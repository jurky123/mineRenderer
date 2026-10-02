package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.LightingEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector4f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Linear opaque lighting, followed by tone mapping and native fog before entity/translucent composition. */
final class LightingResolvePass implements AutoCloseable {
    static final RenderPipeline LIGHTING = lightingPipeline(false);
    static final RenderPipeline LIGHTING_TEMPORAL = lightingPipeline(true);
    private final TemporalShadowHistory temporal = new TemporalShadowHistory();
    private final Matrix4f actualProjection = new Matrix4f();
    private boolean projectionObserved;
    static final RenderPipeline OUTPUT = outputPipeline();
    private GpuTexture hdr;
    private GpuTextureView hdrView;
    private GpuBuffer environment;

    boolean prepare(RenderTarget target) {
        if (LightingEnvironment.targetBytes(target.width,target.height) > LightingEnvironment.TARGET_LIMIT
                || RenderSystem.getShaderFog() == null) { close(); return false; }
        var device = RenderSystem.getDevice();
        if (!device.precompilePipeline(LIGHTING,RenderProbe.SHADERS).isValid()
                || !device.precompilePipeline(OUTPUT,RenderProbe.SHADERS).isValid())
            throw new IllegalStateException("Foundation lighting shader compilation failed");
        if (hdr == null || hdr.getWidth(0) != target.width || hdr.getHeight(0) != target.height) {
            releaseBuffers();temporal.close();
            hdr = device.createTexture("VoxelLight linear HDR lighting",GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT,target.width,target.height,1,1);
            hdrView = device.createTextureView(hdr);
            environment = device.createBuffer(() -> "VoxelLight lighting environment",GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,LightingEnvironment.SETTINGS_BYTES);
        }
        return true;
    }
    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,GpuSampler terrainSampler) {
        material.capture(encoder,terrainSampler);
        shadows.updateLighting(encoder,RenderProbe.Mode.FOUNDATION);
        boolean useTemporal = temporal.prepare(output,projectionObserved);
        if(useTemporal && !RenderSystem.getDevice().precompilePipeline(LIGHTING_TEMPORAL,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Temporal lighting shader compilation failed");
        renderCurrent(encoder,output,material,shadows,useTemporal);
        var result = useTemporal ? temporal.resolve(encoder,hdrView,material,shadows,actualProjection) : hdrView;
        display(encoder,output,shadows,result);
    }
    void captureProjection(Matrix4f projection){actualProjection.set(projection);projectionObserved=true;}
    void endFrame(){projectionObserved=false;}
    void setTemporal(boolean enabled){temporal.setEnabled(enabled);}
    void invalidateHistory(){temporal.invalidate();}
    void renderCaptured(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows) {
        renderCurrent(encoder,output,material,shadows,false);
        display(encoder,output,shadows,hdrView);
    }
    void renderCurrent(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,boolean history) {
        var sky = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        var light = LightingEnvironment.sample(shadows.light(),sky.skybox == DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);
        try (var stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(environment.slice(),Std140Builder.onStack(stack,LightingEnvironment.SETTINGS_BYTES)
                    .putVec4(light.directR(),light.directG(),light.directB(),light.directStrength())
                    .putVec4(light.skyR(),light.skyG(),light.skyB(),light.skyStrength()).get());
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = history ? encoder.createRenderPass(lightingDescriptor(hdrView,temporal.input(),output.width,output.height))
                : encoder.createRenderPass(() -> "VoxelLight separated linear lighting",hdrView,Optional.of(new Vector4f(0,0,0,0)))) {
            pass.setPipeline(history ? LIGHTING_TEMPORAL : LIGHTING);
            String[] names = {"MaterialAlbedo","MaterialNormal","MaterialEmission","MaterialDepth"};
            for (int i=0;i<names.length;i++) pass.bindTexture(names[i],material.view(i),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindLighting(pass);
            pass.setUniform("LightingEnvironment",environment);
            pass.draw(3,1,0,0);
        }
    }
    private void display(CommandEncoder encoder,RenderTarget output,ShadowRenderer shadows,GpuTextureView source) {
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(() -> "VoxelLight tone mapping and native fog",output.getColorTextureView(),Optional.empty())) {
            pass.setPipeline(OUTPUT);
            pass.bindTexture("LightingHdr",source,nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindTransform(pass);
            pass.setUniform("Fog",RenderSystem.getShaderFog());
            pass.draw(3,1,0,0);
        }
    }
    static RenderPassDescriptor lightingDescriptor(GpuTextureView hdr,GpuTextureView shadow,int width,int height) {
        return RenderPassDescriptor.create(()->"VoxelLight lighting and current shadow visibility")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(hdr,Optional.of(new Vector4f(0))).withColorAttachment(shadow,Optional.of(new Vector4f(1,0,0,0)));
    }
    String status() { return "lighting=separated linear HDR; native block-light baseline, hdrBytes=" + (hdr == null ? 0 : (long)hdr.getWidth(0)*hdr.getHeight(0)*8) + temporal.status(); }
    @Override public void close() {releaseBuffers();temporal.close();projectionObserved=false;}
    private void releaseBuffers() {
        if (hdrView != null) { hdrView.close(); hdrView=null; }
        if (hdr != null) { hdr.close(); hdr=null; }
        if (environment != null) { environment.close(); environment=null; }
    }
    private static RenderPipeline lightingPipeline(boolean history) {
        var builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",history ? "pipeline/lighting_temporal" : "pipeline/lighting"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","lighting"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneDepth")
                        .withSampler("MaterialAlbedo").withSampler("MaterialNormal").withSampler("MaterialEmission").withSampler("MaterialDepth")
                        .withSampler("ShadowMap").withSampler("MiddleShadowMap").withSampler("FarShadowMap")
                        .withSampler("EntityShadowMap").withSampler("MiddleEntityShadowMap").withSampler("FarEntityShadowMap")
                        .withSampler("VoxelOpacity").withSampler("ShapeBounds")
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("LocalLightSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if(history)builder.withShaderDefine("TEMPORAL_SHADOW").withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));
        return builder.build();
    }
    private static RenderPipeline outputPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/lighting_output"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","lighting_output"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("LightingHdr").withSampler("SceneDepth")
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("Fog",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(ColorTargetState.DEFAULT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
