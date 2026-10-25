package com.carilt01.trueatmosphere.client;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Matrix4f;

import com.carilt01.trueatmosphere.ModInfo;

public final class SkyBobbing {
    private static final Matrix4f START = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();

    private static final float SYNTHETIC_DEGREES = readSyntheticDegrees();

    private static boolean captured;

    private SkyBobbing() {
    }

    public static float syntheticDegrees() {
        return SYNTHETIC_DEGREES;
    }

    public static void reset() {
        captured = false;
    }

    public static void begin(PoseStack pose) {
        START.set(pose.last().pose());
    }

    public static void end(PoseStack pose) {
        new Matrix4f(START).invert().mul(pose.last().pose()).invert(INVERSE);
        captured = true;
    }

    public static Matrix4f withoutViewBob(Matrix4f modelView) {
        return captured ? new Matrix4f(INVERSE).mul(modelView) : new Matrix4f(modelView);
    }

    private static float readSyntheticDegrees() {
        String value = System.getenv("ATMO_SELFTEST_BOB");
        if (value == null || value.isBlank()) {
            return 0.0F;
        }
        try {
            float degrees = Float.parseFloat(value.trim());
            if (degrees != 0.0F) {
                ModInfo.LOGGER.info("Self-test: adding {} degrees to the view bob", degrees);
            }
            return degrees;
        } catch (NumberFormatException exception) {
            ModInfo.LOGGER.warn("Ignoring ATMO_SELFTEST_BOB: '{}' is not a number", value);
            return 0.0F;
        }
    }
}
