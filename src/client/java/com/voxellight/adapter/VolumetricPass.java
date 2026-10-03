package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.VolumetricLight;
import com.voxellight.world.VisualPolish;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Quarter-resolution in-scattered HDR radiance and transmittance; current frame only. */
final class VolumetricPass implements AutoCloseable {
    static final RenderPipeline VOLUMETRIC = pipeline();
    private GpuTexture texture, neutral;
    private GpuTextureView view, neutralView;
    private GpuBuffer settings;
    private int width, height;
    private boolean active;
    private long passes;

    void render(CommandEncoder encoder, RenderTarget output, MaterialCapture material, ShadowRenderer shadows,
                GpuBuffer atmosphere, GpuBuffer environment, boolean enabled) {
        active = false;
        var device = RenderSystem.getDevice();
        if (neutral == null) {
            neutral = device.createTexture("VoxelLight neutral volumetric", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT, 1, 1, 1, 1);
            neutralView = device.createTextureView(neutral);
            try (var pass = encoder.createRenderPass(descriptor(neutralView, 1, 1))) { }
            settings = device.createBuffer(() -> "VoxelLight volumetric controls", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, VolumetricLight.SETTINGS_BYTES);
        }
        if (!enabled || VolumetricLight.targetBytes(output.width, output.height) > VolumetricLight.TARGET_LIMIT) releaseTarget();
        else {
            if (!device.precompilePipeline(VOLUMETRIC, RenderProbe.SHADERS).isValid()) throw new IllegalStateException("Volumetric shader failed");
            int w = VisualPolish.quarterSize(output.width), h = VisualPolish.quarterSize(output.height);
            if (texture == null || width != w || height != h) {
                releaseTarget(); width = w; height = h;
                texture = device.createTexture("VoxelLight quarter-resolution volumetric", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
                view = device.createTextureView(texture);
            }
            active = true;
        }
        try (var stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(), Std140Builder.onStack(stack, VolumetricLight.SETTINGS_BYTES)
                    .putVec4(active ? 1 : 0, VolumetricLight.MAX_DISTANCE, VolumetricLight.ANISOTROPY, VolumetricLight.PHASE_SCALE).get());
        }
        if (!active) return;
        try (var pass = encoder.createRenderPass(descriptor(view, width, height))) {
            pass.setPipeline(VOLUMETRIC);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SceneDepth", output.getDepthTextureView(), nearest);
            pass.bindTexture("MaterialEmission", material.view(2), nearest);
            shadows.bindVolumetric(pass);
            pass.setUniform("AtmosphereSettings", atmosphere);
            pass.setUniform("LightingEnvironment", environment);
            pass.setUniform("VolumetricSettings", settings);
            pass.draw(3, 1, 0, 0);
        }
        passes++;
    }
    void bind(RenderPass pass) {
        pass.bindTexture("VolumetricScatter", active ? view : neutralView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.setUniform("VolumetricSettings", settings);
    }
    boolean active() { return active; }
    String status() { return ", volumetric=" + (active ? "quarter-res shadowed; 16 steps" : "off/medium/budget fallback")
            + ", volumetricBytes=" + (texture == null ? 0 : (long)width * height * 8) + ", volumetricPasses=" + passes; }
    static RenderPassDescriptor descriptor(GpuTextureView target, int width, int height) {
        return RenderPassDescriptor.create(() -> "VoxelLight shadowed medium").withRenderArea(new RenderPass.RenderArea(0, 0, width, height))
                .withColorAttachment(target, Optional.of(new Vector4f(0, 0, 0, 1)));
    }
    private void releaseTarget() {
        if (view != null) { view.close(); view = null; }
        if (texture != null) { texture.close(); texture = null; }
        width = height = 0; active = false;
    }
    @Override public void close() {
        releaseTarget();
        if (neutralView != null) { neutralView.close(); neutralView = null; }
        if (neutral != null) { neutral.close(); neutral = null; }
        if (settings != null) { settings.close(); settings = null; }
    }
    private static RenderPipeline pipeline() {
        var layout = BindGroupLayout.builder().withSampler("SceneDepth").withSampler("MaterialEmission")
                .withUniform("ShadowResolveSettings", UniformType.UNIFORM_BUFFER)
                .withUniform("AtmosphereSettings", UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment", UniformType.UNIFORM_BUFFER)
                .withUniform("VolumetricSettings", UniformType.UNIFORM_BUFFER);
        for (var name : new String[]{"ShadowMap", "MiddleShadowMap", "FarShadowMap", "EntityShadowMap", "MiddleEntityShadowMap", "FarEntityShadowMap"}) layout.withSampler(name);
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight", "pipeline/volumetric"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight", "probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight", "volumetric"))
                .withBindGroupLayout(layout.build()).withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
