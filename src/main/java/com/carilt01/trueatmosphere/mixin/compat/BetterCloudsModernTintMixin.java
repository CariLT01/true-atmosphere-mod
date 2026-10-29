package com.carilt01.trueatmosphere.mixin.compat;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.carilt01.trueatmosphere.client.CloudBaseTint;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

@Pseudo
@Mixin(targets = "com.qendolin.betterclouds.mixin.provider.EffectTintProvider")
public abstract class BetterCloudsModernTintMixin {
    @ModifyReturnValue(method = "getEffectTint", at = @At("RETURN"), require = 0)
    private static Vector3f trueatmosphere$cloudBaseTint(Vector3f tint) {
        return CloudBaseTint.apply(tint);
    }
}
