package com.carilt01.trueatmosphere;

public enum SkyMode {

    DOME,

    QUAD,

    VANILLA;

    public static SkyMode byName(String name) {
        if (name != null) {
            String trimmed = name.trim();
            for (SkyMode mode : values()) {
                if (mode.name().equalsIgnoreCase(trimmed)) {
                    return mode;
                }
            }
        }
        return DOME;
    }
}
