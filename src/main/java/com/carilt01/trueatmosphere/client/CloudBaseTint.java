package com.carilt01.trueatmosphere.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.joml.Vector3f;

import com.carilt01.trueatmosphere.AtmosphereSettings;
import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.SkyMode;
import com.mojang.blaze3d.platform.NativeImage;

//? if mc >= 1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}

//? if mc >= 26.3 {
import com.mojang.renderpearl.api.textures.FilterMode;
//?} else if mc >= 1.21.11 {
import com.mojang.blaze3d.textures.FilterMode;
//?}

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.server.packs.resources.Resource;

//? if mc >= 1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}

public final class CloudBaseTint {
    //? if mc >= 1.21.11 {
    private static final Identifier LIGHTING =
            Identifier.fromNamespaceAndPath("betterclouds", "textures/environment/cloud_light_gradient.png");
    //?} else {
    private static final ResourceLocation LIGHTING =
            ResourceLocation.fromNamespaceAndPath("betterclouds", "textures/environment/cloud_light_gradient.png");
    //?}

    private static final float FULL_DEGREES = 15.0F;
    private static final float NONE_DEGREES = 30.0F;

    private static final float NIGHT_FULL_DEGREES = -1.4F;
    private static final float NIGHT_NONE_DEGREES = -2.9F;

    private static final float MIX = 1.0F;

    private static final float AWAY_WEIGHT = 0.5F;

    private static final double GAMMA = 2.2;

    private static final double TICKS_PER_DAY = 24000.0;

    private static final int TARGET_ROWS = 2400;

    private static boolean loaded;
    private static float[] colors = new float[0];
    private static float[] elevations = new float[0];

    private static long calls;
    private static int mixedRows;
    private static float largestRowStep;
    private static float largestRowElevation = Float.NaN;
    private static int largestRowIndex = -1;
    private static AbstractTexture installed;
    private static AbstractTexture lastSeen;
    private static float lastRed = Float.NaN;
    private static float lastGreen = Float.NaN;
    private static float lastBlue = Float.NaN;
    private static float lastElevation = Float.NaN;

    private CloudBaseTint() {
    }

    private static void load(Minecraft minecraft) {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            SkyLut.CloudTint tint = SkyLut.cloudTint(minecraft.getResourceManager());
            if (tint.colors.length == 0 || tint.colors.length != tint.elevations.length * 3) {
                ModInfo.LOGGER.warn("Cloud base tint is malformed ({} steps, {} colours)",
                        tint.elevations.length, tint.colors.length / 3);
                return;
            }
            colors = tint.colors;
            elevations = tint.elevations;
            ModInfo.LOGGER.info("Loaded cloud base tint: {} sun steps", elevations.length);
        } catch (IOException | RuntimeException e) {
            ModInfo.LOGGER.warn("Could not read the cloud base tint; Better Clouds stays vanilla", e);
        }
    }

    public static Vector3f apply(Vector3f tint) {
        if (tint == null) {
            return null;
        }
        calls++;
        if (!enabled()) {
            return tint;
        }
        armed = true;
        return tint;
    }

    private static boolean enabled() {
        return AtmosphereSettings.betterCloudsTint && AtmosphereSettings.skyMode != SkyMode.VANILLA;
    }

    public static void tick(Minecraft minecraft) {
        if (!armed) {
            return;
        }
        armed = false;
        if (!enabled()) {
            return;
        }
        try {
            install(minecraft);
        } catch (RuntimeException e) {
            reportOnce(e);
        }
    }

    private static boolean armed;
    private static boolean reported;

    private static void reportOnce(RuntimeException cause) {
        if (reported) {
            return;
        }
        reported = true;
        ModInfo.LOGGER.warn("Cloud base tint disabled after a failure; Better Clouds stays vanilla", cause);
    }

    private static void install(Minecraft minecraft) {
        TextureManager textures = minecraft.getTextureManager();
        AbstractTexture current = textures.getTexture(LIGHTING);
        if (current == installed || current == lastSeen) {
            return;
        }
        lastSeen = current;

        load(minecraft);
        if (colors.length == 0) {
            return;
        }
        Optional<Resource> resource = minecraft.getResourceManager().getResource(LIGHTING);
        if (resource.isEmpty()) {
            return;
        }

        NativeImage source;
        try (InputStream in = resource.get().open()) {
            source = NativeImage.read(in);
        } catch (IOException e) {
            ModInfo.LOGGER.warn("Could not read Better Clouds' light gradient", e);
            return;
        }

        NativeImage image = source;
        if (source.getHeight() < TARGET_ROWS) {
            image = stretch(source, TARGET_ROWS);
            source.close();
        }

        largestRowStep = 0.0F;
        largestRowElevation = Float.NaN;
        int rows = mix(image);
        if (rows == 0) {
            image.close();
            return;
        }

        //? if mc >= 1.21.11 {
        DynamicTexture texture = new LinearGradient(image);
        //?} else if mc >= 1.21.5 {
        DynamicTexture texture = new DynamicTexture(() -> "trueatmosphere cloud light gradient", image);
        texture.setFilter(true, false);
        //?} else {
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        //?}
        textures.register(LIGHTING, texture);
        installed = texture;
        mixedRows = rows;
        ModInfo.LOGGER.info("Mixed this mod's cloud colour into {} of {} times of day of Better Clouds' sun gradient",
                rows, image.getHeight());
    }

    //? if mc >= 1.21.11 {
    private static final class LinearGradient extends DynamicTexture {
        LinearGradient(NativeImage image) {
            super(() -> "trueatmosphere cloud light gradient", image);
            this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        }
    }
    //?}

    private static NativeImage stretch(NativeImage source, int targetHeight) {
        int width = source.getWidth();
        int height = source.getHeight();
        NativeImage tall = new NativeImage(NativeImage.Format.RGBA, width, targetHeight, false);
        for (int y = 0; y < targetHeight; y++) {
            float v = (y + 0.5F) * height / targetHeight - 0.5F;
            int lower = (int) Math.floor(v);
            float t = v - lower;
            int row0 = Math.max(0, Math.min(height - 1, lower));
            int row1 = Math.max(0, Math.min(height - 1, lower + 1));
            for (int x = 0; x < width; x++) {
                setPixel(tall, x, y, blend(pixel(source, x, row0), pixel(source, x, row1), t));
            }
        }
        return tall;
    }

    private static int blend(int from, int to, float t) {
        return pack(
                encode(lerp(decode(from & 0xFF), decode(to & 0xFF), t)),
                encode(lerp(decode((from >>> 8) & 0xFF), decode((to >>> 8) & 0xFF), t)),
                encode(lerp(decode((from >>> 16) & 0xFF), decode((to >>> 16) & 0xFF), t)),
                lerp(((from >>> 24) & 0xFF) / 255.0F, ((to >>> 24) & 0xFF) / 255.0F, t));
    }

    private static int mix(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int rows = 0;

        for (int y = 0; y < height; y++) {
            float[] sample = sampleAt((y + 0.5) / height);
            if (sample == null) {
                continue;
            }
            float weight = fade(sample[3]) * sunsetFade(sample[3]);
            if (weight <= 0.0F) {
                continue;
            }

            float luminance = luminance(sample[0], sample[1], sample[2]);
            if (luminance <= 1.0E-6F) {
                continue;
            }
            float red = sample[0] / luminance;
            float green = sample[1] / luminance;
            float blue = sample[2] / luminance;

            float physical = Math.min(1.0F, luminance);

            boolean touched = false;
            for (int x = 0; x < width; x++) {
                float near = sunProximity((x + 0.5F) / width);
                float mix = MIX * weight * (AWAY_WEIGHT + (1.0F - AWAY_WEIGHT) * near);
                if (mix <= 0.004F) {
                    continue;
                }

                int pixel = pixel(image, x, y);
                float alpha = ((pixel >>> 24) & 0xFF) / 255.0F;
                float texelRed = decode(pixel & 0xFF);
                float texelGreen = decode((pixel >>> 8) & 0xFF);
                float texelBlue = decode((pixel >>> 16) & 0xFF);

                float texelLuminance = luminance(texelRed, texelGreen, texelBlue);
                float target = Math.max(texelLuminance, physical);
                setPixel(image, x, y, pack(
                        encode(lerp(texelRed, red * target, mix)),
                        encode(lerp(texelGreen, green * target, mix)),
                        encode(lerp(texelBlue, blue * target, mix)),
                        alpha));
                touched = true;
            }
            if (touched) {
                rows++;
                lastElevation = sample[3];
                lastRed = red;
                lastGreen = green;
                lastBlue = blue;
            }
        }
        measureRowSteps(image);
        return rows;
    }

    private static void measureRowSteps(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (height < 2 || width < 1) {
            return;
        }
        largestRowStep = 0.0F;
        largestRowElevation = Float.NaN;
        for (int y = 0; y + 1 < height; y++) {
            float worst = 0.0F;
            for (int x = 0; x < width; x++) {
                int here = pixel(image, x, y);
                int there = pixel(image, x, y + 1);
                worst = Math.max(worst, Math.abs(decode(here & 0xFF) - decode(there & 0xFF)));
                worst = Math.max(worst, Math.abs(decode((here >>> 8) & 0xFF) - decode((there >>> 8) & 0xFF)));
                worst = Math.max(worst, Math.abs(decode((here >>> 16) & 0xFF) - decode((there >>> 16) & 0xFF)));
            }
            if (worst > largestRowStep) {
                largestRowStep = worst;
                largestRowElevation = sampleAt((y + 1.0) / height)[3];
                largestRowIndex = y;
            }
        }
    }

    private static float[] sampleAt(double dayFraction) {
        int steps = colors.length / 3;
        double ticks = dayFraction * TICKS_PER_DAY;
        double phase = (ticks / TICKS_PER_DAY - 0.25) - Math.floor(ticks / TICKS_PER_DAY - 0.25);
        double slant = 0.5 - Math.cos(phase * Math.PI) / 2.0;
        float angle = (float) (((phase * 2.0 + slant) / 3.0) * 360.0 + 90.0);
        angle %= 360.0F;
        if (angle < 0.0F) {
            angle += 360.0F;
        }

        double position = angle / (360.0 / steps);
        int lower = (int) Math.floor(position);
        float blend = (float) (position - lower);
        int current = Math.floorMod(lower, steps);
        int next = Math.floorMod(lower + 1, steps);

        float red = lerp(colors[current * 3], colors[next * 3], blend);
        float green = lerp(colors[current * 3 + 1], colors[next * 3 + 1], blend);
        float blue = lerp(colors[current * 3 + 2], colors[next * 3 + 2], blend);
        float elevation = lerp(elevations[current], elevations[next], blend);
        return new float[] { red, green, blue, elevation };
    }

    private static float sunProximity(float u) {
        float x;
        if (u <= 0.375F) {
            x = 1.0F - u / 0.75F;
        } else if (u <= 0.625F) {
            x = 0.5F - (u - 0.375F) / 0.25F;
        } else {
            x = -0.5F * ((u - 0.625F) / 0.375F + 1.0F);
        }
        float cosine = Math.max(-1.0F, Math.min(1.0F, x / 0.9F));
        return (1.0F + cosine) * 0.5F;
    }

    private static int pixel(NativeImage image, int x, int y) {
        //? if mc >= 1.21.2 {
        int argb = image.getPixel(x, y);
        return (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >>> 16) | ((argb & 0x000000FF) << 16);
        //?} else {
        return image.getPixelRGBA(x, y);
        //?}
    }

    private static void setPixel(NativeImage image, int x, int y, int argb) {
        //? if mc >= 1.21.5 {
        image.setPixelABGR(x, y, argb);
        //?} else if mc >= 1.21.2 {
        image.setPixel(x, y, argb);
        //?} else {
        image.setPixelRGBA(x, y, argb);
        //?}
    }

    private static int pack(float red, float green, float blue, float alpha) {
        int a = Math.round(alpha * 255.0F) & 0xFF;
        int r = Math.round(red * 255.0F) & 0xFF;
        int g = Math.round(green * 255.0F) & 0xFF;
        int b = Math.round(blue * 255.0F) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static float decode(int channel) {
        return (float) Math.pow(channel / 255.0, GAMMA);
    }

    private static float encode(float linear) {
        return (float) Math.pow(Math.max(0.0F, Math.min(1.0F, linear)), 1.0 / GAMMA);
    }

    private static float luminance(float red, float green, float blue) {
        return 0.2126F * red + 0.7152F * green + 0.0722F * blue;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static float fade(float elevationDegrees) {
        if (elevationDegrees <= FULL_DEGREES) {
            return 1.0F;
        }
        if (elevationDegrees >= NONE_DEGREES) {
            return 0.0F;
        }
        return smoothstep((NONE_DEGREES - elevationDegrees) / (NONE_DEGREES - FULL_DEGREES));
    }

    private static float sunsetFade(float elevationDegrees) {
        if (elevationDegrees >= NIGHT_FULL_DEGREES) {
            return 1.0F;
        }
        if (elevationDegrees <= NIGHT_NONE_DEGREES) {
            return 0.0F;
        }
        return smoothstep((elevationDegrees - NIGHT_NONE_DEGREES) / (NIGHT_FULL_DEGREES - NIGHT_NONE_DEGREES));
    }

    private static float smoothstep(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    public static String probe() {
        if (!AtmosphereSettings.betterCloudsTint) {
            return "disabled by config (betterCloudsTint)";
        }
        if (calls == 0) {
            return "hook never called by Better Clouds";
        }
        if (installed == null) {
            return calls + " calls, gradient not mixed yet";
        }
        return calls + " calls, gradient mixed at " + mixedRows + " times of day of "
                + TARGET_ROWS + ", largest step between adjacent rows "
                + String.format("%.4f", largestRowStep) + " at row " + largestRowIndex
                + " (sun elevation " + Math.round(largestRowElevation) + ")"
                + ", last row at sun elevation " + Math.round(lastElevation)
                + " hue (" + String.format("%.2f", lastRed) + ", "
                + String.format("%.2f", lastGreen) + ", " + String.format("%.2f", lastBlue) + ")";
    }
}
