package com.carilt01.trueatmosphere.client;

import org.joml.Matrix4f;

import com.carilt01.trueatmosphere.SkyMode;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;

public final class AtmosphereEffects extends DimensionSpecialEffects.OverworldEffects {
    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
            Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {

        SkyBench.beginSkyStage();

        if (SkyDomeRenderer.mode() == SkyMode.VANILLA) {
            return false;
        }

        if (isFoggy) {
            return false;
        }

        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer == null || !renderer.isReady()) {
            return false;
        }

        renderer.render(level, modelViewMatrix, projectionMatrix, partialTick, setupFog);
        return true;
    }
}
