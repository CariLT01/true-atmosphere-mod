package com.carilt01.trueatmosphere.mixin;

import com.carilt01.trueatmosphere.client.SkyBobbing;
import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;
//? if mc >= 26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
//?}

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    //? if mc >= 26.1 {
    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void trueatmosphere$beforeHurt(CameraRenderState state, PoseStack pose, CallbackInfo info) {
        SkyBobbing.reset();
    }

    @Inject(method = "bobView", at = @At("HEAD"))
    private void trueatmosphere$beforeBob(CameraRenderState state, PoseStack pose, CallbackInfo info) {
        trueatmosphere$startBob(pose);
    }

    @Inject(method = "bobView", at = @At("TAIL"))
    private void trueatmosphere$afterBob(CameraRenderState state, PoseStack pose, CallbackInfo info) {
        SkyBobbing.end(pose);
    }
    //?} else {
    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void trueatmosphere$beforeHurt(PoseStack pose, float partialTick, CallbackInfo info) {
        SkyBobbing.reset();
    }

    @Inject(method = "bobView", at = @At("HEAD"))
    private void trueatmosphere$beforeBob(PoseStack pose, float partialTick, CallbackInfo info) {
        trueatmosphere$startBob(pose);
    }

    @Inject(method = "bobView", at = @At("TAIL"))
    private void trueatmosphere$afterBob(PoseStack pose, float partialTick, CallbackInfo info) {
        SkyBobbing.end(pose);
    }
    //?}

    private static void trueatmosphere$startBob(PoseStack pose) {

        SkyBobbing.begin(pose);
        float synthetic = SkyBobbing.syntheticDegrees();
        if (synthetic != 0.0F) {
            Quaternionf pitch = new Quaternionf().rotateX((float) Math.toRadians(synthetic));
            //? if mc >= 26.1 {
            pose.mulPose(new Matrix4f().rotation(pitch));
            //?} else {
            pose.mulPose(pitch);
            //?}
        }
    }
}
