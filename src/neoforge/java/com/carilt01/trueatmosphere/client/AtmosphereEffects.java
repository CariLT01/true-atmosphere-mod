package com.carilt01.trueatmosphere.client;

//? if mc < 1.21.9 {
import org.joml.Matrix4f;

import com.carilt01.trueatmosphere.SkyMode;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;

public final class AtmosphereEffects extends DimensionSpecialEffects.OverworldEffects {
    //? if mc < 1.21.6 {
    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
            //? if mc < 1.21.6 {
            Matrix4f projectionMatrix,
            //?}
            //? if mc == 1.21.1 {
            boolean isFoggy,
            //?}
            Runnable setupFog) {

        SkyBench.beginSkyStage();

        if (SkyDomeRenderer.mode() == SkyMode.VANILLA) {
            return false;
        }

        //? if mc == 1.21.1 {
        if (isFoggy) {
            return false;
        }
        //?}

        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer == null || !renderer.isReady()) {
            return false;
        }

        //? if mc == 1.21.5 {
        renderer.render(level, partialTick);
        //?} else {
        renderer.render(level, modelViewMatrix, projectionMatrix, partialTick, setupFog);
        //?}
        return true;
    }
    //?} else {

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix,
            Camera camera, Runnable setupFog) {

        SkyBench.beginSkyStage();

        if (SkyDomeRenderer.mode() == SkyMode.VANILLA) {
            return false;
        }

        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer == null || !renderer.isReady()) {
            return false;
        }

        renderer.render(level, modelViewMatrix, partialTick);
        return true;
    }
    //?}
}
//?}
