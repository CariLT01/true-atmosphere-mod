package com.carilt01.trueatmosphere;

public final class AtmosphereSettings {

    public static volatile SkyMode skyMode = SkyMode.QUAD;

    public static volatile int starCount = 1500;
    public static volatile float starSizeVariation = 0.1F;
    public static volatile float minimumStarSize = 0.15F;

    private AtmosphereSettings() {
    }
}
