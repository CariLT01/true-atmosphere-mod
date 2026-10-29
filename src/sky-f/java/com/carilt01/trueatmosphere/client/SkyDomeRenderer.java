package com.carilt01.trueatmosphere.client;

import java.util.Arrays;
import java.util.OptionalDouble;

import com.carilt01.trueatmosphere.ModInfo;
import com.carilt01.trueatmosphere.SkyMode;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public final class SkyDomeRenderer implements AutoCloseable {
    private static final RenderPipeline.Snippet MATRICES = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .buildSnippet();

    private static final BindGroupLayout TABLE_CURRENT = BindGroupLayout.builder()
            .withUniform("TableCurrent", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final BindGroupLayout TABLE_NEXT = BindGroupLayout.builder()
            .withUniform("TableNext", UniformType.COMBINED_IMAGE_SAMPLER).build();

    private static final DepthStencilState NO_DEPTH = new DepthStencilState(CompareOp.ALWAYS_PASS, false);

    private static final int[] SRGB8 = srgb8Table();

    private static final SkyMode PROPERTY_MODE = modeProperty();

    public static volatile SkyDomeRenderer instance;

    public static volatile float sunAngleDegrees;

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
    private final GpuTextureView[] views = new GpuTextureView[2];
    private GpuSampler sampler;

    private GpuBuffer screenQuad;

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

        this.domePipeline = RenderPipeline.builder(MATRICES)
                .withLocation(Identifier.fromNamespaceAndPath(ModInfo.MODID, "pipeline/sky_dome"))
                .withVertexShader(Identifier.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withDepthStencilState(NO_DEPTH)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();

        this.domeBlendPipeline = RenderPipeline.builder(MATRICES)
                .withLocation(Identifier.fromNamespaceAndPath(ModInfo.MODID, "pipeline/sky_dome_blend"))
                .withVertexShader(Identifier.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(ModInfo.MODID, "core/sky_dome"))
                .withDepthStencilState(NO_DEPTH)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();

        this.quadPipeline = RenderPipeline.builder(MATRICES)
                .withLocation(Identifier.fromNamespaceAndPath(ModInfo.MODID, "pipeline/sky_quad"))
                .withVertexShader(Identifier.fromNamespaceAndPath(ModInfo.MODID, "core/sky_quad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(ModInfo.MODID, "core/sky_quad"))
                .withShaderDefine("SKY_AZ_SAMPLES", this.columns)
                .withShaderDefine("SKY_ALT_SAMPLES", this.rows)
                .withBindGroupLayout(TABLE_CURRENT)
                .withBindGroupLayout(TABLE_NEXT)
                .withDepthStencilState(NO_DEPTH)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_FAN)
                .build();
    }

    private boolean pipelinesUsable() {
        ensurePipelines();
        if (this.pipelinesValid == null) {
            CompiledRenderPipeline compiled = RenderSystem
                    .getCompiledPipelineNullable(mode() == SkyMode.DOME ? this.domePipeline : this.quadPipeline);
            this.pipelinesValid = compiled != null;
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
        return property != null ? property : com.carilt01.trueatmosphere.AtmosphereSettings.skyMode;
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

    public static void updateSunAngle(float radians) {
        float angle = (float) Math.toDegrees(radians) + 90.0F;
        angle %= 360.0F;
        sunAngleDegrees = angle < 0.0F ? angle + 360.0F : angle;
    }

    private void select(ClientLevel level) {
        float rain = Mth.clamp(level.getRainLevel(1.0F), 0.0F, 1.0F);
        float thunder = Math.min(Mth.clamp(level.getThunderLevel(1.0F), 0.0F, 1.0F), rain);
        float clear = 1.0F - rain;
        float wet = rain * (1.0F - thunder);

        this.variant = thunder >= wet && thunder >= clear ? 2 : (wet >= clear ? 1 : 0);

        float wrapped = sunAngleDegrees % 360.0F;
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
            this.sampler = RenderSystem.getDevice().createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            for (int i = 0; i < 2; i++) {
                int index = i;
                GpuTexture texture = RenderSystem.getDevice().createTexture(
                        () -> "True Atmosphere sky table " + index,
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM, this.paddedColumns, this.rows, 1, 1);
                this.textures[i] = texture;
                this.views[i] = RenderSystem.getDevice().createTextureView(texture);
            }
            this.screenQuad = buildFullscreenQuad();
        }

        this.gpuMode = mode;

        ModInfo.LOGGER.info("Sky GPU ready: {} mode, {}x{} table ({} KiB per step), pipeline {}",
                mode, this.columns, this.rows, this.base.stepFloats() * 4 / 1024,
                mode == SkyMode.DOME ? "sky_dome" : "sky_quad");
    }

    private GpuBuffer buildFullscreenQuad() {
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(
                4 * DefaultVertexFormat.POSITION.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.TRIANGLE_FAN,
                    DefaultVertexFormat.POSITION);
            builder.addVertex(-1.0F, -1.0F, 0.0F);
            builder.addVertex(1.0F, -1.0F, 0.0F);
            builder.addVertex(1.0F, 1.0F, 0.0F);
            builder.addVertex(-1.0F, 1.0F, 0.0F);
            try (MeshData mesh = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "True Atmosphere fullscreen quad",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
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
            if (this.views[i] != null) {
                this.views[i].close();
                this.views[i] = null;
            }
            if (this.textures[i] != null) {
                this.textures[i].close();
                this.textures[i] = null;
            }
        }
        if (this.sampler != null) {
            this.sampler.close();
            this.sampler = null;
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
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(quads * 4 * bytesPerVertex)) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS,
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
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private void vertex(BufferBuilder builder, float[] table, int offset, int row, int column,
            float azimuth, float cosAltitude, float mu) {
        float x = (float) (cosAltitude * Mth.cos(azimuth) * 100.0);
        float y = (float) (mu * 100.0);
        float z = (float) (cosAltitude * Mth.sin(azimuth) * 100.0);
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

    public void prepare(ClientLevel level) {
        SkyMode mode = mode();
        if (mode == SkyMode.VANILLA || level == null || !isReady()) {
            return;
        }
        select(level);
        sync(mode);
    }

    public void render(RenderPass pass, Matrix4f modelView) {
        SkyMode mode = mode();
        if (mode == SkyMode.VANILLA || !isReady() || this.gpuMode != mode) {
            return;
        }
        if (mode == SkyMode.DOME ? this.slots[this.current] == null : this.views[this.current] == null) {
            return;
        }

        long skyStarted = System.nanoTime();
        debug(modelView);
        RenderSystem.bindDefaultUniforms(pass);
        if (mode == SkyMode.DOME) {
            drawDome(pass, writeTransform(modelView, new Vector4f(1.0F, 1.0F, 1.0F, 1.0F)),
                    this.domePipeline, this.current);

            if (this.blend > 1.0E-4F) {
                drawDome(pass, writeTransform(modelView, new Vector4f(1.0F, 1.0F, 1.0F, this.blend)),
                        this.domeBlendPipeline, this.next);
            }
        } else {
            drawQuad(pass, writeTransform(modelView, new Vector4f(1.0F, 1.0F, 1.0F, this.blend)));
        }
        this.skyNanos += System.nanoTime() - skyStarted;
        this.frames++;
    }

    private static final boolean DEBUG = System.getenv("ATMO_SKY_DEBUG") != null;

    private void debug(Matrix4f modelView) {
        if (!DEBUG || this.frames % 20 != 0) {
            return;
        }
        Matrix4f view = new Matrix4f(modelView);
        Vector4f axisX = new Vector4f(view.m00(), view.m01(), view.m02(), 0.0F);
        Vector4f axisY = new Vector4f(view.m10(), view.m11(), view.m12(), 0.0F);
        Vector4f axisZ = new Vector4f(view.m20(), view.m21(), view.m22(), 0.0F);
        ModInfo.LOGGER.info(
                "sky debug frame {}: type {} det {} x {} y {} z {} translation {} {} {}",
                this.frames, RenderSystem.getProjectionType(), view.determinant(),
                axisX.length(), axisY.length(), axisZ.length(),
                view.m30(), view.m31(), view.m32());
    }

    private static GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator) {
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, colorModulator);
    }

    private void drawDome(RenderPass pass, GpuBufferSlice transform, RenderPipeline pipeline, int slot) {
        if (this.slots[slot] == null || this.slotIndexCount[slot] == 0) {
            return;
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(this.slotIndexCount[slot]);

        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        pass.setUniform("DynamicTransforms", transform);
        pass.setVertexBuffer(0, this.slots[slot].slice());
        pass.setIndexBuffer(indexBuffer, indices.type());
        pass.drawIndexed(this.slotIndexCount[slot], 1, 0, 0, 0);
    }

    private void drawQuad(RenderPass pass, GpuBufferSlice transform) {
        pass.setPipeline(RenderSystem.getCompiledPipeline(this.quadPipeline));
        pass.setUniform("DynamicTransforms", transform);
        pass.setUniform("TableCurrent", this.views[this.current], this.sampler);
        pass.setUniform("TableNext", this.views[this.next], this.sampler);
        pass.setVertexBuffer(0, this.screenQuad.slice());
        pass.draw(4, 1, 0, 0);
    }

    public boolean horizonColour(ClientLevel level, float dirX, float dirZ, float[] out) {
        return skyColour(level, dirX, 0.02F, dirZ, out);
    }

    public boolean skyColour(ClientLevel level, float dirX, float dirY, float dirZ, float[] out) {
        if (!isReady() || level == null) {
            return false;
        }
        select(level);
        SkyLut lut = this.variants[this.variant];
        lut.sample(dirX, dirY, dirZ, this.currentStep, this.nextStep, this.blend, out);
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
        this.pipelinesValid = null;
    }
}
