package com.carilt01.trueatmosphere.client;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.carilt01.trueatmosphere.TrueAtmosphereClient;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.joml.Vector3f;
import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.material.FogType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

import com.carilt01.trueatmosphere.TrueAtmosphere;

public final class AtmosphereSkyClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SKY_DOME_SHADER = ResourceLocation.fromNamespaceAndPath(TrueAtmosphere.MODID, "sky_dome");
    private static final ResourceLocation SKY_QUAD_SHADER = ResourceLocation.fromNamespaceAndPath(TrueAtmosphere.MODID, "sky_quad");

    private static ShaderInstance domeShader;
    private static ShaderInstance quadShader;
    private static SkyLut[] luts;
    private static SkyDomeRenderer renderer;
    private static CompletableFuture<SkyLut[]> lutLoad;
    private static boolean lutFailed;

    private AtmosphereSkyClient() {
    }

    public static void init(ModContainer container) {

        TrueAtmosphere.LOGGER.info("Initializing true atmosphere!");

        IEventBus bus = container.getEventBus();
        bus.addListener(AtmosphereSkyClient::onRegisterShaders);
        bus.addListener(AtmosphereSkyClient::onRegisterDimensionEffects);

        NeoForge.EVENT_BUS.addListener(AtmosphereSkyClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(AtmosphereSkyClient::onComputeFogColor);
        NeoForge.EVENT_BUS.addListener(AtmosphereSkyClient::onAfterSky);
    }

    static SkyDomeRenderer renderer() {
        return renderer;
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {

        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), SKY_DOME_SHADER, DefaultVertexFormat.POSITION_COLOR),
                    loaded -> {
                        domeShader = loaded;
                        pushShaders();
                    });
        } catch (IOException e) {
            LOGGER.error("Could not load the atmospheric dome shader", e);
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), SKY_QUAD_SHADER, DefaultVertexFormat.POSITION),
                    loaded -> {
                        quadShader = loaded;
                        pushShaders();
                    });
        } catch (IOException e) {
            LOGGER.error("Could not load the atmospheric quad shader", e);
        }
    }

    private static void pushShaders() {
        if (renderer != null) {
            renderer.setShaders(domeShader, quadShader);
        }
    }

    private static void onRegisterDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(BuiltinDimensionTypes.OVERWORLD_EFFECTS, new AtmosphereEffects());
        LOGGER.info("Registered atmospheric sky effects for minecraft:overworld");
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ensureLoaded(minecraft);
        SkyBench.tick(minecraft);
        SkySelfTest.tick(minecraft);
    }

    private static void ensureLoaded(Minecraft minecraft) {
        if (luts == null && lutLoad == null && !lutFailed) {
            lutLoad = CompletableFuture.supplyAsync(() -> {
                SkyLut[] loaded = new SkyLut[SkyLut.VARIANTS.length];
                for (int i = 0; i < loaded.length; i++) {
                    try {
                        loaded[i] = SkyLut.load(minecraft.getResourceManager(), SkyLut.VARIANTS[i]);
                    } catch (IOException e) {
                        if (i == 0) {
                            throw new CompletionException(e);
                        }
                        LOGGER.warn("No {} sky table, weather blending falls back to clear", SkyLut.VARIANTS[i], e);
                        loaded[i] = loaded[0];
                    }
                }
                for (int i = 1; i < loaded.length; i++) {
                    if (loaded[i].stepFloats() != loaded[0].stepFloats()) {
                        LOGGER.warn("{} sky table is {}x{}, not {}x{}; blending falls back to clear",
                                SkyLut.VARIANTS[i], loaded[i].azimuthSamples, loaded[i].altitudeSamples,
                                loaded[0].azimuthSamples, loaded[0].altitudeSamples);
                        loaded[i] = loaded[0];
                    }
                }
                return loaded;
            }, Util.backgroundExecutor());
        }

        if (luts == null && lutLoad != null && lutLoad.isDone()) {
            try {
                luts = lutLoad.join();
                renderer = new SkyDomeRenderer(luts);
                renderer.setShaders(domeShader, quadShader);
            } catch (CompletionException e) {
                lutFailed = true;
                LOGGER.error("Atmospheric sky disabled: could not load the baked LUT", e.getCause());
            }
        }
    }

    private static void onAfterSky(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            SkyBench.endSkyStage();
        }
    }

    private static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        SkyDomeRenderer sky = renderer;
        if (level == null || sky == null || !sky.isReady()) {
            return;
        }
        if (!(level.effects() instanceof AtmosphereEffects)) {
            return;
        }
        if (event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vector3f look = event.getCamera().getLookVector();
        float[] color = new float[3];
        if (!sky.horizonColour(level, (float) event.getPartialTick(), look.x, look.z, color)) {
            return;
        }

        event.setRed(color[0]);
        event.setGreen(color[1]);
        event.setBlue(color[2]);
    }
}
