package com.carilt01.trueatmosphere.fabric.mixin;

import com.carilt01.trueatmosphere.client.SkyDomeRenderer;
import com.mojang.blaze3d.systems.RenderSystem;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
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
}
