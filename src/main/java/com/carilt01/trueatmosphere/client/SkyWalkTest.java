package com.carilt01.trueatmosphere.client;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;


public final class SkyWalkTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FRAMES = System.getenv("ATMO_SELFTEST_WALK");
    private static final String TAG = System.getenv("ATMO_SELFTEST_TAG");

    private static final int PLATFORM_RADIUS = 24;
    private static final int WARMUP_TICKS = 140;
    private static final int FLUSH_TICKS = 60;
    private static final double HEIGHT = 250.0;
    private static final float YAW = 270.0F;
    private static final float PITCH = 0.0F;
    private static final double WALK_SPEED = 0.2;
    private static final int LEG_TICKS = 6;

    private static int frames;
    private static int index;
    private static int warmup;
    private static int leg;
    private static int direction = 1;
    private static int shutdown = -1;
    private static boolean prepared;
    private static double startX;
    private static double startZ;

    private SkyWalkTest() {
    }

    public static void tick(Minecraft minecraft) {
        if (FRAMES == null || FRAMES.isBlank()) {
            return;
        }
        if (frames == 0) {
            try {
                frames = Integer.parseInt(FRAMES.trim());
            } catch (NumberFormatException exception) {
                LOGGER.warn("Ignoring ATMO_SELFTEST_WALK: '{}' is not a number", FRAMES);
                return;
            }
            if (frames <= 0) {
                return;
            }
            LOGGER.info("Sky walk test armed for {} frames", frames);
        }

        if (shutdown >= 0) {
            if (--shutdown <= 0) {
                LOGGER.info("Sky walk test finished, stopping the client");
                minecraft.stop();
            }
            return;
        }

        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        if (!prepared) {
            prepare(minecraft);
            return;
        }

        if (warmup > 0) {
            aim(minecraft);
            if (SkyDomeRenderer.instance == null || !SkyDomeRenderer.instance.isReady()) {
                warmup = WARMUP_TICKS;
            } else {
                warmup--;
            }
            return;
        }

        if (index >= frames) {
            minecraft.player.setDeltaMovement(0.0, minecraft.player.getDeltaMovement().y, 0.0);
            LOGGER.info("walk done: {} frames, x {} to {}", index, startX, minecraft.player.getX());
            shutdown = FLUSH_TICKS;
            return;
        }

        aim(minecraft);
        minecraft.player.getAbilities().flying = false;
        minecraft.player.onUpdateAbilities();
        if (index > 0) {
            minecraft.player.setDeltaMovement(-WALK_SPEED * direction,
                    minecraft.player.getDeltaMovement().y, 0.0);
        }
        if (++leg >= LEG_TICKS) {
            leg = 0;
            direction = -direction;
        }

        BlockPos below = BlockPos.containing(minecraft.player.getX(), minecraft.player.getY() - 0.1,
                minecraft.player.getZ());
        //? if mc >= 1.21.5 {
        LOGGER.info("walk {}: x {} y {} z {} phase {} speed {} onGround {} below {}",
                index, minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                minecraft.player.walkAnimation.position(), minecraft.player.walkAnimation.speed(),
                minecraft.player.onGround(), level.getBlockState(below));
        //?} else {
        LOGGER.info("walk {}: x {} y {} z {} phase {} bob {} bobView {} onGround {} below {}",
                index, minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                minecraft.player.walkDist, minecraft.player.bob, minecraft.options.bobView().get(),
                minecraft.player.onGround(), level.getBlockState(below));
        //?}

        save(minecraft, name(index));
        index++;
    }

    private static void prepare(Minecraft minecraft) {
        minecraft.options.bobView().set(true);
        minecraft.options.cloudStatus().set(CloudStatus.OFF);
        minecraft.options.pauseOnLostFocus = false;
        hideHud(minecraft);

        startX = minecraft.player.getX();
        startZ = minecraft.player.getZ();

        serverCommand(minecraft, "gamerule doDaylightCycle false");
        serverCommand(minecraft, "gamerule doWeatherCycle false");
        serverCommand(minecraft, "weather clear 1000000");
        serverCommand(minecraft, "time set 1000");
        serverCommand(minecraft, "gamemode survival " + playerName(minecraft));
        serverCommand(minecraft, "tp " + playerName(minecraft) + " " + startX + " " + HEIGHT + " " + startZ);
        serverCommand(minecraft, String.format(java.util.Locale.ROOT,
                "fill %.0f %.0f %.0f %.0f %.0f %.0f minecraft:barrier",
                startX - PLATFORM_RADIUS, HEIGHT - 1.0, startZ - PLATFORM_RADIUS,
                startX + PLATFORM_RADIUS, HEIGHT - 1.0, startZ + PLATFORM_RADIUS));
        serverCommand(minecraft, String.format(java.util.Locale.ROOT,
                "fill %.0f %.0f %.0f %.0f %.0f %.0f minecraft:glowstone",
                startX + 10.0, HEIGHT + 1.0, startZ,
                startX + 10.0, HEIGHT + 3.0, startZ));

        aim(minecraft);
        prepared = true;
        warmup = WARMUP_TICKS;
        LOGGER.info("Sky walk test platform at {} {} {}, walking yaw {} pitch {}", startX, HEIGHT, startZ, YAW, PITCH);
    }

    private static void aim(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.setYRot(YAW);
        minecraft.player.setYHeadRot(YAW);
        minecraft.player.setYBodyRot(YAW);
        minecraft.player.setXRot(PITCH);
        minecraft.player.xRotO = PITCH;
        minecraft.player.yRotO = YAW;
    }

    private static String name(int frame) {
        String tag = TAG == null || TAG.isBlank() ? "atmo" : TAG;
        return tag + "_walk" + frame + ".png";
    }

    private static void hideHud(Minecraft minecraft) {
        //? if mc >= 26.2 {
        if (!minecraft.gui.hud.isHidden()) {
            minecraft.gui.hud.toggle();
        }
        //?} else {
        minecraft.options.hideGui = true;
        //?}
    }

    private static String playerName(Minecraft minecraft) {
        //? if mc >= 1.21.5 {
        return minecraft.player == null ? "Dev" : minecraft.player.getScoreboardName();
        //?} else {
        return minecraft.player == null ? "Dev" : minecraft.player.getGameProfile().getName();
        //?}
    }

    private static void serverCommand(Minecraft minecraft, String command) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("No integrated server, cannot run '{}'", command);
            return;
        }
        server.execute(() -> {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, command);
        });
    }

    private static void save(Minecraft minecraft, String name) {
        //? if mc >= 26.2 {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
                message -> LOGGER.info("Screenshot: {}", message.getString()));
        //?} else if mc >= 1.21.6 {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), 1,
                message -> LOGGER.info("Screenshot: {}", message.getString()));
        //?} else {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                message -> LOGGER.info("Screenshot: {}", message.getString()));
        //?}
    }
}
