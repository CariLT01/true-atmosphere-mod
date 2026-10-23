package com.carilt01.trueatmosphere;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue STAR_COUNT = BUILDER
            .comment("Number of stars. Vanilla: 1500")
            .defineInRange("starCount", 1500, 0, 65_535);

    public static final ModConfigSpec.DoubleValue STAR_SIZE_VARIATION = BUILDER
            .comment("Star Size Variation")
            .defineInRange("starSizeVariation", 0.1, 0, 10);

    public static final ModConfigSpec.DoubleValue STAR_MIN_SIZe = BUILDER
            .comment("Minimum Star Size")
            .defineInRange("minimumStarSize", 0.15, 0, 10);

    public static final ModConfigSpec.EnumValue<SkyMode> SKY_MODE = BUILDER
            .comment("Choose rendering geometry mode. QUAD (Fastest) renders one full quad for the sky. DOME renders a dome to cover the sky. VANILLA renders the vanilla sky.")
            .defineEnum("skyMode", SkyMode.QUAD);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static void apply() {
        AtmosphereSettings.skyMode = SKY_MODE.get();
        AtmosphereSettings.starCount = STAR_COUNT.getAsInt();
        AtmosphereSettings.starSizeVariation = STAR_SIZE_VARIATION.get().floatValue();
        AtmosphereSettings.minimumStarSize = STAR_MIN_SIZe.get().floatValue();
    }

    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            apply();
        }
    }
}
