package com.carilt01.trueatmosphere.client;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

public final class SkySelfTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENV = System.getenv("ATMO_SELFTEST");
    private static final String TAG = System.getenv("ATMO_SELFTEST_TAG");
    private static final String WEATHER = System.getenv("ATMO_SELFTEST_WEATHER");

    private static final int WARMUP_TICKS = 140;
    private static final int SETTLE_TICKS = 30;
    private static final int TELEPORT_SETTLE_TICKS = 50;
    private static final int FLUSH_TICKS = 60;

    private static final double SKY_HEIGHT = 170.0;

    private static int[] times;
    private static float[] yaws;
    private static float[] pitches;
    private static double[] heights;

    private static int index;
    private static int ticks;
    private static int settle = SETTLE_TICKS;
    private static int retries;
    private static int shutdown = -1;
    private static boolean ready;
    private static boolean prepared;
    private static double lastHeight = SKY_HEIGHT;
    private static long lastUploads;

    private SkySelfTest() {
    }

    public static void tick(Minecraft minecraft) {
        if (ENV == null || ENV.isBlank()) {
            return;
        }
        if (!ready) {
            if (!parse(ENV)) {
                return;
            }
            ready = true;
            LOGGER.info("Sky self-test armed for {} shots", times.length);
        }

        if (shutdown >= 0) {
            if (--shutdown <= 0) {
                LOGGER.info("Sky self-test finished, stopping the client");
                minecraft.stop();
            }
            return;
        }

        if (index >= times.length) {
            shutdown = FLUSH_TICKS;
            return;
        }

        if (minecraft.level == null || minecraft.player == null) {
            ticks = 0;
            return;
        }

        if (!prepared) {
            minecraft.options.hideGui = true;
            serverCommand(minecraft, "gamerule doDaylightCycle false");
            serverCommand(minecraft, "gamerule doWeatherCycle false");
            serverCommand(minecraft, "weather " + (weather() == null ? "clear" : weather()) + " 1000000");
            serverCommand(minecraft, "gamemode spectator " + playerName(minecraft));
            teleport(minecraft, heights[0]);
            prepared = true;
            ticks = 0;
            return;
        }

        int warmup = weather() == null ? WARMUP_TICKS : WARMUP_TICKS + 220;
        if (index == 0 && ticks < warmup) {
            ticks++;
            return;
        }

        aim(minecraft);

        if (ticks == 0) {
            applyTime(minecraft, times[index]);
        }

        if (++ticks < settle) {
            return;
        }

        if (!timeArrived(minecraft, times[index]) && retries++ < 4) {
            LOGGER.warn("Time did not take (wanted {}, have {}), retrying",
                    times[index], minecraft.level.getDayTime());
            applyTime(minecraft, times[index]);
            ticks = 0;
            settle = SETTLE_TICKS;
            return;
        }

        grab(minecraft);

        int next = index + 1;
        ticks = 0;
        retries = 0;
        index = next;
        settle = SETTLE_TICKS;

        if (index < heights.length && heights[index] != lastHeight) {
            teleport(minecraft, heights[index]);
            settle = TELEPORT_SETTLE_TICKS;
        }
    }

    private static float targetAngle(int dayTime) {
        double d0 = (dayTime / 24000.0 - 0.25) - Math.floor(dayTime / 24000.0 - 0.25);
        double d1 = 0.5 - Math.cos(d0 * Math.PI) / 2.0;
        return (float) (((d0 * 2.0 + d1) / 3.0) * 360.0 + 90.0);
    }

    private static boolean timeArrived(Minecraft minecraft, int dayTime) {
        long actual = minecraft.level.getDayTime();
        long want = Math.floorMod(dayTime, 24000L);
        long have = Math.floorMod(actual, 24000L);
        return Math.abs(have - want) < 200 || Math.abs(have - want) > 23800;
    }

    private static void aim(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        float yaw = yaws[index];
        float pitch = pitches[index];
        minecraft.player.setYRot(yaw);
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.setYBodyRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.xRotO = pitch;
        minecraft.player.yRotO = yaw;
    }

    private static String playerName(Minecraft minecraft) {
        return minecraft.player == null ? "Dev" : minecraft.player.getGameProfile().getName();
    }

    private static void teleport(Minecraft minecraft, double height) {
        if (minecraft.player == null) {
            return;
        }
        lastHeight = height;
        serverCommand(minecraft, "tp " + playerName(minecraft) + " "
                + minecraft.player.getX() + " " + height + " " + minecraft.player.getZ());
    }

    private static void applyTime(Minecraft minecraft, int dayTime) {
        serverCommand(minecraft, "time set " + dayTime);
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

    private static void grab(Minecraft minecraft) {
        String name = (TAG == null || TAG.isBlank() ? "atmo" : TAG)
                + "_" + times[index]
                + "_yaw" + Math.round(yaws[index])
                + "_p" + Math.round(pitches[index])
                + "_y" + Math.round(heights[index]) + ".png";
        float angle = SkyDomeRenderer.sunAngle(minecraft.level, 1.0F);
        LOGGER.info("shot {}: requested {} actual {} -> sun angle {} (elevation {} deg)",
                index, times[index], minecraft.level.getDayTime(), angle,
                Math.round(Math.toDegrees(Math.asin(Math.sin(Math.toRadians(angle))))));
        LOGGER.info("shot {}: expected celestial angle {}", index, targetAngle(times[index]));
        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        long uploads = renderer == null ? -1L : renderer.uploads();
        LOGGER.info("shot {}: {}", index, uploads < 0
                ? "no renderer"
                : (uploads - lastUploads) + " table uploads since the last shot (" + uploads + " total)");
        LOGGER.info("shot {}: rain {} thunder {}", index,
                minecraft.level.getRainLevel(1.0F), minecraft.level.getThunderLevel(1.0F));
        lastUploads = uploads;
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                message -> LOGGER.info("Screenshot: {}", message.getString()));
    }

    private static String weather() {
        if (WEATHER == null || WEATHER.isBlank() || "clear".equalsIgnoreCase(WEATHER)) {
            return null;
        }
        return WEATHER.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean parse(String spec) {
        String[] parts = spec.split(",");
        int count = 0;
        int[] t = new int[parts.length];
        float[] y = new float[parts.length];
        float[] p = new float[parts.length];
        double[] h = new double[parts.length];

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] pieces = trimmed.split("[@:]");
            try {
                t[count] = Integer.parseInt(pieces[0].trim());
                y[count] = pieces.length > 1 ? Float.parseFloat(pieces[1].trim()) : 90.0F;
                p[count] = pieces.length > 2 ? Float.parseFloat(pieces[2].trim()) : 6.0F;
                h[count] = pieces.length > 3 ? Double.parseDouble(pieces[3].trim()) : SKY_HEIGHT;
            } catch (NumberFormatException e) {
                LOGGER.error("Invalid ATMO_SELFTEST entry '{}'", trimmed);
                return false;
            }
            count++;
        }

        if (count == 0) {
            return false;
        }

        times = java.util.Arrays.copyOf(t, count);
        yaws = java.util.Arrays.copyOf(y, count);
        pitches = java.util.Arrays.copyOf(p, count);
        heights = java.util.Arrays.copyOf(h, count);
        lastHeight = heights[0];
        return true;
    }
}
