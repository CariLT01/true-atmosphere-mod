package com.carilt01.trueatmosphere.client;

import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.carilt01.trueatmosphere.AtmosphereSettings;
import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.SkyMode;
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class SkyDomeRenderer implements AutoCloseable {
    private static final ResourceLocation SUN_TEXTURE = ResourceLocation.withDefaultNamespace(
            "textures/environment/sun.png");

    private static final ResourceLocation MOON_TEXTURE = ResourceLocation.withDefaultNamespace(
            "textures/environment/moon_phases.png");

    private static final float SUN_QUAD = 15.0F;
    private static final float MOON_QUAD = 20.0F;

    private static final float STAR_QUAD_SCALE = 0.7F;
    private static final float MOON_HEIGHT = -100.0F;

    private static final double SKY_RADIUS = 100.0;

    private static final int[] SRGB8 = srgb8Table();

    private static final SkyMode PROPERTY_MODE = modeProperty();

    public static volatile SkyDomeRenderer instance;

    private final SkyLut[] variants;
    private final SkyLut base;
    private final int rows;
    private final int columns;
    private final int paddedColumns;

    private RenderPipeline domePipeline;
    private RenderPipeline domeBlendPipeline;
    private RenderPipeline quadPipeline;
    private Boolean pipelinesValid;

    private final GpuBuffer[] slots = new GpuBuffer[2];
    private final int[] slotIndexCount = new int[2];
    private final int[] slotStep = {-1, -1};
    private final int[] slotVariant = {-1, -1};
    private final GpuTexture[] textures = new GpuTexture[2];

    private GpuBuffer screenQuad;

    private GpuBuffer starBuffer;
    private int starIndexCount;
    private GpuBuffer sunBuffer;
    private int sunIndexCount;
    private GpuBuffer moonBuffer;
    private int moonIndexCount;

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
        instance = this;
    }

    private void ensurePipelines() {
        if (this.domePipeline != null) {
            return;
        }

        this.domePipeline = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
                .withLocation(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "pipeline/sky_dome"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .build();

        this.domeBlendPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
                .withLocation(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "pipeline/sky_dome_blend"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .build();

        this.quadPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
                .withLocation(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "pipeline/sky_quad"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "core/sky_quad"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(ModInfo.MODID, "core/sky_quad"))
                .withShaderDefine("SKY_AZ_SAMPLES", this.columns)
                .withShaderDefine("SKY_ALT_SAMPLES", this.rows)
                .withSampler("TableCurrent")
                .withSampler("TableNext")
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.TRIANGLE_FAN)
                .build();
    }

    private boolean pipelinesUsable() {
        ensurePipelines();
        if (this.pipelinesValid == null) {
            CompiledRenderPipeline compiled = RenderSystem.getDevice()
                    .precompilePipeline(mode() == SkyMode.DOME ? this.domePipeline : this.quadPipeline);
            this.pipelinesValid = compiled.isValid();
            if (!this.pipelinesValid) {
                ModInfo.LOGGER.error("Atmospheric sky pipeline failed to compile, leaving the vanilla sky in place");
            }
        }
        return this.pipelinesValid;
    }

    public boolean isReady() {
        if (this.closed) {
            return false;
        }
        SkyMode mode = mode();
        if (mode == SkyMode.VANILLA || RenderSystem.getDevice() == null) {
            return false;
        }
        return pipelinesUsable();
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

        if (mode != SkyMode.DOME) {
            for (int i = 0; i < 2; i++) {
                int index = i;
                GpuTexture texture = RenderSystem.getDevice().createTexture(
                        () -> "True Atmosphere sky table " + index,
                        TextureFormat.RGBA8, this.paddedColumns, this.rows, 1);
                texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
                texture.setTextureFilter(FilterMode.LINEAR, false);
                this.textures[i] = texture;
            }
            this.screenQuad = buildFullscreenQuad();
        }

        this.gpuMode = mode;

        if (this.starBuffer == null) {
            buildCelestialBuffers();
        }

        ModInfo.LOGGER.info("Sky GPU ready: {} mode, {}x{} table ({} KiB per step), pipeline {}",
                mode, this.columns, this.rows, this.base.stepFloats() * 4 / 1024,
                mode == SkyMode.DOME ? "sky_dome" : "sky_quad");
    }

    private GpuBuffer buildFullscreenQuad() {
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(
                4 * DefaultVertexFormat.POSITION.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLE_FAN,
                    DefaultVertexFormat.POSITION);
            builder.addVertex(-1.0F, -1.0F, 0.0F);
            builder.addVertex(1.0F, -1.0F, 0.0F);
            builder.addVertex(1.0F, 1.0F, 0.0F);
            builder.addVertex(-1.0F, 1.0F, 0.0F);
            try (MeshData mesh = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "True Atmosphere fullscreen quad",
                        BufferType.VERTICES, BufferUsage.STATIC_WRITE, mesh.vertexBuffer());
            }
        }
    }

    private void buildCelestialBuffers() {
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(
                AtmosphereSettings.starCount * 4 * DefaultVertexFormat.POSITION.getVertexSize() + 64)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            addStars(builder);
            try (MeshData mesh = builder.buildOrThrow()) {
                this.starIndexCount = mesh.drawState().indexCount();
                this.starBuffer = RenderSystem.getDevice().createBuffer(() -> "True Atmosphere stars",
                        BufferType.VERTICES, BufferUsage.STATIC_WRITE, mesh.vertexBuffer());
            }
        }

        try (ByteBufferBuilder bytes = new ByteBufferBuilder(
                4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_TEX_COLOR);
            addCelestialQuad(builder, SUN_QUAD, 100.0F, 0.0F, 0.0F, 1.0F, 1.0F, false);
            try (MeshData mesh = builder.buildOrThrow()) {
                this.sunIndexCount = mesh.drawState().indexCount();
                this.sunBuffer = RenderSystem.getDevice().createBuffer(() -> "True Atmosphere sun",
                        BufferType.VERTICES, BufferUsage.STATIC_WRITE, mesh.vertexBuffer());
            }
        }

        try (ByteBufferBuilder bytes = new ByteBufferBuilder(
                4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_TEX_COLOR);
            addCelestialQuad(builder, MOON_QUAD, MOON_HEIGHT, 0.0F, 0.0F, 0.25F, 0.5F, true);
            try (MeshData mesh = builder.buildOrThrow()) {
                this.moonIndexCount = mesh.drawState().indexCount();
                this.moonBuffer = RenderSystem.getDevice().createBuffer(() -> "True Atmosphere moon",
                        BufferType.VERTICES, BufferUsage.STATIC_WRITE, mesh.vertexBuffer());
            }
        }
    }

    private void releaseModeResources() {
        for (int i = 0; i < this.slots.length; i++) {
            if (this.slots[i] != null) {
                this.slots[i].close();
                this.slots[i] = null;
            }
            this.slotIndexCount[i] = 0;
        }
        if (this.screenQuad != null) {
            this.screenQuad.close();
            this.screenQuad = null;
        }
        for (int i = 0; i < this.textures.length; i++) {
            if (this.textures[i] != null) {
                this.textures[i].close();
                this.textures[i] = null;
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
            if (this.slots[slot] != null) {
                this.slots[slot].close();
                this.slots[slot] = null;
            }
            uploadDome(lut, step, slot);
        } else {
            uploadTable(lut, step, slot);
        }
        this.uploadNanos += System.nanoTime() - started;
        this.uploadCount++;

        this.slotStep[slot] = step;
        this.slotVariant[slot] = variantIndex;
        this.uploads++;
    }

    private void uploadDome(SkyLut lut, int step, int slot) {
        int quads = (this.rows - 1) * this.columns;
        int bytesPerVertex = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(quads * 4 * bytesPerVertex)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS,
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

            try (MeshData mesh = builder.buildOrThrow()) {
                this.slotIndexCount[slot] = mesh.drawState().indexCount();
                this.slots[slot] = RenderSystem.getDevice().createBuffer(
                        () -> "True Atmosphere sky dome " + slot,
                        BufferType.VERTICES, BufferUsage.STATIC_WRITE, mesh.vertexBuffer());
            }
        }
    }

    private void vertex(BufferBuilder builder, float[] table, int offset, int row, int column,
            float azimuth, float cosAltitude, float mu) {
        float x = (float) (cosAltitude * Mth.cos(azimuth) * SKY_RADIUS);
        float y = (float) (mu * SKY_RADIUS);
        float z = (float) (cosAltitude * Mth.sin(azimuth) * SKY_RADIUS);
        int i = offset + (row * this.columns + column) * 3;

        builder.addVertex(x, y, z)
                .setColor(encode8(table[i]), encode8(table[i + 1]), encode8(table[i + 2]), 255);
    }

    private void uploadTable(SkyLut lut, int step, int slot) {
        float[] table = lut.display();
        int offset = lut.stepOffset(step);

        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, this.paddedColumns, this.rows, false)) {
            for (int row = 0; row < this.rows; row++) {
                int rowStart = offset + row * this.columns * 3;
                for (int column = 0; column < this.paddedColumns; column++) {
                    int i = rowStart + (column == this.columns ? 0 : column * 3);
                    int r = encode8(table[i]);
                    int g = encode8(table[i + 1]);
                    int b = encode8(table[i + 2]);

                    image.setPixel(column, row, (0xFF << 24) | (r << 16) | (g << 8) | b);
                }
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToTexture(this.textures[slot], image);
        }
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

    public void render(ClientLevel level, float partialTick) {
        SkyMode mode = mode();
        if (mode == SkyMode.VANILLA || !isReady()) {
            return;
        }
        select(level, partialTick);
        sync(mode);

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        Matrix4f restore = new Matrix4f(stack);
        stack.set(SkyBobbing.withoutViewBob(restore));
        try {
            Minecraft minecraft = Minecraft.getInstance();
            GpuTexture color = minecraft.getMainRenderTarget().getColorTexture();
            GpuTexture depth = minecraft.getMainRenderTarget().getDepthTexture();

            long skyStarted = System.nanoTime();
            if (mode == SkyMode.DOME) {
                drawDome(color, depth, this.domePipeline, this.current, 1.0F);

                if (this.blend > 1.0E-4F) {
                    drawDome(color, depth, this.domeBlendPipeline, this.next, this.blend);
                }
            } else {
                drawQuad(color, depth, this.blend);
            }
            this.skyNanos += System.nanoTime() - skyStarted;

            drawCelestial(level, partialTick, color, depth);
        } finally {
            stack.set(restore);
        }
        this.frames++;
    }

    private static void modulate(float alpha) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
    }

    private void drawDome(GpuTexture color, GpuTexture depth, RenderPipeline pipeline, int slot,
            float alpha) {
        if (this.slots[slot] == null || this.slotIndexCount[slot] == 0) {
            return;
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(this.slotIndexCount[slot]);
        int indexCount = this.slotIndexCount[slot];

        modulate(alpha);
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, this.slots[slot]);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, indexCount);
        } finally {
            modulate(1.0F);
        }
    }

    private void drawQuad(GpuTexture color, GpuTexture depth, float alpha) {
        modulate(alpha);
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(this.quadPipeline);
            pass.bindSampler("TableCurrent", this.textures[this.current]);
            pass.bindSampler("TableNext", this.textures[this.next]);
            pass.setVertexBuffer(0, this.screenQuad);
            pass.draw(0, 4);
        } finally {
            modulate(1.0F);
        }
    }

    private static volatile boolean vanillaCelestial;

    public static void useVanillaCelestial() {
        vanillaCelestial = true;
    }

    private void drawCelestial(ClientLevel level, float partialTick, GpuTexture color, GpuTexture depth) {
        if (vanillaCelestial) {
            return;
        }
        float timeOfDay = level.getTimeOfDay(partialTick);
        float fade = 1.0F - Mth.clamp(level.getRainLevel(partialTick), 0.0F, 1.0F);

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.rotateY((float) (-Math.PI / 2.0));
            stack.rotateX((float) (timeOfDay * Math.PI * 2.0));

            drawTexturedCelestial(color, depth, this.sunBuffer, this.sunIndexCount, fade, SUN_TEXTURE);
            drawTexturedCelestial(color, depth, this.moonBuffer, this.moonIndexCount, fade, MOON_TEXTURE);

            float brightness = level.getStarBrightness(partialTick) * fade;
            if (brightness > 0.0F && this.starBuffer != null) {
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer indexBuffer = indices.getBuffer(this.starIndexCount);
                modulate(brightness);
                try (RenderPass pass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                    pass.setPipeline(RenderPipelines.STARS);
                    pass.setVertexBuffer(0, this.starBuffer);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.drawIndexed(0, this.starIndexCount);
                } finally {
                    modulate(1.0F);
                }
            }
        } finally {
            stack.popMatrix();
        }
    }

    private void drawTexturedCelestial(GpuTexture color, GpuTexture depth, GpuBuffer buffer,
            int indexCount, float alpha, ResourceLocation texture) {
        if (buffer == null || indexCount == 0) {
            return;
        }
        AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(texture);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(indexCount);

        modulate(alpha);
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(RenderPipelines.CELESTIAL);
            pass.bindSampler("Sampler0", loaded.getTexture());
            pass.setVertexBuffer(0, buffer);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, indexCount);
        } finally {
            modulate(1.0F);
        }
    }

    private static void addCelestialQuad(BufferBuilder builder, float size, float y,
            float u0, float v0, float u1, float v1, boolean flipped) {
        int white = 0xFFFFFFFF;
        if (flipped) {
            builder.addVertex(-size, y, size).setUv(u1, v1).setColor(white);
            builder.addVertex(size, y, size).setUv(u0, v1).setColor(white);
            builder.addVertex(size, y, -size).setUv(u0, v0).setColor(white);
            builder.addVertex(-size, y, -size).setUv(u1, v0).setColor(white);
        } else {
            builder.addVertex(-size, y, -size).setUv(u0, v0).setColor(white);
            builder.addVertex(size, y, -size).setUv(u1, v0).setColor(white);
            builder.addVertex(size, y, size).setUv(u1, v1).setColor(white);
            builder.addVertex(-size, y, size).setUv(u0, v1).setColor(white);
        }
    }

    private static void addStars(BufferBuilder builder) {
        RandomSource random = RandomSource.create(10842L);

        int count = AtmosphereSettings.starCount;
        float sizeVariation = AtmosphereSettings.starSizeVariation;
        float minimumSize = AtmosphereSettings.minimumStarSize;

        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 2.0F - 1.0F;
            float y = random.nextFloat() * 2.0F - 1.0F;
            float z = random.nextFloat() * 2.0F - 1.0F;
            float size = (minimumSize + random.nextFloat() * sizeVariation) * STAR_QUAD_SCALE;
            float lengthSq = Mth.lengthSquared(x, y, z);
            if (lengthSq <= 0.010000001F || lengthSq >= 1.0F) {
                continue;
            }
            Vector3f center = new Vector3f(x, y, z).normalize((float) SKY_RADIUS);
            float roll = (float) (random.nextDouble() * (float) Math.PI * 2.0);
            Quaternionf rotation = new Quaternionf().rotateTo(new Vector3f(0.0F, 0.0F, -1.0F), center).rotateZ(roll);

            builder.addVertex(new Vector3f(size, -size, 0.0F).rotate(rotation).add(center));
            builder.addVertex(new Vector3f(size, size, 0.0F).rotate(rotation).add(center));
            builder.addVertex(new Vector3f(-size, size, 0.0F).rotate(rotation).add(center));
            builder.addVertex(new Vector3f(-size, -size, 0.0F).rotate(rotation).add(center));
        }
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
        if (this.moonBuffer != null) {
            this.moonBuffer.close();
            this.moonBuffer = null;
        }
        this.pipelinesValid = null;
    }
}
