package com.carilt01.trueatmosphere.client;

import com.carilt01.trueatmosphere.AtmosphereSettings;
import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.SkyMode;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

public final class SkyBench {
    private static final String ENV = System.getenv("ATMO_BENCH");

    private static final SkyMode[] ORDER = {
            SkyMode.DOME, SkyMode.QUAD, SkyMode.VANILLA,
            SkyMode.QUAD, SkyMode.VANILLA, SkyMode.DOME,
            SkyMode.VANILLA, SkyMode.DOME, SkyMode.QUAD,
            SkyMode.DOME, SkyMode.QUAD, SkyMode.VANILLA
    };
    private static final int TICKS_PER_SECOND = 20;
    private static final int WARMUP_TICKS = 300;
    private static final int SETTLE_TICKS = 40;
    private static final int SHUTDOWN_TICKS = 40;
    private static final int FRAMERATE_CAP = 260;

    private static final double SKY_HEIGHT = 170.0;
    private static final float YAW = 90.0F;
    private static final float PITCH = 0.0F;

    private static final double[] MEAN_FPS = new double[ORDER.length];
    private static final int[] WORST_FPS = new int[ORDER.length];
    private static final double[] SKY_MS = new double[ORDER.length];
    private static final double[] STAGE_MS = new double[ORDER.length];
    private static final double[] UPLOAD_MS = new double[ORDER.length];
    private static final double[] UPLOADS = new double[ORDER.length];

    private static boolean ready;
    private static boolean prepared;
    private static int windowTicks = 200;
    private static int index;
    private static int ticks;
    private static int warmup = WARMUP_TICKS;
    private static int shutdown = -1;

    private static long fpsSum;
    private static int fpsCount;
    private static int fpsWorst;
    private static long windowFrames;
    private static long windowSkyNanos;
    private static long windowUploadNanos;
    private static long windowUploads;

    private static int stageQuery = -1;
    private static boolean stageActive;
    private static boolean stageRunning;
    private static long stageNanos;
    private static long stageSamples;

    private SkyBench() {
    }

    public static boolean enabled() {
        return ENV != null && !ENV.isBlank();
    }

    public static void beginSkyStage() {
        if (!enabled()) {
            return;
        }
        if (stageQuery < 0) {
            stageQuery = GL15.glGenQueries();
        }
        if (stageRunning) {
            if (GL15.glGetQueryObjecti(stageQuery, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
            stageNanos += GL15.glGetQueryObjecti(stageQuery, GL15.GL_QUERY_RESULT);
            stageSamples++;
            stageRunning = false;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, stageQuery);
        stageActive = true;
    }

    public static void endSkyStage() {
        if (!stageActive) {
            return;
        }
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        stageActive = false;
        stageRunning = true;
    }

    public static void tick(Minecraft minecraft) {
        if (ENV == null || ENV.isBlank()) {
            return;
        }
        if (!ready) {
            windowTicks = Math.max(1, seconds(ENV)) * TICKS_PER_SECOND;
            ready = true;
            ModInfo.LOGGER.info("Sky benchmark armed: {} windows of {}s across {} modes",
                    ORDER.length, windowTicks / TICKS_PER_SECOND, SkyMode.values().length);
        }

        if (shutdown >= 0) {
            if (--shutdown <= 0) {
                ModInfo.LOGGER.info("Sky benchmark finished, stopping the client");
                minecraft.stop();
            }
            return;
        }

        if (index >= ORDER.length) {
            summarise();
            shutdown = SHUTDOWN_TICKS;
            return;
        }

        if (minecraft.level == null || minecraft.player == null) {
            ticks = 0;
            return;
        }

        if (!prepared) {
            prepare(minecraft);
            return;
        }

        if (warmup > 0) {
            warmup--;
            if (warmup == 0) {
                ModInfo.LOGGER.info("Sky benchmark warmup done");
            }
            return;
        }

        if (ticks == 0) {
            startWindow(minecraft);
            ticks = 1;
            return;
        }

        if (ticks < SETTLE_TICKS) {

            ticks++;
            return;
        }

        if (ticks == SETTLE_TICKS) {
            resetCounters();
        }

        sample(minecraft);

        if (++ticks < SETTLE_TICKS + windowTicks) {
            return;
        }

        finishWindow(minecraft);
    }

    private static void startWindow(Minecraft minecraft) {
        SkyMode mode = ORDER[index];
        AtmosphereSettings.skyMode = mode;

        serverCommand(minecraft, "time set 6000");
        if (SkyDomeRenderer.mode() != mode) {
            ModInfo.LOGGER.warn("Sky benchmark cannot switch to {}: a launch property pins {}", mode,
                    SkyDomeRenderer.mode());
        }
        ModInfo.LOGGER.info("Sky benchmark window {}/{}: {} for {}s", index + 1, ORDER.length, mode,
                windowTicks / TICKS_PER_SECOND);
    }

    private static void resetCounters() {
        fpsSum = 0L;
        fpsCount = 0;
        fpsWorst = Integer.MAX_VALUE;
        windowFrames = 0L;
        windowSkyNanos = 0L;
        stageNanos = 0L;
        stageSamples = 0L;
        windowUploadNanos = 0L;
        windowUploads = 0L;

        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer != null) {
            renderer.resetTimings();
        }
    }

    private static void sample(Minecraft minecraft) {
        int fps = minecraft.getFps();
        fpsSum += fps;
        fpsCount++;
        fpsWorst = Math.min(fpsWorst, fps);

        SkyDomeRenderer renderer = SkyDomeRenderer.instance;
        if (renderer != null) {
            windowFrames = renderer.frames();
            windowSkyNanos = renderer.skyNanos();
            windowUploadNanos = renderer.uploadNanos();
            windowUploads = renderer.uploadCount();
        }
    }

    private static void finishWindow(Minecraft minecraft) {
        double meanFps = fpsCount == 0 ? 0.0 : (double) fpsSum / fpsCount;
        double skyMs = windowFrames == 0 ? 0.0 : windowSkyNanos / 1.0e6 / windowFrames;
        double stageMs = stageSamples == 0 ? 0.0 : stageNanos / 1.0e6 / stageSamples;
        double uploadMs = windowUploads == 0 ? 0.0 : windowUploadNanos / 1.0e6 / windowUploads;

        MEAN_FPS[index] = meanFps;
        WORST_FPS[index] = fpsWorst == Integer.MAX_VALUE ? 0 : fpsWorst;
        SKY_MS[index] = skyMs;
        STAGE_MS[index] = stageMs;
        UPLOAD_MS[index] = uploadMs;
        UPLOADS[index] = windowUploads;

        ModInfo.LOGGER.info("BENCH {} window {}: mean {} fps, worst second {} fps, sky cpu {} ms/frame,"
                        + " whole sky stage {} ms, {} uploads at {} ms each"
                        + " ({} frames)",
                ORDER[index], index + 1, String.format("%.1f", meanFps), WORST_FPS[index],
                String.format("%.3f", skyMs), String.format("%.3f", stageMs),
                windowUploads, String.format("%.2f", uploadMs), windowFrames);

        index++;
        ticks = 0;
    }

    @SuppressWarnings("unchecked")
    private static void summarise() {
        java.util.List<Double>[] ratios = new java.util.ArrayList[SkyMode.values().length];
        for (int i = 0; i < ratios.length; i++) {
            ratios[i] = new java.util.ArrayList<>();
        }

        for (int round = 0; round + 2 < ORDER.length; round += 3) {
            double mean = (MEAN_FPS[round] + MEAN_FPS[round + 1] + MEAN_FPS[round + 2]) / 3.0;
            if (mean <= 0.0) {
                continue;
            }
            for (int i = round; i < round + 3; i++) {
                ratios[ORDER[i].ordinal()].add(MEAN_FPS[i] / mean);
            }
        }

        double[] relativeToVanilla = new double[SkyMode.values().length];
        double vanilla = ratios[SkyMode.VANILLA.ordinal()].isEmpty()
                ? 0.0
                : median(ratios[SkyMode.VANILLA.ordinal()]);

        for (SkyMode mode : SkyMode.values()) {
            if (count(mode) == 0) {
                continue;
            }
            double[] fps = median(MEAN_FPS, mode);
            double ratio = ratios[mode.ordinal()].isEmpty() ? 0.0 : median(ratios[mode.ordinal()]);
            relativeToVanilla[mode.ordinal()] = vanilla > 0.0 ? (ratio / vanilla - 1.0) * 100.0 : 0.0;
            ModInfo.LOGGER.info("BENCH summary {} ({} windows): fps median {}, range {}..{};"
                            + " whole sky stage {} ms; sky cpu {} ms per frame;"
                            + " {} ms per step upload; {} vs vanilla's frame rate",
                    mode, count(mode), fmt1(fps[0]), fmt1(fps[1]), fmt1(fps[2]),
                    fmt3(median(STAGE_MS, mode)[0]),
                    fmt3(median(SKY_MS, mode)[0]),
                    fmt2(median(UPLOAD_MS, mode)[0]), relative(relativeToVanilla[mode.ordinal()]));
        }
    }

    private static String relative(double percent) {
        return String.format("%+.1f%%", percent);
    }

    private static double median(java.util.List<Double> values) {
        double[] picked = new double[values.size()];
        for (int i = 0; i < picked.length; i++) {
            picked[i] = values.get(i);
        }
        java.util.Arrays.sort(picked);
        int n = picked.length;
        return n % 2 == 1 ? picked[n / 2] : (picked[n / 2 - 1] + picked[n / 2]) / 2.0;
    }

    private static double[] median(double[] values, SkyMode mode) {
        double[] picked = new double[count(mode)];
        int at = 0;
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == mode) {
                picked[at++] = values[i];
            }
        }
        if (picked.length == 0) {
            return new double[] {0.0, 0.0, 0.0};
        }
        java.util.Arrays.sort(picked);
        double low = picked[0];
        double high = picked[picked.length - 1];
        double middle = picked.length % 2 == 1
                ? picked[picked.length / 2]
                : (picked[picked.length / 2 - 1] + picked[picked.length / 2]) / 2.0;
        return new double[] {middle, low, high};
    }

    private static int count(SkyMode mode) {
        int n = 0;
        for (SkyMode entry : ORDER) {
            if (entry == mode) {
                n++;
            }
        }
        return n;
    }

    private static String fmt1(double value) {
        return String.format("%.1f", value);
    }

    private static String fmt2(double value) {
        return String.format("%.2f", value);
    }

    private static String fmt3(double value) {
        return String.format("%.3f", value);
    }

    private static void prepare(Minecraft minecraft) {
        minecraft.options.enableVsync().set(false);
        minecraft.options.framerateLimit().set(FRAMERATE_CAP);
        minecraft.options.hideGui = true;

        serverCommand(minecraft, "gamerule doDaylightCycle true");
        serverCommand(minecraft, "gamerule doWeatherCycle false");
        serverCommand(minecraft, "weather clear 1000000");
        serverCommand(minecraft, "time set 6000");
        serverCommand(minecraft, "gamemode spectator " + playerName(minecraft));
        serverCommand(minecraft, "tp " + playerName(minecraft) + " "
                + minecraft.player.getX() + " " + SKY_HEIGHT + " " + minecraft.player.getZ());

        minecraft.player.setYRot(YAW);
        minecraft.player.setYHeadRot(YAW);
        minecraft.player.setYBodyRot(YAW);
        minecraft.player.setXRot(PITCH);
        minecraft.player.xRotO = PITCH;
        minecraft.player.yRotO = YAW;

        prepared = true;
        ticks = 0;
        ModInfo.LOGGER.info("Sky benchmark prepared: {}x{}, y={}, yaw={}, pitch={}, vsync off, cap {}",
                minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(),
                SKY_HEIGHT, YAW, PITCH, FRAMERATE_CAP);
    }

    private static int seconds(String spec) {
        try {
            return Math.max(1, Integer.parseInt(spec.trim()));
        } catch (NumberFormatException e) {
            ModInfo.LOGGER.warn("Invalid ATMO_BENCH '{}', using 10s windows", spec);
            return 10;
        }
    }

    private static String playerName(Minecraft minecraft) {
        return minecraft.player == null ? "Dev" : minecraft.player.getGameProfile().getName();
    }

    private static void serverCommand(Minecraft minecraft, String command) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            ModInfo.LOGGER.warn("No integrated server, cannot run '{}'", command);
            return;
        }
        server.execute(() -> {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, command);
        });
    }
}
