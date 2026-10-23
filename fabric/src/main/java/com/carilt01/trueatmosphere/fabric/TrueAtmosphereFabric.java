package com.carilt01.trueatmosphere.fabric;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.client.SkyBench;
import com.carilt01.trueatmosphere.client.SkyDomeRenderer;
import com.carilt01.trueatmosphere.client.SkyLut;
import com.carilt01.trueatmosphere.client.SkySelfTest;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class TrueAtmosphereFabric implements ClientModInitializer {
    private static final ResourceLocation SKY_DOME_SHADER =
            ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "sky_dome");
    private static final ResourceLocation SKY_QUAD_SHADER =
            ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "sky_quad");

    private static ShaderInstance domeShader;
    private static ShaderInstance quadShader;
    private static SkyLut[] luts;
    private static CompletableFuture<SkyLut[]> lutLoad;
    private static boolean lutFailed;

    @Override
    public void onInitializeClient() {
        SkyConfig.load();

        CoreShaderRegistrationCallback.EVENT.register(context -> {
            context.register(SKY_DOME_SHADER, DefaultVertexFormat.POSITION_COLOR, loaded -> {
                domeShader = loaded;
                pushShaders();
            });
            context.register(SKY_QUAD_SHADER, DefaultVertexFormat.POSITION, loaded -> {
                quadShader = loaded;
                pushShaders();
            });
        });

        DimensionRenderingRegistry.registerSkyRenderer(Level.OVERWORLD, context -> {
            SkyDomeRenderer renderer = SkyDomeRenderer.instance;
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.render(context.world(), context.positionMatrix(), context.projectionMatrix(),
                    context.tickCounter().getGameTimeDeltaPartialTick(false),
                    FogRenderer::setupNoFog);
        });

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            ensureLoaded(minecraft);
            SkyBench.tick(minecraft);
            SkySelfTest.tick(minecraft);
        });

        ModInfo.LOGGER.info("True Atmosphere client ready on Fabric");
    }

    private static void pushShaders() {
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer != null) {
            renderer.setShaders(domeShader, quadShader);
        }
    }

    private static void ensureLoaded(Minecraft minecraft) {
        if (luts == null && lutLoad == null && !lutFailed) {
            lutLoad = CompletableFuture.supplyAsync(() -> {
                SkyLut[] loaded = new SkyLut[SkyLut.VARIANTS.length];
                for (int i = 0; i < loaded.length; i++) {
                    try {
                        loaded[i] = SkyLut.load(minecraft.getResourceManager(), SkyLut.VARIANTS[i]);
                    } catch (IOException exception) {
                        if (i == 0) {
                            throw new CompletionException(exception);
                        }
                        ModInfo.LOGGER.warn("No {} sky table, weather blending falls back to clear",
                                SkyLut.VARIANTS[i], exception);
                        loaded[i] = loaded[0];
                    }
                }
                return loaded;
            }, Util.backgroundExecutor());
        }

        if (luts == null && lutLoad != null && lutLoad.isDone()) {
            try {
                luts = lutLoad.join();
                SkyDomeRenderer renderer = new SkyDomeRenderer(luts);
                renderer.setShaders(domeShader, quadShader);
            } catch (CompletionException exception) {
                lutFailed = true;
                ModInfo.LOGGER.error("Atmospheric sky disabled: could not load the baked LUT",
                        exception.getCause());
            }
        }
    }
}
