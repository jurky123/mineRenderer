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
import com.voxellight.world.VisualPolish;
import com.voxellight.world.Atmosphere;
import net.minecraft.world.level.material.FogType;
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
    private final EmissiveBloom bloom = new EmissiveBloom();
    private boolean polished=true, bloomEnabled=true, coverageBlend=true;
    private float exposureEv=VisualPolish.DEFAULT_EV;
    private GpuBuffer visualSettings, atmosphereSettings;
    private boolean atmosphereEnabled=true, atmosphereActive;
    private float atmosphereDensity=Atmosphere.DEFAULT_DENSITY;
    private final AmbientOcclusionPass ao = new AmbientOcclusionPass();
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
            visualSettings = device.createBuffer(() -> "VoxelLight visual settings",GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,VisualPolish.SETTINGS_BYTES);
            atmosphereSettings=device.createBuffer(()->"VoxelLight atmosphere settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,Atmosphere.SETTINGS_BYTES);
            environment = device.createBuffer(() -> "VoxelLight lighting environment",GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,LightingEnvironment.SETTINGS_BYTES);
        }
        return true;
    }
    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,GpuSampler terrainSampler) {
        material.capture(encoder,terrainSampler);
        shadows.updateLighting(encoder,RenderProbe.Mode.FOUNDATION);
        ao.render(encoder,output,material,shadows);
        bloom.render(encoder,output,material,polished && bloomEnabled);
        boolean useTemporal = temporal.prepare(output,projectionObserved);
        if(useTemporal && !RenderSystem.getDevice().precompilePipeline(LIGHTING_TEMPORAL,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Temporal lighting shader compilation failed");
        renderCurrent(encoder,output,material,shadows,useTemporal);
        var result = useTemporal ? temporal.resolve(encoder,hdrView,material,shadows,actualProjection) : hdrView;
        display(encoder,output,shadows,material,result);
    }
    void captureProjection(Matrix4f projection){actualProjection.set(projection);projectionObserved=true;}
    void endFrame(){projectionObserved=false;}
    void setAmbientOcclusion(boolean enabled,boolean debug){ao.setEnabled(enabled,debug);}
    void setPolished(boolean value){polished=value;temporal.invalidate();}
    void setBloom(boolean value){bloomEnabled=value;}
    void setCoverageBlend(boolean value){coverageBlend=value;}
    void setAtmosphere(boolean enabled){atmosphereEnabled=enabled;}
    void setAtmosphereDensity(float density){atmosphereDensity=Atmosphere.density(density);}
    void setExposure(float ev){VisualPolish.exposure(ev);exposureEv=ev;}
    void setTemporal(boolean enabled){temporal.setEnabled(enabled);}
    void invalidateHistory(){temporal.invalidate();}
    void renderCaptured(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows) {
        renderCurrent(encoder,output,material,shadows,false);
        display(encoder,output,shadows,material,hdrView);
    }
    void renderCurrent(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,boolean history) {
        var sky = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        var light = polished ? LightingEnvironment.polished(shadows.light(),sky.skybox == DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness)
                : LightingEnvironment.sample(shadows.light(),sky.skybox == DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);
        try (var stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(environment.slice(),Std140Builder.onStack(stack,LightingEnvironment.SETTINGS_BYTES)
                    .putVec4(light.directR(),light.directG(),light.directB(),light.directStrength())
                    .putVec4(light.skyR(),light.skyG(),light.skyB(),light.skyStrength())
                    .putVec4(light.horizonR(),light.horizonG(),light.horizonB(),light.lowerHemisphere()).get());
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = history ? encoder.createRenderPass(lightingDescriptor(hdrView,temporal.input(),output.width,output.height))
                : encoder.createRenderPass(() -> "VoxelLight separated linear lighting",hdrView,Optional.of(new Vector4f(0,0,0,0)))) {
            pass.setPipeline(history ? LIGHTING_TEMPORAL : LIGHTING);
            String[] names = {"MaterialAlbedo","MaterialNormal","MaterialEmission","MaterialDepth"};
            for (int i=0;i<names.length;i++) pass.bindTexture(names[i],material.view(i),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindLighting(pass);
            ao.bind(pass);
            pass.setUniform("LightingEnvironment",environment);
            pass.draw(3,1,0,0);
        }
    }
    private void display(CommandEncoder encoder,RenderTarget output,ShadowRenderer shadows,MaterialCapture material,GpuTextureView source) {
        var mc=Minecraft.getInstance();
        var sky=mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        atmosphereActive=polished && atmosphereEnabled && mc.level!=null && sky.skybox==DimensionType.Skybox.OVERWORLD
                && mc.gameRenderer.mainCamera().getFluidInCamera()==FogType.NONE;
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(atmosphereSettings.slice(),Std140Builder.onStack(stack,Atmosphere.SETTINGS_BYTES)
                    .putVec4(Atmosphere.weatherDensity(atmosphereDensity,sky.rainBrightness),
                            mc.level==null?0:(float)(mc.gameRenderer.mainCamera().position().y-mc.level.getSeaLevel()),atmosphereActive?1:0,Atmosphere.MAX_DISTANCE).get());
            encoder.writeToBuffer(visualSettings.slice(),Std140Builder.onStack(stack,VisualPolish.SETTINGS_BYTES)
                    .putVec4(polished?VisualPolish.exposure(exposureEv):1,polished?1:0,polished && bloomEnabled?VisualPolish.BLOOM_STRENGTH:0,polished && coverageBlend?1:0)
                    .putVec4(VisualPolish.FADE_START,VisualPolish.FADE_END,0,0).get());
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(() -> "VoxelLight tone mapping and native fog",output.getColorTextureView(),Optional.empty())) {
            pass.setPipeline(OUTPUT);
            pass.bindTexture("LightingHdr",source,nearest);
            bloom.bind(pass);
            pass.setUniform("VisualSettings",visualSettings);
            pass.setUniform("AtmosphereSettings",atmosphereSettings);
            pass.setUniform("LightingEnvironment",environment);
            pass.bindTexture("MaterialEmission",material.view(2),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindTransform(pass);
            pass.setUniform("Fog",RenderSystem.getShaderFog());
            ao.bindSettings(pass);
            pass.draw(3,1,0,0);
        }
    }
    static RenderPassDescriptor lightingDescriptor(GpuTextureView hdr,GpuTextureView shadow,int width,int height) {
        return RenderPassDescriptor.create(()->"VoxelLight lighting and current shadow visibility")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(hdr,Optional.of(new Vector4f(0))).withColorAttachment(shadow,Optional.of(new Vector4f(1,0,0,0)));
    }
    String status() { return "lighting=separated linear HDR; native block-light baseline, hdrBytes=" + (hdr == null ? 0 : (long)hdr.getWidth(0)*hdr.getHeight(0)*8) + temporal.status() + ao.status() + ", look="+(polished?"polished":"reference")+", exposureEV="+exposureEv+", coverageBlend="+(polished && coverageBlend)+bloom.status()+", atmosphere="+(atmosphereActive?"analytic aerial perspective":"off/native")+", atmosphereDensity="+atmosphereDensity; }
    @Override public void close() {releaseBuffers();temporal.close();ao.close();bloom.close();projectionObserved=false;}
    private void releaseBuffers() {
        if (hdrView != null) { hdrView.close(); hdrView=null; }
        if (hdr != null) { hdr.close(); hdr=null; }
        if(atmosphereSettings!=null){atmosphereSettings.close();atmosphereSettings=null;}
        atmosphereActive=false;
        if (visualSettings != null) {visualSettings.close();visualSettings=null;}
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
                        .withSampler("VoxelOpacity").withSampler("ShapeBounds").withSampler("AmbientVisibility")
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("LocalLightSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER).withUniform("AoSettings",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if(history)builder.withShaderDefine("TEMPORAL_SHADOW").withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));
        return builder.build();
    }
    private static RenderPipeline outputPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/lighting_output"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","lighting_output"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("LightingHdr").withSampler("SceneDepth").withSampler("EmissiveBloom").withSampler("MaterialEmission").withUniform("VisualSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("AtmosphereSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER)
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("Fog",UniformType.UNIFORM_BUFFER).withUniform("AoSettings",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
