package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.voxellight.debug.PassMetrics;
import com.voxellight.VoxelLightClient;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Version-specific diagnostic pass. Owns no Minecraft device, queue or framebuffer. */
public final class RenderProbe {
    public enum Mode {
        OFF, COLOR, DEPTH, NORMAL, SHADOW, SHADOW_MASK, SHADOW_MAP, SHADOW_RANGES, ALBEDO, SURFACE_NORMAL, EMISSION, MATERIAL_FLAGS, MATERIAL_COVERAGE;

        public boolean isMaterial() { return this == ALBEDO || this == SURFACE_NORMAL || this == EMISSION || this == MATERIAL_FLAGS || this == MATERIAL_COVERAGE; }

        public boolean isShadow() {
            return this == SHADOW || this == SHADOW_MASK || this == SHADOW_MAP || this == SHADOW_RANGES;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("VoxelLight");
    private static final RenderPipeline COLOR = pipeline("color");
    private static final RenderPipeline DEPTH = pipeline("depth");
    private static final RenderPipeline NORMAL = pipeline("normal");
    static final ShaderSource SHADERS = (id, type) -> readShader(id, type);
    private final PassMetrics metrics = new PassMetrics(14_400);
    private final ShadowRenderer shadows = new ShadowRenderer();
    private final MaterialCapture material = new MaterialCapture();
    private Mode mode = Mode.OFF;
    private GpuTexture scratch;
    private GpuTextureView scratchView;
    private GpuPassTimer timer;
    private boolean timerAttempted;
    private long frame;
    private String backend = "not observed";
    private String deviceName = "not observed";
    private String driver = "not observed";
    private String state = "off";
    private String timing = "not observed";
    private boolean zZeroToOne;
    private boolean materialPointObserved;

    public void setMode(Mode mode) {
        RenderSystem.assertOnRenderThread();
        if ((mode.isShadow() || mode.isMaterial()) && !VoxelLightClient.scene().isEnabled()) VoxelLightClient.scene().setEnabled(true);
        if (this.mode == mode) return;
        if ((this.mode.isShadow() && mode.isShadow()) || (this.mode.isMaterial() && mode.isMaterial())) resetTiming();
        else reset();
        this.mode = mode;
        state = mode == Mode.OFF ? "off" : "waiting for world render";
    }

    public void setShadowDistance(int blocks) {
        RenderSystem.assertOnRenderThread();
        shadows.setShadowDistance(blocks);
        resetTiming();
    }

    public void setEntityShadows(boolean value) { RenderSystem.assertOnRenderThread(); shadows.setEntityShadows(value); resetTiming(); }
    public void setFineShapes(boolean value) { RenderSystem.assertOnRenderThread(); shadows.setFineShapes(value); resetTiming(); }
    public void setLocalLights(boolean enabled) {
        RenderSystem.assertOnRenderThread();
        shadows.setLocalLights(enabled);
        resetTiming();
    }

    public void setWorldSun(boolean enabled) {
        RenderSystem.assertOnRenderThread();
        shadows.setWorldSun(enabled);
        resetTiming();
    }

    public void setShadowCache(boolean enabled) {
        RenderSystem.assertOnRenderThread();
        shadows.setCacheEnabled(enabled);
        resetTiming();
    }

    public Mode mode() {
        return mode;
    }

    public PassMetrics metrics() {
        return metrics;
    }

    public String status() {
        return "mode=" + mode + ", state=" + state + ", backend=" + backend + ", device=" + deviceName
                + ", driver=" + driver + ", depthZeroToOne=" + zZeroToOne + ", gpuTiming=" + timing
                + ", skippedQueries=" + (timer == null ? 0 : timer.skipped())
                + ", scratchBytes=" + scratchBytes() + ", samples=" + metrics.snapshot().size()
                + (mode.isShadow() ? ", " + shadows.status() : mode.isMaterial() ? ", " + material.status() : "");
    }

    public long scratchBytes() {
        return scratch == null ? 0 : (long) scratch.getWidth(0) * scratch.getHeight(0) * scratch.getFormat().blockSize();
    }

    public void render(RenderTarget target) {
        if (mode.isMaterial()) {
            if (!materialPointObserved) state = "opaque terrain hook not observed; vanilla retained";
            materialPointObserved = false;
            return;
        }
        renderPass(target, null);
    }

    public void renderMaterialTerrain(RenderTarget target, com.mojang.blaze3d.textures.GpuSampler terrainSampler) {
        if (!mode.isMaterial()) return;
        materialPointObserved = true;
        renderPass(target, terrainSampler);
    }

    private void renderPass(RenderTarget target, com.mojang.blaze3d.textures.GpuSampler terrainSampler) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        var info = device.getDeviceInfo();
        backend = info.backendName();
        deviceName = info.name();
        driver = info.driverInfo();
        zZeroToOne = info.isZZeroToOne();
        if (mode == Mode.OFF) {
            return;
        }
        if (!"Vulkan".equalsIgnoreCase(backend)) {
            state = "unsupported backend; vanilla rendering retained";
            return;
        }
        if (target.width <= 0 || target.height <= 0 || target.getColorTexture() == null
                || target.getDepthTextureView() == null) {
            state = "scene targets unavailable";
            return;
        }
        if (target.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) {
            state = "unsupported scene color format; vanilla rendering retained";
            return;
        }

        frame++;
        try {
            if (mode.isShadow() && !shadows.prepare()) {
                releaseScratch();
                state = "shadow not ready; vanilla rendering retained";
                return;
            }
            if (mode.isMaterial() && !material.prepare(target)) {
                releaseScratch(); state = "material unavailable; vanilla retained"; return;
            }
            RenderPipeline pipeline = switch (mode) {
                case COLOR -> COLOR;
                case DEPTH -> DEPTH;
                case NORMAL -> NORMAL;
                case SHADOW, SHADOW_MASK, SHADOW_MAP, SHADOW_RANGES, ALBEDO, SURFACE_NORMAL, EMISSION, MATERIAL_FLAGS, MATERIAL_COVERAGE -> null;
                case OFF -> throw new IllegalStateException("Off mode cannot render");
            };
            // Minecraft owns compilation/cache destruction. Only our shaders are supplied here.
            if (pipeline != null && !device.precompilePipeline(pipeline, SHADERS).isValid()) {
                throw new IllegalStateException("Diagnostic shader compilation failed");
            }
            if (mode == Mode.COLOR || mode.isShadow() || mode.isMaterial()) {
                ensureScratch(target);
            }
            if (!timerAttempted) {
                timerAttempted = true;
                try {
                    timer = new GpuPassTimer(device);
                    timing = "available; delayed nonblocking read";
                } catch (RuntimeException e) {
                    timing = "unavailable: " + e.getClass().getSimpleName();
                    LOGGER.warn("GPU timestamps unavailable; diagnostic pass remains active", e);
                }
            }
            if (timer != null) {
                try {
                    timer.poll(frame, metrics);
                } catch (RuntimeException e) {
                    disableTimer(e);
                }
            }

            long cpuStart = System.nanoTime();
            var encoder = device.createCommandEncoder();
            int query = -1;
            if (timer != null) {
                try {
                    query = timer.begin(encoder, frame);
                } catch (RuntimeException e) {
                    disableTimer(e);
                }
            }
            if (mode == Mode.COLOR || mode.isShadow() || mode.isMaterial()) {
                encoder.copyTextureToTexture(target.getColorTexture(), scratch, 0, 0, 0, 0, 0, target.width, target.height);
            }
            if (mode.isMaterial()) {
                material.render(encoder, target, scratchView, mode, terrainSampler);
            } else if (mode.isShadow()) {
                shadows.render(encoder, target, scratchView, mode);
            } else try (var pass = encoder.createRenderPass(() -> "VoxelLight diagnostic", target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("SceneSampler", mode == Mode.COLOR ? scratchView : target.getDepthTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                if (mode == Mode.NORMAL) {
                    pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
                }
                // 26.2: vertexCount, instanceCount, firstVertex, firstInstance.
                pass.draw(3, 1, 0, 0);
            }
            if (timer != null) {
                try {
                    timer.end(encoder, query);
                } catch (RuntimeException e) {
                    disableTimer(e);
                }
            }
            // Leave submission/frame lifecycle to Minecraft's shared encoder.
            metrics.record(frame, mode.name(), target.width, target.height, System.nanoTime() - cpuStart);
            state = mode.isShadow() ? "terrain lighting active" : mode.isMaterial() ? "material diagnostic active" : "diagnostic active";
        } catch (RuntimeException e) {
            LOGGER.error("Diagnostic pass disabled after failure", e);
            reset();
            mode = Mode.OFF;
            state = "failed; vanilla rendering restored on subsequent frames (see log)";
        }
    }

    /** Called on resize, world reset and shutdown. Backend close methods defer GPU destruction. */
    public void reset() {
        RenderSystem.assertOnRenderThread();
        releaseScratch();
        shadows.close();
        material.close();
        materialPointObserved = false;
        resetTiming();
        state = mode == Mode.OFF ? "off" : "waiting for world render";
    }

    private void resetTiming() {
        if (timer != null) {
            timer.close();
            timer = null;
        }
        timerAttempted = false;
        timing = "not observed";
        metrics.clear();
    }

    private void ensureScratch(RenderTarget target) {
        if (scratch != null && scratch.getWidth(0) == target.width && scratch.getHeight(0) == target.height
                && scratch.getFormat() == target.getColorTexture().getFormat()) {
            return;
        }
        releaseScratch();
        var device = RenderSystem.getDevice();
        scratch = device.createTexture("VoxelLight scene color", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                target.getColorTexture().getFormat(), target.width, target.height, 1, 1);
        scratchView = device.createTextureView(scratch);
    }

    private void releaseScratch() {
        if (scratchView != null) {
            scratchView.close();
            scratchView = null;
        }
        if (scratch != null) {
            scratch.close();
            scratch = null;
        }
    }

    private void disableTimer(RuntimeException e) {
        timing = "unavailable: " + e.getClass().getSimpleName();
        LOGGER.warn("GPU timing disabled", e);
        timer.close();
        timer = null;
    }

    private static RenderPipeline pipeline(String name) {
        var bindings = BindGroupLayout.builder().withSampler("SceneSampler");
        if (name.equals("normal")) {
            bindings.withUniform("Projection", UniformType.UNIFORM_BUFFER);
        }
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("voxellight", "pipeline/" + name))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight", "probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight", name))
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
    }

    private static String readShader(Identifier id, ShaderType type) {
        String extension = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
        String path = "/assets/" + id.getNamespace() + "/shaders/" + id.getPath() + extension;
        try (var stream = RenderProbe.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing shader: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
