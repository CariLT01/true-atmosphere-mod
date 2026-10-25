package com.carilt01.trueatmosphere.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;

//? if mc >= 1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;

import com.carilt01.trueatmosphere.ModInfo;

import org.slf4j.Logger;

public final class SkyLut {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String CLEAR = "clear";
    public static final String RAIN = "rain";
    public static final String THUNDER = "thunder";
    public static final String[] VARIANTS = {CLEAR, RAIN, THUNDER};

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

    public static SkyLut load(ResourceManager resources, String variant) throws IOException {
        String stem = CLEAR.equals(variant) ? "sky/sky_lut" : "sky/sky_lut_" + variant;
        //? if mc >= 1.21.11 {
        Identifier metaId = Identifier.fromNamespaceAndPath(ModInfo.MODID, stem + ".json");
        Identifier dataId = Identifier.fromNamespaceAndPath(ModInfo.MODID, stem + ".npy");
        //?} else {
        ResourceLocation metaId = ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, stem + ".json");
        ResourceLocation dataId = ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, stem + ".npy");
        //?}

        JsonObject meta;
        try (InputStream in = resources.getResourceOrThrow(metaId).open();
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            meta = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("Invalid sky LUT metadata in " + metaId, e);
        }

        int sunSteps = meta.get("sun_steps").getAsInt();
        int altitudes = meta.get("altitude_samples").getAsInt();
        int azimuths = meta.get("azimuth_samples").getAsInt();
        if (sunSteps <= 0 || altitudes <= 0 || azimuths <= 0) {
            throw new IOException("Invalid sky LUT dimensions in " + metaId);
        }

        int expected = sunSteps * altitudes * azimuths * 3;
        float[] display;
        try (InputStream in = resources.getResourceOrThrow(dataId).open()) {
            display = readNpyFloats(in, dataId, expected);
        }

        SkyLut lut = new SkyLut(variant, display, sunSteps, altitudes, azimuths);
        LOGGER.info("Loaded {} sky LUT {}x{}x{} ({} KiB, steps of {} KiB)", variant, sunSteps, altitudes, azimuths,
                display.length * 4 / 1024, lut.stepFloats() * 4 / 1024);
        return lut;
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

    //? if mc >= 1.21.11 {
    private static float[] readNpyFloats(InputStream in, Identifier dataId, int expectedFloats)
    //?} else {
    private static float[] readNpyFloats(InputStream in, ResourceLocation dataId, int expectedFloats)
    //?}
            throws IOException {
        byte[] bytes = in.readAllBytes();
        if (bytes.length < 10
                || (bytes[0] & 0xFF) != 0x93
                || bytes[1] != 'N' || bytes[2] != 'U' || bytes[3] != 'M'
                || bytes[4] != 'P' || bytes[5] != 'Y') {
            throw new IOException("Not a .npy file: " + dataId);
        }

        int major = bytes[6] & 0xFF;
        int headerStart;
        int headerLength;
        if (major == 1) {
            headerStart = 10;
            headerLength = (bytes[8] & 0xFF) | ((bytes[9] & 0xFF) << 8);
        } else {
            headerStart = 12;
            headerLength = (bytes[8] & 0xFF)
                    | ((bytes[9] & 0xFF) << 8)
                    | ((bytes[10] & 0xFF) << 16)
                    | ((bytes[11] & 0xFF) << 24);
        }
        if (headerStart + headerLength > bytes.length) {
            throw new IOException("Corrupt .npy header in " + dataId);
        }

        String header = new String(bytes, headerStart, headerLength, StandardCharsets.ISO_8859_1);
        if (!header.contains("<f4")) {
            throw new IOException("Unsupported .npy dtype (expected little-endian float32): " + header);
        }

        int dataStart = headerStart + headerLength;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, dataStart, bytes.length - dataStart).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer floats = buffer.asFloatBuffer();
        if (floats.remaining() < expectedFloats) {
            throw new IOException("Sky LUT payload is " + floats.remaining() + " floats, expected " + expectedFloats);
        }

        float[] out = new float[expectedFloats];
        floats.get(out);
        return out;
    }
}
