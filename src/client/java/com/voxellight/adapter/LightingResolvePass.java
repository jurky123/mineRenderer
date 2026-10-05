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

/** Owns opaque HDR lighting, AO and directional history; visual effects belong to VisualComposite. */
final class LightingResolvePass implements AutoCloseable {
    static final RenderPipeline LIGHTING = lightingPipeline(false);
    static final RenderPipeline LIGHTING_TEMPORAL = lightingPipeline(true);
    private final WaterSurfaceCapture waterMask=new WaterSurfaceCapture();
    private final SurfaceEffects surfaces=new SurfaceEffects();
    private final MotionFrame motion=new MotionFrame();
    private final EnvironmentPass weather=new EnvironmentPass();
    boolean replacesClouds(){return weather.replacesClouds();}
    void setEnvironment(String option,boolean value){switch(option){case "voxel_clouds"->composite.setVoxelClouds(value);case "sky"->weather.setSky(value);case "clouds"->weather.setClouds(value);case "cloud_shadows"->weather.setCloudShadows(value);case "underwater"->weather.setUnderwater(value);case "caustics"->weather.setCaustics(value);case "rain_ripples"->weather.setRipples(value);default->throw new IllegalArgumentException(option);}}
    private final RtxLightingPass rtx=new RtxLightingPass();
    private final VulkanRtDebugPass vulkanRt=new VulkanRtDebugPass();
    void setVulkanMaterials(){referenceRt(false);rtx.enable(false);pathtrace.setEnabled(false);vulkanRt.enableMaterials();}
    void setVulkanTransportTest(){referenceRt(false);rtx.enable(false);pathtrace.setEnabled(false);vulkanRt.enableTransport();}
    void setVulkanRtPoc(){referenceRt(false);rtx.enable(false);pathtrace.setEnabled(false);vulkanRt.enable(true);}
    void setRtBackend(boolean enabled){if(vulkanRt.enabled())vulkanRt.enable(false);if(!enabled)referenceRt(false);rtx.enable(enabled);}
    void setRtOption(String option,boolean value){rtx.option(option,value);}
    void referenceRt(boolean value){if(value&&vulkanRt.enabled())vulkanRt.enable(false);rtx.reference(value);surfaces.reference(value);}
    boolean referenceActive(){return rtx.referenceEnabled();}
    void referenceSpp(int value){rtx.referenceSpp(value);}
    void referenceReset(){rtx.referenceReset();}
    void fireflyClamp(boolean value){rtx.fireflyClamp(value);}
    void benchmarkRt(){rtx.benchmark();}
    void setRtDebug(int value){rtx.debug(value);}
    void setCloudDebug(int value){composite.setCloudDebug(value);}
    private final PathTracePass pathtrace=new PathTracePass();
    private final VisualComposite composite=new VisualComposite();
    private final AmbientOcclusionPass ao = new AmbientOcclusionPass();
    private final TemporalShadowHistory temporal = new TemporalShadowHistory();
    private final Matrix4f actualProjection = new Matrix4f();
    private boolean projectionObserved,entityPhase;

    private GpuTexture hdr;
    private GpuTextureView hdrView;
    private GpuBuffer environment,pbrSettings;
    private boolean pbrEnabled=true,wetnessEnabled=true;private int pbrDebug;
    void setPbr(boolean value){pbrEnabled=value;temporal.invalidate();}
    void setWetness(boolean value){wetnessEnabled=value;}
    void setPbrDebug(int value){pbrDebug=value;}

    boolean prepare(RenderTarget target) {
        if (LightingEnvironment.targetBytes(target.width,target.height) > LightingEnvironment.TARGET_LIMIT
                || RenderSystem.getShaderFog() == null) { close(); return false; }
        var device = RenderSystem.getDevice();
        if (!device.precompilePipeline(LIGHTING,RenderProbe.SHADERS).isValid()
                || !composite.prepare(target))
            throw new IllegalStateException("Foundation lighting shader compilation failed");
        if (hdr == null || hdr.getWidth(0) != target.width || hdr.getHeight(0) != target.height) {
            releaseBuffers();temporal.close();
            hdr = device.createTexture("VoxelLight linear HDR lighting",GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT,target.width,target.height,1,1);
            hdrView = device.createTextureView(hdr);
            pbrSettings=device.createBuffer(() -> "VoxelLight PBR settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
            environment = device.createBuffer(() -> "VoxelLight lighting environment",GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,LightingEnvironment.SETTINGS_BYTES);
        }
        return true;
    }
    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,GpuSampler terrainSampler) {
        material.capture(encoder,terrainSampler);
        if(composite.polished()&&!ao.debug()&&pbrDebug==0&&(composite.needsMotion()||surfaces.needsMotion()))motion.prepare(encoder,output,material,actualProjection,projectionObserved);else motion.close();
        waterMask.render(encoder,output,material,composite.polished()&&weather.caustics()&&!ao.debug());
        shadows.updateLighting(encoder,RenderProbe.Mode.FOUNDATION);
        ao.render(encoder,output,material,shadows);
        boolean useTemporal = temporal.prepare(output,projectionObserved);
        if(useTemporal && !RenderSystem.getDevice().precompilePipeline(LIGHTING_TEMPORAL,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Temporal lighting shader compilation failed");
        weather.prepare(encoder,composite.polished()&&!ao.debug());
        rtx.trace(encoder,output,material,shadows,actualProjection,projectionObserved,weather);composite.setRtTransmission(rtx.transmission());composite.setRtGuides(rtx.coverage(),rtx.interfaceNormal(),rtx.surfaceKey(),rtx.materialIds());
        renderCurrent(encoder,output,material,shadows,useTemporal);
        var result = useTemporal ? temporal.resolve(encoder,hdrView,material,shadows,actualProjection) : hdrView;
        result=rtx.active()?rtx.composite(encoder,output,material,result):pathtrace.render(encoder,output,material,shadows,result,actualProjection,projectionObserved,pbrSettings);
        surfaces.rtReflections((rtx.ownership()&2)!=0);
        if(composite.polished()&&!ao.debug()&&pbrDebug==0)result=surfaces.render(encoder,output,material,shadows,result,environment,pbrSettings,weather,motion);
        composite.fullReference(rtx.fullReferenceActive());
        composite.render(encoder,output,material,shadows,result,environment,ao,true,weather,motion);

    }
    void displayVulkanRt(RenderTarget output,MaterialCapture material){if(vulkanRt.enabled())vulkanRt.render(RenderSystem.getDevice().createCommandEncoder(),output,actualProjection,projectionObserved,material,weather);}
    void captureProjection(Matrix4f projection){actualProjection.set(projection);projectionObserved=true;}
    void endFrame(){pathtrace.endFrame();motion.endFrame();weather.endFrame();projectionObserved=false;composite.endFrame();}
    void setAmbientOcclusion(boolean enabled,boolean debug){ao.setEnabled(enabled,debug);}
    void setPolished(boolean value){composite.setPolished(value);temporal.invalidate();}
    void setBloom(boolean value){composite.setBloom(value);}
    void setCoverageBlend(boolean value){composite.setCoverageBlend(value);}
    void setAtmosphere(boolean enabled){composite.setAtmosphere(enabled);}
    void setVolumetric(boolean enabled){composite.setVolumetric(enabled);}
    void setVolumeDensity(float density){composite.setVolumeDensity(density);}
    void setForwardStrength(float strength){composite.setForwardStrength(strength);}
    void setAtmosphereDensity(float density){composite.setAtmosphereDensity(density);}
    void setExposure(float ev){composite.setExposure(ev);rtx.referenceExposure(ev);}
    void setWater(boolean value){composite.setWater(value);}
    void setWaterHzb(boolean value){composite.setWaterHzb(value);}
    void setWaterReflections(boolean value){composite.setWaterReflections(value);}
    void setWaterWaves(boolean value){composite.setWaterWaves(value);}
    void setWaveStrength(float value){composite.setWaveStrength(value);}
    void setWaveSpeed(float value){composite.setWaveSpeed(value);}
    void setMaterialReflections(boolean value){surfaces.setReflections(value);}
    void setColorTaa(boolean value){surfaces.setTaa(value);}
    void jitterProjection(Matrix4f matrix,RenderTarget target){if(!rtx.referenceEnabled()&&composite.polished()&&!ao.debug()&&pbrDebug==0)surfaces.jitter(matrix,target);}
    void setVolumeTemporal(boolean value){composite.setVolumeTemporal(value);}
    void setVolumeFilter(boolean value){composite.setVolumeFilter(value);}
    void setQuality(com.voxellight.world.VisualQuality value){rtx.quality(value);composite.setQuality(value);surfaces.setQuality(value);pathtrace.setQuality(value);}
    void prepareWater(RenderTarget target,ShadowRenderer shadows){composite.setRtTransmission(rtx.transmission());composite.setRtGuides(rtx.coverage(),rtx.interfaceNormal(),rtx.surfaceKey(),rtx.materialIds());composite.usePyramid(surfaces.pyramid());composite.prepareWater(target,shadows,environment,ao,weather);}
    boolean bindWater(RenderPass pass){return composite.bindWater(pass);}
    void fullReference(boolean value){if(value&&vulkanRt.enabled())vulkanRt.enable(false);rtx.fullReference(value);surfaces.reference(value);}
    void referenceScale(int value){rtx.referenceScale(value);}
    void setPathTrace(boolean enabled){pathtrace.setEnabled(enabled);}
    void setPathTraceDenoise(boolean enabled){pathtrace.setDenoise(enabled);}
    void setPathTraceFreeze(boolean value){pathtrace.setFreeze(value);}
    void setPathTraceHistory(boolean value){pathtrace.setHistory(value);}
    void setPathTraceRejection(boolean value){pathtrace.setRejectionDebug(value);}
    void setPathTraceUploadDelay(boolean value){pathtrace.setUploadDelay(value);}
    void setPathTraceDebug(boolean enabled){pathtrace.setDebug(enabled);}
    void setTemporal(boolean enabled){temporal.setEnabled(enabled);}
    void invalidateHistory(){temporal.invalidate();}
    void displayFullReference(CommandEncoder encoder,RenderTarget output){rtx.displayFullReference(encoder,output);}
    void renderCaptured(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows) {
        if(vulkanRt.enabled())return;
        entityPhase=true;
        try{renderCurrent(encoder,output,material,shadows,false);}finally{entityPhase=false;}
        var result=pathtrace.render(encoder,output,material,shadows,hdrView,actualProjection,projectionObserved,pbrSettings);
        composite.fullReference(rtx.fullReferenceActive());
        composite.render(encoder,output,material,shadows,result,environment,ao,false,weather,motion);
    }
    void renderCurrent(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,boolean history) {
        weather.prepare(encoder,composite.polished()&&!ao.debug());
        var sky = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        var light = composite.polished() ? LightingEnvironment.polished(shadows.light(),sky.skybox == DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness)
                : LightingEnvironment.sample(shadows.light(),sky.skybox == DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);
        try (var stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(pbrSettings.slice(),Std140Builder.onStack(stack,16).putVec4(pbrEnabled&&composite.polished()?1:0,wetnessEnabled&&sky.skybox==DimensionType.Skybox.OVERWORLD?1-sky.rainBrightness:0,pbrDebug,entityPhase?0:rtx.ownership()).get());
            encoder.writeToBuffer(environment.slice(),Std140Builder.onStack(stack,LightingEnvironment.SETTINGS_BYTES)
                    .putVec4(light.directR(),light.directG(),light.directB(),light.directStrength())
                    .putVec4(light.skyR(),light.skyG(),light.skyB(),light.skyStrength())
                    .putVec4(light.horizonR(),light.horizonG(),light.horizonB(),light.lowerHemisphere()).get());
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var profile=RenderPassProfile.begin(encoder,"lighting"); var pass = history ? encoder.createRenderPass(lightingDescriptor(hdrView,temporal.input(),output.width,output.height))
                : encoder.createRenderPass(() -> "VoxelLight separated linear lighting",hdrView,Optional.of(new Vector4f(0,0,0,0)))) {
            pass.setPipeline(history ? LIGHTING_TEMPORAL : LIGHTING);
            String[] names = {"MaterialAlbedo","MaterialNormal","MaterialEmission","MaterialDepth"};
            for (int i=0;i<names.length;i++) pass.bindTexture(names[i],material.view(i),nearest);
            pass.bindTexture("MaterialPbr",material.view(4),nearest);
            pass.bindTexture("RtSunVisibility",rtx.sunVisibility()==null?material.view(1):rtx.sunVisibility(),nearest);
            pass.bindTexture("RtSurfaceKey",rtx.surfaceKey()==null?material.view(1):rtx.surfaceKey(),nearest);
            pass.bindTexture("RtGeometryNormal",rtx.interfaceNormal()==null?material.view(1):rtx.interfaceNormal(),nearest);
            pass.bindTexture("RtCoverage",rtx.coverage()==null?material.view(1):rtx.coverage(),nearest);
            pass.bindTexture("MaterialTable",material.materialTable(),nearest);
            pass.setUniform("PbrSettings",pbrSettings);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindLighting(pass);
            ao.bind(pass);
            pass.setUniform("LightingEnvironment",environment);weather.bind(pass);waterMask.bind(pass);
            pass.draw(3,1,0,0);
        }
    }
    static RenderPassDescriptor lightingDescriptor(GpuTextureView hdr,GpuTextureView shadow,int width,int height) {
        return RenderPassDescriptor.create(()->"VoxelLight lighting and current shadow visibility")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(hdr,Optional.of(new Vector4f(0))).withColorAttachment(shadow,Optional.of(new Vector4f(1,0,0,0)));
    }
    String status() { return "lighting=separated linear HDR; native block-light baseline, hdrBytes=" + (hdr == null ? 0 : (long)hdr.getWidth(0)*hdr.getHeight(0)*8) + ", pbr="+pbrEnabled+", wetness="+wetnessEnabled+", pbrDebug="+pbrDebug + temporal.status() + ao.status() +composite.status()+weather.status()+motion.status()+surfaces.status()+waterMask.status()+pathtrace.status()+rtx.status()+vulkanRt.status(); }
    @Override public void close() {releaseBuffers();temporal.close();ao.close();composite.close();motion.close();surfaces.close();waterMask.close();weather.close();pathtrace.close();rtx.close();vulkanRt.close();projectionObserved=false;}
    private void releaseBuffers() {
        if (hdrView != null) { hdrView.close(); hdrView=null; }
        if (hdr != null) { hdr.close(); hdr=null; }
        if(pbrSettings!=null){pbrSettings.close();pbrSettings=null;}
        if (environment != null) { environment.close(); environment=null; }
    }
    private static RenderPipeline lightingPipeline(boolean history) {
        var builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",history ? "pipeline/lighting_temporal" : "pipeline/lighting"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","lighting"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneDepth")
                        .withSampler("RtSunVisibility").withSampler("RtGeometryNormal").withSampler("RtCoverage").withSampler("RtSurfaceKey").withSampler("MaterialPbr").withSampler("MaterialTable").withUniform("PbrSettings",UniformType.UNIFORM_BUFFER).withSampler("MaterialAlbedo").withSampler("MaterialNormal").withSampler("MaterialEmission").withSampler("MaterialDepth")
                        .withSampler("ShadowMap").withSampler("MiddleShadowMap").withSampler("FarShadowMap").withSampler("NextShadowMap").withSampler("MiddleNextShadowMap").withSampler("FarNextShadowMap")
                        .withSampler("EntityShadowMap").withSampler("MiddleEntityShadowMap").withSampler("FarEntityShadowMap")
                        .withSampler("WaterSurfaceDepth").withSampler("VoxelOpacity").withSampler("ShapeBounds").withSampler("AmbientVisibility")
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("LocalLightSettings",UniformType.UNIFORM_BUFFER).withUniform("EnvironmentSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER).withUniform("AoSettings",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if(history)builder.withShaderDefine("TEMPORAL_SHADOW").withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));
        return builder.build();
    }
}
