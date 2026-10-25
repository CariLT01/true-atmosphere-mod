package com.carilt01.trueatmosphere.fabric.mixin;

import com.carilt01.trueatmosphere.client.SkyDomeRenderer;

//? if mc == 1.21.1 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
//? if mc >= 1.21.11 {
import org.joml.Vector3fc;
//?} else {
import org.joml.Vector3f;
//?}
//? if mc >= 1.21.2 {
import org.joml.Vector4f;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if mc == 1.21.1 || mc >= 26.1 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

import net.minecraft.client.Camera;
//? if mc == 1.21.1 {
import net.minecraft.client.Minecraft;
//?}
import net.minecraft.client.multiplayer.ClientLevel;
//? if mc >= 1.21.6 {
import net.minecraft.client.renderer.fog.FogRenderer;
//?} else {
import net.minecraft.client.renderer.FogRenderer;
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    //? if mc >= 26.1 {

    @Inject(method = "computeFogColor", at = @At("TAIL"))
    private void trueatmosphere$atmosphericFog(Camera camera, float partialTick, ClientLevel level,
            int renderDistance, float darkenWorldAmount, Vector4f dest, CallbackInfo info) {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady() || dest == null) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vector3fc look = camera.forwardVector();
        float[] colour = new float[3];
        if (!renderer.horizonColour(level, look.x(), look.z(), colour)) {
            return;
        }

        dest.set(colour[0], colour[1], colour[2], dest.w);
    }
    //?} else if mc == 1.21.1 {
    @Inject(method = "levelFogColor", at = @At("TAIL"))
    private static void trueatmosphere$atmosphericFog(CallbackInfo info) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vector3f look = camera.getLookVector();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        float[] colour = new float[3];
        if (!renderer.horizonColour(level, partialTick, look.x, look.z, colour)) {
            return;
        }

        RenderSystem.setShaderFogColor(colour[0], colour[1], colour[2]);
    }
    //?} else if mc == 1.21.11 {
    @Inject(method = "computeFogColor", at = @At("RETURN"), cancellable = true)
    private void trueatmosphere$atmosphericFog(Camera camera, float partialTick, ClientLevel level,
            int renderDistance, float darkenWorldAmount, CallbackInfoReturnable<Vector4f> info) {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vector3fc look = camera.forwardVector();
        float[] colour = new float[3];
        if (!renderer.horizonColour(level, look.x(), look.z(), colour)) {
            return;
        }

        Vector4f current = info.getReturnValue();
        info.setReturnValue(new Vector4f(colour[0], colour[1], colour[2], current == null ? 1.0F : current.w));
    }
    //?} else if mc >= 1.21.6 {
    @Inject(method = "computeFogColor", at = @At("RETURN"), cancellable = true)
    private void trueatmosphere$atmosphericFog(Camera camera, float partialTick, ClientLevel level,
            int renderDistance, float darkenWorldAmount, boolean isFoggy,
            CallbackInfoReturnable<Vector4f> info) {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vector3f look = camera.getLookVector();
        float[] colour = new float[3];
        if (!renderer.horizonColour(level, partialTick, look.x, look.z, colour)) {
            return;
        }

        Vector4f current = info.getReturnValue();
        info.setReturnValue(new Vector4f(colour[0], colour[1], colour[2], current == null ? 1.0F : current.w));
    }
    //?} else {
    @Inject(method = "computeFogColor", at = @At("RETURN"), cancellable = true)
    private static void trueatmosphere$atmosphericFog(Camera camera, float partialTick, ClientLevel level,
            int renderDistance, float darkenWorldAmount, CallbackInfoReturnable<Vector4f> info) {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vector3f look = camera.getLookVector();
        float[] colour = new float[3];
        if (!renderer.horizonColour(level, partialTick, look.x, look.z, colour)) {
            return;
        }

        Vector4f current = info.getReturnValue();
        info.setReturnValue(new Vector4f(colour[0], colour[1], colour[2], current == null ? 1.0F : current.w));
    }
    //?}
}
