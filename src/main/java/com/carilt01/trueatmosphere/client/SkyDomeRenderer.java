package com.carilt01.trueatmosphere.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import com.carilt01.trueatmosphere.Config;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class SkyDomeRenderer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TEXTURE_UNIT = 33984;

    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

    private static final float SUN_QUAD = 15.0F;
    private static final float MOON_QUAD = 20.0F;

    public static float displayGain = Config.DISPLAY_EXPOSURE.get().floatValue();

    private static final float MOON_GAIN = Config.MOON_GAIN.get().floatValue();
    private static final float[] MOON_COLOUR = {1.0F, 0.97F, 0.88F};

    private static final float MOON_GLARE_KNEE = Config.MOON_GLARE_KNEE.get().floatValue();
    private static final float MOON_SATURATION = Config.MOON_SATURATION.get().floatValue();

    private final SkyLut[] luts;
    private final SkyLut base;
    private final int[] textures = new int[2];
    private final int[] moonTextures = new int[2];
    private final FloatBuffer upload;

    private final float[] weights = {1.0F, 0.0F, 0.0F};
    private final float[] uploadedWeights = {-1.0F, -1.0F, -1.0F};

    private ShaderInstance shader;
    private VertexBuffer domeQuad;
    private VertexBuffer starBuffer;
    private boolean gpuReady;
    private boolean closed;

    private int slot;
    private int currentStep = -1;
    private float blend;
    private final float[] scratch = new float[3];
    private final float[] blended = new float[3];
    private final float[] moonBlended = new float[3];
    private long uploads;

    public SkyDomeRenderer(SkyLut[] luts) {
        this.luts = luts;
        this.base = luts[0];
        this.upload = ByteBuffer.allocateDirect(this.base.stepFloats() * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    public void setShader(ShaderInstance shader) {
        this.shader = shader;
    }

    public boolean isReady() {
        return this.shader != null && !this.closed;
    }

    public long uploads() {
        return this.uploads;
    }

    public static float sunAngle(ClientLevel level, float partialTick) {
        float angle = level.getTimeOfDay(partialTick) * 360.0F + 90.0F;
        angle %= 360.0F;
        return angle < 0.0F ? angle + 360.0F : angle;
    }

    private void ensureGpu() {
        if (this.gpuReady) {
            return;
        }

        int previous = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(TEXTURE_UNIT);
        try {
            for (int[] target : new int[][] {this.textures, this.moonTextures}) {
                for (int i = 0; i < 2; i++) {
                    int id = GlStateManager._genTexture();
                    target[i] = id;
                    GlStateManager._bindTexture(id);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                    GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB32F,
                            this.base.azimuthSamples, this.base.altitudeSamples, 0,
                            GL11.GL_RGB, GL11.GL_FLOAT, (java.nio.IntBuffer) null);
                }
            }
        } finally {
            GlStateManager._bindTexture(0);
            GlStateManager._activeTexture(previous);
        }

        this.domeQuad = new VertexBuffer(VertexBuffer.Usage.STATIC);
        this.domeQuad.bind();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(-1.0F, 1.0F, 0.0F);
        this.domeQuad.upload(builder.buildOrThrow());
        VertexBuffer.unbind();

        this.starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        this.starBuffer.bind();
        this.starBuffer.upload(buildStars());
        VertexBuffer.unbind();

        this.currentStep = -1;
        Arrays.fill(this.uploadedWeights, -1.0F);

        this.gpuReady = true;
        LOGGER.info("Sky dome GPU resources ready: 4 x {} KiB tables (sun + moon slots), full LUT {} KiB stays on the CPU",
                this.base.stepFloats() * 4 / 1024, this.base.display().length * 4 / 1024);
    }

    private void updateWeather(ClientLevel level, float partialTick) {
        float rainLevel = Mth.clamp(level.getRainLevel(partialTick), 0.0F, 1.0F);
        float thunderLevel = Math.min(Mth.clamp(level.getThunderLevel(partialTick), 0.0F, 1.0F), rainLevel);
        this.weights[0] = 1.0F - rainLevel;
        this.weights[1] = rainLevel * (1.0F - thunderLevel);
        this.weights[2] = thunderLevel;
    }

    private boolean weightsChanged() {
        for (int i = 0; i < this.weights.length; i++) {
            if (Math.abs(this.weights[i] - this.uploadedWeights[i]) > 2.0E-3F) {
                return true;
            }
        }
        return false;
    }

    private void uploadTable(int target, int step, int[] slots) {
        int floats = this.base.stepFloats();
        int offset = this.base.stepOffset(step);
        this.upload.clear();
        if (this.weights[0] == 1.0F) {
            this.upload.put(this.luts[0].display(), offset, floats);
        } else {
            float[] clear = this.luts[0].display();
            float[] rain = this.luts[1].display();
            float[] thunder = this.luts[2].display();
            float wc = this.weights[0];
            float wr = this.weights[1];
            float wt = this.weights[2];
            for (int i = 0; i < floats; i++) {
                this.upload.put(wc * clear[offset + i] + wr * rain[offset + i] + wt * thunder[offset + i]);
            }
        }
        this.upload.flip();

        int previousUnit = GlStateManager._getActiveTexture();
        int rowLength = GlStateManager._getInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipPixels = GlStateManager._getInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int skipRows = GlStateManager._getInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int alignment = GlStateManager._getInteger(GL11.GL_UNPACK_ALIGNMENT);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._activeTexture(TEXTURE_UNIT);
        GlStateManager._bindTexture(slots[target]);
        try {
            GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                    this.base.azimuthSamples, this.base.altitudeSamples,
                    GL11.GL_RGB, GL11.GL_FLOAT, MemoryUtil.memAddress(this.upload));
        } finally {
            GlStateManager._bindTexture(0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GlStateManager._activeTexture(previousUnit);
        }

        this.uploads++;
    }

    private void uploadPair(int target, int step) {
        uploadTable(target, step, this.textures);
        uploadTable(target, this.base.moonStep(step), this.moonTextures);
    }

    private void updateTables(float angle) {
        int step = (int) Math.floor(angle) % this.base.sunSteps;
        int next = (step + 1) % this.base.sunSteps;
        this.blend = angle - (float) Math.floor(angle);
        boolean weather = weightsChanged();

        if (step != this.currentStep || weather) {
            if (this.currentStep < 0) {
                uploadPair(0, step);
                uploadPair(1, next);
                this.slot = 0;
            } else if (!weather && step == (this.currentStep + 1) % this.base.sunSteps) {
                int stale = this.slot;
                this.slot = 1 - this.slot;
                uploadPair(stale, next);
            } else {
                uploadPair(0, step);
                uploadPair(1, next);
                this.slot = 0;
            }
            System.arraycopy(this.weights, 0, this.uploadedWeights, 0, this.weights.length);
            this.currentStep = step;
        }
    }

    public void render(ClientLevel level, Matrix4f frustum, Matrix4f projection, float partialTick, Runnable skyFogSetup) {

        ensureGpu();

        float timeOfDay = level.getTimeOfDay(partialTick);
        float angle = sunAngle(level, partialTick);
        updateWeather(level, partialTick);
        updateTables(angle);

        ShaderInstance sky = this.shader;
        Matrix4f inverseViewProjection = new Matrix4f(projection).mul(frustum).invert();
        sky.safeGetUniform("u_invViewProj").set(inverseViewProjection);
        sky.safeGetUniform("u_t").set(this.blend);
        sky.safeGetUniform("u_gain").set(displayGain);
        float sunY = Mth.sin((float) Math.toRadians(this.upperEdgeAngle(angle)));
        sky.safeGetUniform("u_night").set(nightFactor(sunY));
        sky.safeGetUniform("u_moonGainR").set(MOON_GAIN * MOON_COLOUR[0]);
        sky.safeGetUniform("u_moonGainG").set(MOON_GAIN * MOON_COLOUR[1]);
        sky.safeGetUniform("u_moonGainB").set(MOON_GAIN * MOON_COLOUR[2]);
        sky.safeGetUniform("u_azSamples").set(this.base.azimuthSamples);
        sky.safeGetUniform("u_altSamples").set(this.base.altitudeSamples);
        sky.setSampler("TableCurrent", this.textures[this.slot]);
        sky.setSampler("TableNext", this.textures[1 - this.slot]);
        sky.setSampler("TableMoonCurrent", this.moonTextures[this.slot]);
        sky.setSampler("TableMoonNext", this.moonTextures[1 - this.slot]);

        skyFogSetup.run();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        this.domeQuad.bind();
        this.domeQuad.drawWithShader(frustum, projection, sky);
        VertexBuffer.unbind();

        RenderSystem.enableCull();

        PoseStack pose = new PoseStack();
        pose.mulPose(frustum);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        pose.pushPose();
        float fade = 1.0F - level.getRainLevel(partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, fade);
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.XP.rotationDegrees(timeOfDay * 360.0F));
        Matrix4f celestial = pose.last().pose();

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, fade);
        drawSunQuad(celestial);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, fade);
        RenderSystem.setShaderTexture(0, MOON_TEXTURE);
        drawMoonQuad(celestial, 0);

        float starBrightness = level.getStarBrightness(partialTick) * fade;
        if (starBrightness > 0.0F) {
            RenderSystem.setShaderColor(starBrightness, starBrightness, starBrightness, starBrightness);
            FogRenderer.setupNoFog();
            this.starBuffer.bind();
            this.starBuffer.drawWithShader(celestial, projection, GameRenderer.getPositionShader());
            VertexBuffer.unbind();
            skyFogSetup.run();
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        pose.popPose();
        RenderSystem.depthMask(true);
    }

    private static void drawSunQuad(Matrix4f pose) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(pose, -SUN_QUAD, 100.0F, -SUN_QUAD).setUv(0.0F, 0.0F);
        builder.addVertex(pose, SUN_QUAD, 100.0F, -SUN_QUAD).setUv(1.0F, 0.0F);
        builder.addVertex(pose, SUN_QUAD, 100.0F, SUN_QUAD).setUv(1.0F, 1.0F);
        builder.addVertex(pose, -SUN_QUAD, 100.0F, SUN_QUAD).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void drawMoonQuad(Matrix4f pose, int moonPhase) {
        int u = moonPhase % 4;
        int v = moonPhase / 4 % 2;
        float u0 = (float) (u + 0) / 4.0F;
        float v0 = (float) (v + 0) / 2.0F;
        float u1 = (float) (u + 1) / 4.0F;
        float v1 = (float) (v + 1) / 2.0F;

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(pose, -MOON_QUAD, -100.0F, MOON_QUAD).setUv(u1, v1);
        builder.addVertex(pose, MOON_QUAD, -100.0F, MOON_QUAD).setUv(u0, v1);
        builder.addVertex(pose, MOON_QUAD, -100.0F, -MOON_QUAD).setUv(u0, v0);
        builder.addVertex(pose, -MOON_QUAD, -100.0F, -MOON_QUAD).setUv(u1, v0);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static MeshData buildStars() {
        RandomSource random = RandomSource.create(10842L);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

        for (int i = 0; i < 1500; i++) {
            float x = random.nextFloat() * 2.0F - 1.0F;
            float y = random.nextFloat() * 2.0F - 1.0F;
            float z = random.nextFloat() * 2.0F - 1.0F;
            float size = 0.15F + random.nextFloat() * 0.1F;
            float lengthSq = Mth.lengthSquared(x, y, z);
            if (lengthSq <= 0.010000001F || lengthSq >= 1.0F) {
                continue;
            }
            Vector3f center = new Vector3f(x, y, z).normalize(100.0F);
            float roll = (float) (random.nextDouble() * (float) Math.PI * 2.0);
            Quaternionf rotation = new Quaternionf().rotateTo(new Vector3f(0.0F, 0.0F, -1.0F), center).rotateZ(roll);
            builder.addVertex(center.add(new Vector3f(size, -size, 0.0F).rotate(rotation)));
            builder.addVertex(center.add(new Vector3f(size, size, 0.0F).rotate(rotation)));
            builder.addVertex(center.add(new Vector3f(-size, size, 0.0F).rotate(rotation)));
            builder.addVertex(center.add(new Vector3f(-size, -size, 0.0F).rotate(rotation)));
        }

        return builder.buildOrThrow();
    }

    public boolean horizonColor(ClientLevel level, float partialTick, float dirX, float dirZ, float[] out) {
        if (!this.gpuReady) {
            return false;
        }
        float angle = sunAngle(level, partialTick);
        int step = (int) Math.floor(angle) % this.base.sunSteps;
        int next = (step + 1) % this.base.sunSteps;
        float t = angle - (float) Math.floor(angle);

        float length = Mth.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0E-4F) {
            dirX = 1.0F;
            dirZ = 0.0F;
        } else {
            dirX /= length;
            dirZ /= length;
        }

        float night = nightFactor(Mth.sin((float) Math.toRadians(this.upperEdgeAngle(angle))));
        Arrays.fill(this.blended, 0.0F);
        Arrays.fill(this.moonBlended, 0.0F);
        for (int v = 0; v < this.luts.length; v++) {
            if (this.weights[v] == 0.0F) {
                continue;
            }
            this.luts[v].sample(dirX, 0.02F, dirZ, step, next, t, this.scratch);
            float wr = this.weights[v];
            for (int i = 0; i < 3; i++) {
                this.blended[i] += wr * this.scratch[i];
            }
            if (night > 0.0F) {
                int moon = this.luts[v].moonStep(step);
                int moonNext = this.luts[v].moonStep(next);
                this.luts[v].sample(dirX, 0.02F, dirZ, moon, moonNext, t, this.scratch);
                for (int i = 0; i < 3; i++) {
                    this.moonBlended[i] += wr * MOON_GAIN * MOON_COLOUR[i] * this.scratch[i];
                }
            }
        }
        moonDisplay(this.moonBlended);
        for (int i = 0; i < 3; i++) {
            out[i] = srgbEncode(Mth.clamp((this.blended[i] + night * this.moonBlended[i]) * displayGain, 0.0F, 1.0F));
        }
        return true;
    }

    private static void moonDisplay(float[] colour) {
        float peak = Math.max(colour[0], Math.max(colour[1], colour[2]));
        float scale = 1.0F / (1.0F + peak * MOON_GLARE_KNEE);
        for (int i = 0; i < 3; i++) {
            colour[i] *= scale;
        }
        float luminance = 0.2126F * colour[0] + 0.7152F * colour[1] + 0.0722F * colour[2];
        for (int i = 0; i < 3; i++) {
            colour[i] = luminance + (colour[i] - luminance) * MOON_SATURATION;
        }
    }

    private static float nightFactor(float sunY) {
        return Mth.clamp((0.02F - sunY) / 0.14F, 0.0F, 1.0F);
    }

    private float upperEdgeAngle(float angle) {
        float radius = this.base.sunRadiusDegrees;
        return angle > 90.0F && angle < 270.0F ? angle - radius : angle + radius;
    }

    private static float srgbEncode(float linear) {
        float c = Mth.clamp(linear, 0.0F, 1.0F);
        return c <= 0.0031308F ? 12.92F * c : 1.055F * (float) Math.pow(c, 1.0 / 2.4) - 0.055F;
    }

    @Override
    public void close() {
        this.closed = true;
        for (int[] slots : new int[][] {this.textures, this.moonTextures}) {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != 0) {
                    GlStateManager._deleteTexture(slots[i]);
                    slots[i] = 0;
                }
            }
        }
        if (this.domeQuad != null) {
            this.domeQuad.close();
            this.domeQuad = null;
        }
        if (this.starBuffer != null) {
            this.starBuffer.close();
            this.starBuffer = null;
        }
        this.gpuReady = false;
    }
}
