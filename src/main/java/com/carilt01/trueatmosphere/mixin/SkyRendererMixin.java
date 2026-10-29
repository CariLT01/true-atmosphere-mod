package com.carilt01.trueatmosphere.mixin;

//? if mc >= 1.21.2 {
import com.carilt01.trueatmosphere.client.CelestialSizes;
import com.carilt01.trueatmosphere.client.SkyBench;
import com.carilt01.trueatmosphere.client.SkyDomeRenderer;

import com.mojang.blaze3d.systems.RenderSystem;
//? if mc >= 1.21.2 {
import org.joml.Matrix4f;
//?}
//? if mc >= 1.21.5 {
import com.mojang.blaze3d.vertex.PoseStack;
//?}
//? if mc == 1.21.5 {
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;
//?}
//? if mc >= 1.21.5 {
import org.joml.Matrix4fStack;
//?}
//? if mc >= 1.21.5 && mc < 1.21.11 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
//? if mc >= 26.3 {
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;

import org.joml.Vector3fc;
import org.joml.Vector4fc;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if mc >= 26.3 {
import net.minecraft.client.Camera;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
//? if mc >= 1.21.2 && mc < 1.21.6 {
import net.minecraft.client.renderer.FogParameters;
//?}
import net.minecraft.client.renderer.SkyRenderer;
//? if mc == 1.21.11 {
import net.minecraft.client.renderer.state.SkyRenderState;
//?}
//? if mc >= 26.1 {
import net.minecraft.client.renderer.state.level.SkyRenderState;
//?}

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    //? if mc >= 1.21.2 && mc < 1.21.5 {

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            renderer.render(level,
                    new Matrix4f(RenderSystem.getModelViewMatrix()),
                    new Matrix4f(RenderSystem.getProjectionMatrix()),
                    partialTick(),
                    () -> RenderSystem.setShaderFog(FogParameters.NO_FOG));
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipCelestial(CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipSunrise(CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipDarkDisc(CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    private static boolean skyReplaced() {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        return renderer != null && renderer.isReady() && Minecraft.getInstance().level != null;
    }

    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
    //?} else if mc == 1.21.5 {

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(float red, float green, float blue, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            renderer.render(level, partialTick());
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipSunrise(CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$halfSizeSun(float fade, MultiBufferSource buffers, PoseStack pose,
            CallbackInfo info) {
        if (!trueatmosphere$ownsSky()) {
            return;
        }
        float size = CelestialSizes.VANILLA_SUN_HALF_SIZE * CelestialSizes.SUN_SCALE;
        VertexConsumer consumer = buffers.getBuffer(RenderType.celestial(
                ResourceLocation.withDefaultNamespace("textures/environment/sun.png")));
        Matrix4f matrix = pose.last().pose();
        int color = ARGB.white(fade);
        consumer.addVertex(matrix, -size, 100.0F, -size).setUv(0.0F, 0.0F).setColor(color);
        consumer.addVertex(matrix, size, 100.0F, -size).setUv(1.0F, 0.0F).setColor(color);
        consumer.addVertex(matrix, size, 100.0F, size).setUv(1.0F, 1.0F).setColor(color);
        consumer.addVertex(matrix, -size, 100.0F, size).setUv(0.0F, 1.0F).setColor(color);
        info.cancel();
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.15F))
    private float trueatmosphere$smallerStarMinimum(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.1F))
    private float trueatmosphere$smallerStarVariation(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    private static boolean skyReplaced() {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        return renderer != null && renderer.isReady() && Minecraft.getInstance().level != null;
    }

    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
    //?} else if mc >= 1.21.6 && mc < 1.21.11 {

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            renderer.render(level, RenderSystem.getModelViewMatrix(), partialTick());
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipCelestial(PoseStack pose,
            //? if mc < 1.21.9 {
            MultiBufferSource.BufferSource buffers,
            //?}
            float timeOfDay, int moonPhase, float fade, float starBrightness, CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipSunrise(PoseStack pose,
            //? if mc < 1.21.9 {
            MultiBufferSource.BufferSource buffers,
            //?}
            float timeOfDay, int color, CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipDarkDisc(CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    private static boolean skyReplaced() {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        return renderer != null && renderer.isReady() && Minecraft.getInstance().level != null;
    }

    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
    //?} else if mc == 1.21.11 {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void trueatmosphere$recordSkyState(ClientLevel level, float partialTick,
            net.minecraft.client.Camera camera, SkyRenderState state, CallbackInfo info) {
        SkyDomeRenderer.updateSunAngle(state.sunAngle);
    }

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(int skyColor, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            renderer.render(level, RenderSystem.getModelViewMatrix());
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }

    @Redirect(method = "renderSun", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;scale(FFF)Lorg/joml/Matrix4f;"))
    private Matrix4f trueatmosphere$halfSizeSun(Matrix4fStack stack, float x, float y, float z) {
        float scale = trueatmosphere$ownsSky() ? CelestialSizes.SUN_SCALE : 1.0F;
        return stack.scale(x * scale, y, z * scale);
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.15F))
    private float trueatmosphere$smallerStarMinimum(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.1F))
    private float trueatmosphere$smallerStarVariation(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    //?} else if mc >= 26.1 && mc < 26.3 {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void trueatmosphere$recordSkyState(ClientLevel level, float partialTick,
            net.minecraft.client.Camera camera, SkyRenderState state, CallbackInfo info) {
        SkyDomeRenderer.updateSunAngle(state.sunAngle);
    }

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(int skyColor, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            //? if mc >= 26.2 {
            renderer.render(level, RenderSystem.getModelViewMatrixCopy());
            //?} else {
            renderer.render(level, RenderSystem.getModelViewMatrix());
            //?}
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipSunriseGlow(PoseStack pose, float sunAngle, int sunriseColor,
            CallbackInfo info) {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer != null && renderer.isReady() && Minecraft.getInstance().level != null) {
            info.cancel();
        }
    }

    @Redirect(method = "renderSun", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;scale(FFF)Lorg/joml/Matrix4f;"))
    private Matrix4f trueatmosphere$halfSizeSun(Matrix4fStack stack, float x, float y, float z) {
        float scale = trueatmosphere$ownsSky() ? CelestialSizes.SUN_SCALE : 1.0F;
        return stack.scale(x * scale, y, z * scale);
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.15F))
    private float trueatmosphere$smallerStarMinimum(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.1F))
    private float trueatmosphere$smallerStarVariation(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }
    //?} else {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void trueatmosphere$recordSkyState(ClientLevel level, float partialTick, Camera camera,
            SkyRenderState state, CallbackInfo info) {
        SkyDomeRenderer.updateSunAngle(state.sunAngle);
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
            + "Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V", at = @At("HEAD"))
    private void trueatmosphere$prepareSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null) {
            return;
        }
        renderer.prepare(level);
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$skipSunriseGlow(RenderPass pass, PoseStack pose, float sunAngle,
            Vector4fc sunriseAndSunsetColor, CallbackInfo info) {
        if (skyReplaced()) {
            info.cancel();
        }
    }

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void trueatmosphere$replaceSky(RenderPass pass, Vector3fc skyColor, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (level == null || renderer == null || !renderer.isReady()) {
            return;
        }

        SkyBench.beginSkyStage();
        try {
            renderer.render(pass, RenderSystem.getModelViewMatrixCopy());
        } finally {
            SkyBench.endSkyStage();
        }
        info.cancel();
    }

    @ModifyArg(method = "renderSun", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;applyCelestialBodyTransform"
                    + "(Lcom/mojang/blaze3d/vertex/PoseStack;FF)Lorg/joml/Matrix4f;"), index = 2)
    private float trueatmosphere$halfSizeSun(float halfSize) {
        return trueatmosphere$ownsSky() ? halfSize * CelestialSizes.SUN_SCALE : halfSize;
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.15F))
    private float trueatmosphere$smallerStarMinimum(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    @ModifyConstant(method = "buildStars", constant = @Constant(floatValue = 0.1F))
    private float trueatmosphere$smallerStarVariation(float size) {
        return size * CelestialSizes.STAR_SCALE;
    }

    private static boolean skyReplaced() {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        return renderer != null && renderer.isReady() && Minecraft.getInstance().level != null;
    }
    //?}

    //? if mc >= 1.21.5 {
    private static boolean trueatmosphere$ownsSky() {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        return renderer != null && renderer.isReady() && Minecraft.getInstance().level != null;
    }
    //?}
}
//?}
