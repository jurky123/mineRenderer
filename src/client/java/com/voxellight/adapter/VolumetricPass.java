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
import com.voxellight.world.VisualQuality;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Quarter-resolution in-scattered HDR radiance and transmittance; current frame only. */
final class VolumetricPass implements AutoCloseable {
    static final RenderPipeline VOLUMETRIC = pipeline(), VOLUME_FILTER = filterPipeline();
    private GpuTexture texture, neutral, scratch;
    private GpuTextureView view, neutralView, scratchView;
    private GpuBuffer settings, filterSettings;
    private int width, height;
    private boolean active,filtered=true;
    private VisualQuality quality=VisualQuality.BALANCED;
    void setQuality(VisualQuality value){quality=value;}
    void setFiltered(boolean value){filtered=value;}
    private long passes;

    void render(CommandEncoder encoder, RenderTarget output, MaterialCapture material, ShadowRenderer shadows,
                GpuBuffer atmosphere, GpuBuffer environment, boolean enabled) {
        active = false;
        var device = RenderSystem.getDevice();
        if (neutral == null) {
            neutral = device.createTexture("VoxelLight neutral volumetric", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT, 1, 1, 1, 1);
            neutralView = device.createTextureView(neutral);
            try (var profile = RenderPassProfile.begin(encoder,"volume_clear"); var pass = encoder.createRenderPass(descriptor(neutralView, 1, 1))) { }
            settings = device.createBuffer(() -> "VoxelLight volumetric controls", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, VolumetricLight.SETTINGS_BYTES);
        }
        if (!enabled || VolumetricLight.targetBytes(output.width, output.height,filtered) > VolumetricLight.TARGET_LIMIT) releaseTarget();
        else {
            if (!device.precompilePipeline(VOLUMETRIC, RenderProbe.SHADERS).isValid()) throw new IllegalStateException("Volumetric shader failed");
            int w = VisualPolish.quarterSize(output.width), h = VisualPolish.quarterSize(output.height);
            if (texture == null || width != w || height != h) {
                releaseTarget(); width = w; height = h;
                texture = device.createTexture("VoxelLight quarter-resolution volumetric", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
                view = device.createTextureView(texture);
            }
            if (filtered && scratch == null) {
                scratch=device.createTexture("VoxelLight volumetric spatial filter",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA16_FLOAT,width,height,1,1);
                scratchView=device.createTextureView(scratch);
            } else if(!filtered)releaseScratch();
            active = true;
        }
        try (var stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(), Std140Builder.onStack(stack, VolumetricLight.SETTINGS_BYTES)
                    .putVec4(active ? 1 : 0, VolumetricLight.MAX_DISTANCE, VolumetricLight.ANISOTROPY, VolumetricLight.PHASE_SCALE)
                    .putVec4(quality.volumeSteps(),filtered?1:0,0,0).get());
        }
        if (!active) return;
        try (var profile = RenderPassProfile.begin(encoder,"volume_march"); var pass = encoder.createRenderPass(descriptor(view, width, height))) {
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
        if(filtered) {
            if(!device.precompilePipeline(VOLUME_FILTER,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Volumetric spatial filter failed");
            if(filterSettings==null)filterSettings=device.createBuffer(()->"VoxelLight volumetric filter direction",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
            for(int axis=0;axis<2;axis++) {
                try(var stack=MemoryStack.stackPush()) {
                    encoder.writeToBuffer(filterSettings.slice(),Std140Builder.onStack(stack,16).putVec4(axis==0?1:0,axis==1?1:0,.025f,0).get());
                }
                try (var profile = RenderPassProfile.begin(encoder,"volume_filter"); var pass = encoder.createRenderPass(descriptor(axis==0?scratchView:view,width,height))) {
                    pass.setPipeline(VOLUME_FILTER);
                    var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                    pass.bindTexture("VolumeInput",axis==0?view:scratchView,nearest);
                    pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
                    shadows.bindVolumeTransform(pass);
                    pass.setUniform("VolumeFilterSettings",filterSettings);
                    pass.draw(3,1,0,0);
                }
                passes++;
            }
        }
    }
    void bind(RenderPass pass) {
        pass.bindTexture("VolumetricScatter", active ? view : neutralView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.setUniform("VolumetricSettings", settings);
    }
    boolean active() { return active; }
    String status() { return ", volumetric=" + (active ? "quarter-res shadowed; "+quality.volumeSteps()+" steps" : "off/medium/budget fallback")
            + ", volumetricBytes=" + (texture == null ? 0 : (long)width * height * (scratch==null?8:16)) + ", volumetricFilter="+filtered+", volumetricPasses=" + passes; }
    static RenderPassDescriptor descriptor(GpuTextureView target, int width, int height) {
        return RenderPassDescriptor.create(() -> "VoxelLight shadowed medium").withRenderArea(new RenderPass.RenderArea(0, 0, width, height))
                .withColorAttachment(target, Optional.of(new Vector4f(0, 0, 0, 1)));
    }
    private void releaseScratch() {
        if(scratchView!=null){scratchView.close();scratchView=null;}
        if(scratch!=null){scratch.close();scratch=null;}
    }
    private void releaseTarget() {
        releaseScratch();
        if (view != null) { view.close(); view = null; }
        if (texture != null) { texture.close(); texture = null; }
        width = height = 0; active = false;
    }
    @Override public void close() {
        releaseTarget();
        if (neutralView != null) { neutralView.close(); neutralView = null; }
        if (neutral != null) { neutral.close(); neutral = null; }
        if (settings != null) { settings.close(); settings = null; }
        if(filterSettings!=null){filterSettings.close();filterSettings=null;}
    }
    private static RenderPipeline filterPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/volumetric_filter"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","volumetric_filter"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("VolumeInput").withSampler("SceneDepth")
                        .withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).withUniform("VolumeFilterSettings",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
    private static RenderPipeline pipeline() {
        var layout = BindGroupLayout.builder().withSampler("SceneDepth").withSampler("MaterialEmission")
                .withUniform("ShadowResolveSettings", UniformType.UNIFORM_BUFFER)
                .withUniform("AtmosphereSettings", UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment", UniformType.UNIFORM_BUFFER)
                .withUniform("VolumetricSettings", UniformType.UNIFORM_BUFFER);
        for (var name : new String[]{"ShadowMap", "MiddleShadowMap", "FarShadowMap", "EntityShadowMap", "MiddleEntityShadowMap", "FarEntityShadowMap", "NextShadowMap", "MiddleNextShadowMap", "FarNextShadowMap"}) layout.withSampler(name);
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight", "pipeline/volumetric"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight", "probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight", "volumetric"))
                .withBindGroupLayout(layout.build()).withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
