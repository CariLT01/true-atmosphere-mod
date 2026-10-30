package com.carilt01.trueatmosphere.client;

import java.io.IOException;
import java.io.InputStream;

import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.sky.SkyBakeProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mojang.logging.LogUtils;

//? if mc >= 1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;

import io.airlift.compress.zstd.ZstdDecompressor;

import org.slf4j.Logger;

public final class SkyLut {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "sky/sky.bin";

    public static final String CLEAR = "clear";
    public static final String RAIN = "rain";
    public static final String THUNDER = "thunder";
    public static final String[] VARIANTS = {CLEAR, RAIN, THUNDER};

    private static SkyLut[] loaded;
    private static CloudTint tint;

    private final float[] display;

    public final String variant;
    public final int sunSteps;
    public final int altitudeSamples;
    public final int azimuthSamples;

    private SkyLut(String variant, float[] display, int sunSteps, int altitudeSamples, int azimuthSamples) {
        this.variant = variant;
        this.display = display;
        this.sunSteps = sunSteps;
        this.altitudeSamples = altitudeSamples;
        this.azimuthSamples = azimuthSamples;
    }

    public static final class CloudTint {
        public final float[] colors;
        public final float[] elevations;

        private CloudTint(float[] colors, float[] elevations) {
            this.colors = colors;
            this.elevations = elevations;
        }
    }

    public static synchronized SkyLut[] loadAll(ResourceManager resources) throws IOException {
        if (loaded != null) {
            return loaded;
        }
        SkyBakeProto.SkyBake bake = read(resources);
        SkyLut[] tables = new SkyLut[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) {
            SkyBakeProto.SkyTable table = find(bake, VARIANTS[i]);
            if (table == null) {
                if (i == 0) {
                    throw new IOException("Sky bake " + RESOURCE + " has no " + CLEAR + " table");
                }
                LOGGER.warn("No {} sky table, weather blending falls back to clear", VARIANTS[i]);
                tables[i] = tables[0];
                continue;
            }
            tables[i] = from(table);
            if (i > 0 && tables[i].stepFloats() != tables[0].stepFloats()) {
                LOGGER.warn("{} sky table is {}x{}, not {}x{}; blending falls back to clear",
                        VARIANTS[i], tables[i].azimuthSamples, tables[i].altitudeSamples,
                        tables[0].azimuthSamples, tables[0].altitudeSamples);
                tables[i] = tables[0];
            }
        }
        tint = cloudTint(bake.getCloudTint());
        loaded = tables;
        LOGGER.info("Loaded sky bake {} ({} tables, cloud tint with {} steps)",
                RESOURCE, tables.length, tint.elevations.length);
        return tables;
    }

    public static synchronized CloudTint cloudTint(ResourceManager resources) throws IOException {
        loadAll(resources);
        return tint;
    }

    public int stepFloats() {
        return this.altitudeSamples * this.azimuthSamples * 3;
    }

    public float[] display() {
        return this.display;
    }

    public int stepOffset(int step) {
        return step * stepFloats();
    }

    public void sample(float dirX, float dirY, float dirZ, int step, int nextStep, float blend, float[] out) {
        float u = (float) (Math.atan2(dirZ, dirX) / (Math.PI * 2.0));
        if (u < 0.0F) {
            u += 1.0F;
        }
        float fv = (Mth.clamp(dirY, -1.0F, 1.0F) * 0.5F + 0.5F) * (this.altitudeSamples - 1);

        int baseA = stepOffset(step);
        int baseB = stepOffset(nextStep);

        float a0 = 0.0F;
        float a1 = 0.0F;
        float a2 = 0.0F;
        float b0 = 0.0F;
        float b1 = 0.0F;
        float b2 = 0.0F;

        float fu = u * this.azimuthSamples;
        int x0 = (int) Math.floor(fu) % this.azimuthSamples;
        int x1 = (x0 + 1) % this.azimuthSamples;
        int y0 = Mth.clamp((int) Math.floor(fv), 0, this.altitudeSamples - 1);
        int y1 = Math.min(y0 + 1, this.altitudeSamples - 1);
        float tx = fu - (float) Math.floor(fu);
        float ty = fv - y0;

        for (int corner = 0; corner < 4; corner++) {
            int x = (corner & 1) == 0 ? x0 : x1;
            int y = (corner & 2) == 0 ? y0 : y1;
            float wx = (corner & 1) == 0 ? 1.0F - tx : tx;
            float wy = (corner & 2) == 0 ? 1.0F - ty : ty;
            float w = wx * wy;

            int i = (y * this.azimuthSamples + x) * 3;
            a0 += this.display[baseA + i] * w;
            a1 += this.display[baseA + i + 1] * w;
            a2 += this.display[baseA + i + 2] * w;
            b0 += this.display[baseB + i] * w;
            b1 += this.display[baseB + i + 1] * w;
            b2 += this.display[baseB + i + 2] * w;
        }

        out[0] = Mth.lerp(blend, a0, b0);
        out[1] = Mth.lerp(blend, a1, b1);
        out[2] = Mth.lerp(blend, a2, b2);
    }

    private static SkyBakeProto.SkyTable find(SkyBakeProto.SkyBake bake, String variant) {
        for (SkyBakeProto.SkyTable table : bake.getTablesList()) {
            if (variant.equals(table.getVariant())) {
                return table;
            }
        }
        return null;
    }

    private static SkyLut from(SkyBakeProto.SkyTable table) throws IOException {
        int sunSteps = table.getSunSteps();
        int altitudes = table.getAltitudeSamples();
        int azimuths = table.getAzimuthSamples();
        if (sunSteps <= 0 || altitudes <= 0 || azimuths <= 0) {
            throw new IOException("Invalid " + table.getVariant() + " sky table " + sunSteps + "x" + altitudes + "x" + azimuths);
        }
        int expected = sunSteps * altitudes * azimuths * 3;
        if (table.getDisplayCount() != expected) {
            throw new IOException("Sky table " + table.getVariant() + " holds " + table.getDisplayCount()
                    + " floats, expected " + expected);
        }
        float[] display = new float[expected];
        for (int i = 0; i < expected; i++) {
            display[i] = table.getDisplay(i);
        }
        return new SkyLut(table.getVariant(), display, sunSteps, altitudes, azimuths);
    }

    private static CloudTint cloudTint(SkyBakeProto.CloudTint source) {
        float[] colors = new float[source.getColorsCount() * 3];
        for (int i = 0; i < source.getColorsCount(); i++) {
            SkyBakeProto.TintColor color = source.getColors(i);
            colors[i * 3] = color.getRed();
            colors[i * 3 + 1] = color.getGreen();
            colors[i * 3 + 2] = color.getBlue();
        }
        float[] elevations = new float[source.getSunElevationsDegreesCount()];
        for (int i = 0; i < elevations.length; i++) {
            elevations[i] = source.getSunElevationsDegrees(i);
        }
        return new CloudTint(colors, elevations);
    }

    private static SkyBakeProto.SkyBake read(ResourceManager resources) throws IOException {
        //? if mc >= 1.21.11 {
        Identifier id = Identifier.fromNamespaceAndPath(ModInfo.MODID, RESOURCE);
        //?} else {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, RESOURCE);
        //?}

        byte[] blob;
        try (InputStream in = resources.getResourceOrThrow(id).open()) {
            blob = in.readAllBytes();
        }
        long size = ZstdDecompressor.getDecompressedSize(blob, 0, blob.length);
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new IOException("Sky bake " + id + " has no usable size (" + size + " bytes)");
        }
        byte[] payload = new byte[(int) size];
        new ZstdDecompressor().decompress(blob, 0, blob.length, payload, 0, payload.length);
        try {
            return SkyBakeProto.SkyBake.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new IOException("Corrupt sky bake " + id, e);
        }
    }
}
