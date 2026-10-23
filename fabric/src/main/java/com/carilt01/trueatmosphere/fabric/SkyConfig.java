package com.carilt01.trueatmosphere.fabric;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.carilt01.trueatmosphere.AtmosphereSettings;
import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.SkyMode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.fabricmc.loader.api.FabricLoader;

final class SkyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SkyConfig() {
    }

    static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(ModInfo.MODID + ".json");
        JsonObject root = new JsonObject();
        boolean exists = Files.isRegularFile(file);

        if (exists) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject parsed = GSON.fromJson(reader, JsonObject.class);
                if (parsed != null) {
                    root = parsed;
                }
            } catch (IOException | RuntimeException e) {
                ModInfo.LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }

        boolean changed = fill(root, "sky", AtmosphereSettings.skyMode.name().toLowerCase(Locale.ROOT));
        changed |= fill(root, "starCount", AtmosphereSettings.starCount);
        changed |= fill(root, "starSizeVariation", AtmosphereSettings.starSizeVariation);
        changed |= fill(root, "minimumStarSize", AtmosphereSettings.minimumStarSize);

        apply(root);
        ModInfo.LOGGER.info("Sky config: {} (geometry {}, {} stars)", file, AtmosphereSettings.skyMode,
                AtmosphereSettings.starCount);

        if (changed || !exists) {
            write(file, root);
        }
    }

    private static boolean fill(JsonObject root, String key, Object value) {
        if (root.has(key)) {
            return false;
        }
        if (value instanceof Number number) {
            root.add(key, new JsonPrimitive(number));
        } else {
            root.add(key, new JsonPrimitive(String.valueOf(value)));
        }
        return true;
    }

    private static void apply(JsonObject root) {
        try {
            AtmosphereSettings.skyMode = SkyMode.byName(root.get("sky").getAsString());
            AtmosphereSettings.starCount = clamp(root.get("starCount").getAsInt(), 0, 65_535);
            AtmosphereSettings.starSizeVariation = clamp(root.get("starSizeVariation").getAsFloat(), 0.0F, 10.0F);
            AtmosphereSettings.minimumStarSize = clamp(root.get("minimumStarSize").getAsFloat(), 0.0F, 10.0F);
        } catch (RuntimeException e) {
            ModInfo.LOGGER.warn("Malformed config value, keeping the defaults", e);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void write(Path file, JsonObject root) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            ModInfo.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
