package com.carilt01.trueatmosphere.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import com.carilt01.trueatmosphere.AtmosphereSettings;
import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.SkyMode;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class SkyDomeRenderer implements AutoCloseable {
    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

    private static final float SUN_QUAD = 15.0F;
    private static final float MOON_QUAD = 20.0F;

    private static final int TEXTURE_UNIT = 33984;

    private static final int[] SRGB8 = srgb8Table();

    private static final SkyMode PROPERTY_MODE = modeProperty();

    public static volatile SkyDomeRenderer instance;

    private final SkyLut[] variants;
    private final SkyLut base;
    private final int rows;
    private final int columns;
    private final int paddedColumns;

    private final VertexBuffer[] slots = new VertexBuffer[2];
    private final int[] slotStep = {-1, -1};
    private final int[] slotVariant = {-1, -1};
    private final int[] textures = new int[2];
    private final FloatBuffer upload;

    private ShaderInstance domeShader;
    private ShaderInstance quadShader;
    private VertexBuffer screenQuad;
    private VertexBuffer starBuffer;
    private VertexBuffer sunBuffer;
    private VertexBuffer moonBuffer;

    private SkyMode gpuMode;
    private boolean closed;
    private long uploads;

    private long skyNanos;
    private long uploadNanos;
    private long uploadCount;
    private long frames;

    private int variant;
    private int currentStep;
    private int nextStep;
    private float blend;
    private int current;
    private int next;

    public SkyDomeRenderer(SkyLut[] luts) {
        this.variants = luts;
        this.base = luts[0];
        this.rows = this.base.altitudeSamples;
        this.columns = this.base.azimuthSamples;
        this.paddedColumns = this.columns + 1;
        this.upload = ByteBuffer.allocateDirect(this.paddedColumns * this.rows * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        instance = this;
    }

    public void setShaders(ShaderInstance dome, ShaderInstance quad) {
        this.domeShader = dome;
        this.quadShader = quad;
    }

    public boolean isReady() {
        if (this.closed) {
            return false;
        }
        SkyMode mode = mode();
        if (mode == SkyMode.VANILLA) {
            return false;
        }
        return mode == SkyMode.DOME ? this.domeShader != null : this.quadShader != null;
    }

    public static SkyMode mode() {
        SkyMode property = PROPERTY_MODE;
        return property != null ? property : AtmosphereSettings.skyMode;
    }

    private static SkyMode modeProperty() {
        String value = System.getProperty("trueatmosphere.sky");
        if (value == null || value.isBlank()) {
            return null;
        }
        return SkyMode.byName(value);
    }

    public long uploads() {
        return this.uploads;
    }

    public long skyNanos() {
        return this.skyNanos;
    }

    public long uploadNanos() {
        return this.uploadNanos;
    }

    public long uploadCount() {
        return this.uploadCount;
    }

    public long frames() {
        return this.frames;
    }

    public void resetTimings() {
        this.skyNanos = 0L;
        this.uploadNanos = 0L;
        this.uploadCount = 0L;
        this.frames = 0L;
    }

    public static float sunAngle(ClientLevel level, float partialTick) {
        float angle = level.getTimeOfDay(partialTick) * 360.0F + 90.0F;
        angle %= 360.0F;
        return angle < 0.0F ? angle + 360.0F : angle;
    }

    private void select(ClientLevel level, float partialTick) {
        float rain = Mth.clamp(level.getRainLevel(partialTick), 0.0F, 1.0F);
        float thunder = Math.min(Mth.clamp(level.getThunderLevel(partialTick), 0.0F, 1.0F), rain);
        float clear = 1.0F - rain;
        float wet = rain * (1.0F - thunder);

        this.variant = thunder >= wet && thunder >= clear ? 2 : (wet >= clear ? 1 : 0);

        float wrapped = sunAngle(level, partialTick) % 360.0F;
        int steps = this.base.sunSteps;
        float floor = (float) Math.floor(wrapped);
        this.currentStep = (int) floor % steps;
        this.nextStep = (this.currentStep + 1) % steps;
        this.blend = wrapped - floor;
    }

    private void ensureGpu(SkyMode mode) {
        if (this.gpuMode == mode) {
            return;
        }
        if (mode == SkyMode.VANILLA) {
            releaseModeResources();
            return;
        }

        releaseModeResources();

        if (mode == SkyMode.DOME) {
            for (int i = 0; i < 2; i++) {
                this.slots[i] = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
        } else {
            int previous = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(TEXTURE_UNIT);
            try {
                for (int i = 0; i < 2; i++) {
                    int id = GlStateManager._genTexture();
                    this.textures[i] = id;
                    GlStateManager._bindTexture(id);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                    GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                    GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F,
                            this.paddedColumns, this.rows, 0,
                            GL11.GL_RGB, GL11.GL_FLOAT, (java.nio.IntBuffer) null);
                }
            } finally {
                GlStateManager._bindTexture(0);
                GlStateManager._activeTexture(previous);
            }

            this.screenQuad = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.screenQuad.bind();
            BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            quad.addVertex(-1.0F, -1.0F, 0.0F);
            quad.addVertex(1.0F, -1.0F, 0.0F);
            quad.addVertex(1.0F, 1.0F, 0.0F);
            quad.addVertex(-1.0F, 1.0F, 0.0F);
            this.screenQuad.upload(quad.buildOrThrow());
            VertexBuffer.unbind();
        }

        this.gpuMode = mode;

        if (this.starBuffer == null) {
            this.starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.starBuffer.bind();
            this.starBuffer.upload(buildStars());
            VertexBuffer.unbind();

            this.sunBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.sunBuffer.bind();
            this.sunBuffer.upload(buildCelestial(SUN_QUAD, 100.0F, 0.0F, 0.0F, 1.0F, 1.0F));
            VertexBuffer.unbind();

            this.moonBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.moonBuffer.bind();
            this.moonBuffer.upload(buildCelestial(MOON_QUAD, -100.0F, 0.0F, 0.0F, 0.25F, 0.5F));
            VertexBuffer.unbind();
        }

        ModInfo.LOGGER.info("Sky GPU ready: {} mode, {}x{} table ({} KiB per step), two slots",
                mode, this.columns, this.rows, this.base.stepFloats() * 4 / 1024);
    }

    private void releaseModeResources() {
        for (int i = 0; i < this.slots.length; i++) {
            if (this.slots[i] != null) {
                this.slots[i].close();
                this.slots[i] = null;
            }
        }
        if (this.screenQuad != null) {
            this.screenQuad.close();
            this.screenQuad = null;
        }
        for (int i = 0; i < this.textures.length; i++) {
            if (this.textures[i] != 0) {
                GlStateManager._deleteTexture(this.textures[i]);
                this.textures[i] = 0;
            }
        }
        Arrays.fill(this.slotStep, -1);
        Arrays.fill(this.slotVariant, -1);
        this.gpuMode = null;
    }

    private int findSlot(int variantIndex, int step) {
        for (int i = 0; i < 2; i++) {
            if (this.slotVariant[i] == variantIndex && this.slotStep[i] == step) {
                return i;
            }
        }
        return -1;
    }

    private int staleSlot(int keepStep) {
        for (int i = 0; i < 2; i++) {
            if (this.slotStep[i] != keepStep) {
                return i;
            }
        }
        return 0;
    }

    private void sync(SkyMode mode) {
        ensureGpu(mode);

        int current = findSlot(this.variant, this.currentStep);
        if (current < 0) {
            current = staleSlot(this.nextStep);
            upload(mode, current, this.variant, this.currentStep);
        }

        int next = findSlot(this.variant, this.nextStep);
        if (next < 0) {
            next = staleSlot(this.currentStep);
            upload(mode, next, this.variant, this.nextStep);
        }

        this.current = current;
        this.next = next;
    }

    private void upload(SkyMode mode, int slot, int variantIndex, int step) {
        long started = System.nanoTime();
        SkyLut lut = this.variants[variantIndex];
        if (mode == SkyMode.DOME) {
            this.slots[slot].bind();
            this.slots[slot].upload(domeMesh(lut, step));
            VertexBuffer.unbind();
        } else {

            float[] table = lut.display();
            int offset = lut.stepOffset(step);
            this.upload.clear();
            for (int row = 0; row < this.rows; row++) {
                int rowStart = offset + row * this.columns * 3;
                for (int column = 0; column < this.paddedColumns; column++) {
                    int i = rowStart + (column == this.columns ? 0 : column * 3);
                    this.upload.put(encode8(table[i]) / 255.0F);
                    this.upload.put(encode8(table[i + 1]) / 255.0F);
                    this.upload.put(encode8(table[i + 2]) / 255.0F);
                }
            }
            this.upload.flip();
            uploadTable(this.textures[slot]);
        }
        this.uploadNanos += System.nanoTime() - started;
        this.uploadCount++;

        this.slotStep[slot] = step;
        this.slotVariant[slot] = variantIndex;
        this.uploads++;
    }

    private void uploadTable(int texture) {
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
        GlStateManager._bindTexture(texture);
        try {
            GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                    this.paddedColumns, this.rows,
                    GL11.GL_RGB, GL11.GL_FLOAT, MemoryUtil.memAddress(this.upload));
        } finally {
            GlStateManager._bindTexture(0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GlStateManager._activeTexture(previousUnit);
        }
    }

    private MeshData domeMesh(SkyLut lut, int step) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR);
        float[] table = lut.display();
        int offset = lut.stepOffset(step);

        for (int row = 0; row < this.rows - 1; row++) {
            float mu0 = 2.0F * row / (this.rows - 1) - 1.0F;
            float mu1 = 2.0F * (row + 1) / (this.rows - 1) - 1.0F;
            float cos0 = Mth.cos((float) Math.asin(Mth.clamp(mu0, -1.0F, 1.0F)));
            float cos1 = Mth.cos((float) Math.asin(Mth.clamp(mu1, -1.0F, 1.0F)));

            for (int column = 0; column < this.columns; column++) {
                int nextColumn = (column + 1) % this.columns;
                float az0 = (float) (Math.PI * 2.0 * column / this.columns);
                float az1 = (float) (Math.PI * 2.0 * (column + 1) / this.columns);

                vertex(builder, table, offset, row, column, az0, cos0, mu0);
                vertex(builder, table, offset, row, nextColumn, az1, cos0, mu0);
                vertex(builder, table, offset, row + 1, nextColumn, az1, cos1, mu1);
                vertex(builder, table, offset, row + 1, column, az0, cos1, mu1);
            }
        }

        return builder.buildOrThrow();
    }

    private void vertex(BufferBuilder builder, float[] table, int offset, int row, int column,
            float azimuth, float cosAltitude, float mu) {
        float x = cosAltitude * Mth.cos(azimuth) * 100.0F;
        float y = mu * 100.0F;
        float z = cosAltitude * Mth.sin(azimuth) * 100.0F;
        int i = offset + (row * this.columns + column) * 3;

        builder.addVertex(x, y, z)
                .setColor(encode8(table[i]), encode8(table[i + 1]), encode8(table[i + 2]), 255);
    }

    private static int encode8(float linear) {
        return SRGB8[(int) (Mth.clamp(linear, 0.0F, 1.0F) * 4095.0F + 0.5F)];
    }

    private static int[] srgb8Table() {
        int[] table = new int[4096];
        for (int i = 0; i < table.length; i++) {
            table[i] = Math.round(srgbEncode(i / 4095.0F) * 255.0F);
        }
        return table;
    }

    public void render(ClientLevel level, Matrix4f frustum, Matrix4f projection, float partialTick,
            Runnable skyFogSetup) {
        SkyMode mode = mode();
        if (mode == SkyMode.VANILLA) {
            return;
        }
        select(level, partialTick);
        sync(mode);

        skyFogSetup.run();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(false);

        RenderSystem.enableCull();

        long skyStarted = System.nanoTime();
        if (mode == SkyMode.DOME) {
            ShaderInstance shader = this.domeShader;

            shader.safeGetUniform("u_alpha").set(1.0F);
            this.slots[this.current].bind();
            this.slots[this.current].drawWithShader(frustum, projection, shader);
            VertexBuffer.unbind();

            if (this.blend > 1.0E-4F) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                shader.safeGetUniform("u_alpha").set(this.blend);
                this.slots[this.next].bind();
                this.slots[this.next].drawWithShader(frustum, projection, shader);
                VertexBuffer.unbind();
                RenderSystem.disableBlend();
            }
        } else {
            ShaderInstance shader = this.quadShader;
            shader.safeGetUniform("u_invViewProj").set(new Matrix4f(projection).mul(frustum).invert());
            shader.safeGetUniform("u_t").set(this.blend);
            shader.safeGetUniform("u_azSamples").set(this.columns);
            shader.safeGetUniform("u_altSamples").set(this.rows);
            shader.setSampler("TableCurrent", this.textures[this.current]);
            shader.setSampler("TableNext", this.textures[this.next]);
            this.screenQuad.bind();
            this.screenQuad.drawWithShader(frustum, projection, shader);
            VertexBuffer.unbind();
        }
        this.skyNanos += System.nanoTime() - skyStarted;

        RenderSystem.enableCull();

        float timeOfDay = level.getTimeOfDay(partialTick);
        PoseStack pose = new PoseStack();
        pose.mulPose(frustum);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        pose.pushPose();
        float fade = 1.0F - Mth.clamp(level.getRainLevel(partialTick), 0.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.XP.rotationDegrees(timeOfDay * 360.0F));
        Matrix4f celestial = pose.last().pose();

        ShaderInstance sprites = GameRenderer.getPositionTexShader();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, fade);
        this.sunBuffer.bind();
        this.sunBuffer.drawWithShader(celestial, projection, sprites);
        VertexBuffer.unbind();

        RenderSystem.setShaderTexture(0, MOON_TEXTURE);
        this.moonBuffer.bind();
        this.moonBuffer.drawWithShader(celestial, projection, sprites);
        VertexBuffer.unbind();

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
        this.frames++;
    }

    private static MeshData buildCelestial(float size, float y, float u0, float v0, float u1, float v1) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(-size, y, -size).setUv(u0, v0);
        builder.addVertex(size, y, -size).setUv(u1, v0);
        builder.addVertex(size, y, size).setUv(u1, v1);
        builder.addVertex(-size, y, size).setUv(u0, v1);
        return builder.buildOrThrow();
    }

    private static MeshData buildStars() {
        RandomSource random = RandomSource.create(10842L);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

        int count = AtmosphereSettings.starCount;
        float sizeVariation = AtmosphereSettings.starSizeVariation;
        float minimumSize = AtmosphereSettings.minimumStarSize;

        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 2.0F - 1.0F;
            float y = random.nextFloat() * 2.0F - 1.0F;
            float z = random.nextFloat() * 2.0F - 1.0F;
            float size = minimumSize + random.nextFloat() * sizeVariation;
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

    public boolean horizonColour(ClientLevel level, float partialTick, float dirX, float dirZ, float[] out) {
        if (!isReady()) {
            return false;
        }
        select(level, partialTick);
        SkyLut lut = this.variants[this.variant];
        lut.sample(dirX, 0.02F, dirZ, this.currentStep, this.nextStep, this.blend, out);
        for (int i = 0; i < 3; i++) {
            out[i] = srgbEncode(Mth.clamp(out[i], 0.0F, 1.0F));
        }
        return true;
    }

    private static float srgbEncode(float linear) {
        float c = Mth.clamp(linear, 0.0F, 1.0F);
        return c <= 0.0031308F ? 12.92F * c : 1.055F * (float) Math.pow(c, 1.0 / 2.4) - 0.055F;
    }

    @Override
    public void close() {
        this.closed = true;
        if (instance == this) {
            instance = null;
        }
        releaseModeResources();
        if (this.starBuffer != null) {
            this.starBuffer.close();
            this.starBuffer = null;
        }
        if (this.sunBuffer != null) {
            this.sunBuffer.close();
            this.sunBuffer = null;
        }
        if (this.moonBuffer != null) {
            this.moonBuffer.close();
            this.moonBuffer = null;
        }
    }
}
