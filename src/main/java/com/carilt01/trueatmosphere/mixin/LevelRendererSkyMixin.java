package com.carilt01.trueatmosphere.mixin;

//? if mc == 1.21.1 {

import com.carilt01.trueatmosphere.client.SkyBench;
import com.carilt01.trueatmosphere.client.SkyDomeRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(Matrix4f pose, Matrix4f projection, float partialTick, Camera camera,
            boolean thickFog, Runnable skyFogSetup, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            renderer.render(level, new Matrix4f(pose), new Matrix4f(projection), partialTick, skyFogSetup);
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }
}

//?}
