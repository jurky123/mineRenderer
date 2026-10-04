package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.VisualPolish;
import com.voxellight.world.Atmosphere;
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Owns visual composition only: atmosphere, bloom, display mapping and water background. */
final class VisualComposite implements AutoCloseable {
    static final RenderPipeline OUTPUT=outputPipeline();
    private final VoxelCloudPass clouds=new VoxelCloudPass();
    void setVoxelClouds(boolean value){clouds.enabled(value);}
    void setCloudDebug(int value){clouds.debug(value);}
    private final EmissiveBloom bloom=new EmissiveBloom();
    private final WaterPass water=new WaterPass();
    private final VolumetricPass volumetric=new VolumetricPass();
    private boolean volumetricEnabled=true;
    private com.voxellight.world.VisualQuality quality=com.voxellight.world.VisualQuality.BALANCED;
    private boolean coverageBlendActive;
    private boolean polished=true,bloomEnabled=true,coverageBlend=true,atmosphereEnabled=true,atmosphereActive,waterEnabled=true;
    private float exposureEv=VisualPolish.DEFAULT_EV,atmosphereDensity=Atmosphere.DEFAULT_DENSITY,volumeDensity=Atmosphere.DEFAULT_VOLUME_DENSITY,forwardStrength=Atmosphere.DEFAULT_FORWARD_STRENGTH;
    private GpuBuffer visualSettings,atmosphereSettings;
    boolean prepare(RenderTarget target) {
        var device=RenderSystem.getDevice();
        if(!device.precompilePipeline(OUTPUT,RenderProbe.SHADERS).isValid())return false;
        if(visualSettings==null) {
            visualSettings=device.createBuffer(()->"VoxelLight visual settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,VisualPolish.SETTINGS_BYTES);
            atmosphereSettings=device.createBuffer(()->"VoxelLight atmosphere settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,Atmosphere.SETTINGS_BYTES);
        }
        return true;
    }
    boolean polished(){return polished;}
    boolean needsMotion(){return polished&&(clouds.enabled()||volumetricEnabled&&volumetric.needsMotion());}
    void setPolished(boolean value){polished=value;}
    void setBloom(boolean value){bloomEnabled=value;}
    void setCoverageBlend(boolean value){coverageBlend=value;}
    void setAtmosphere(boolean value){atmosphereEnabled=value;}
    void setVolumetric(boolean value){volumetricEnabled=value;}
    void setAtmosphereDensity(float value){atmosphereDensity=Atmosphere.density(value);}
    void setVolumeDensity(float value){volumeDensity=Atmosphere.density(value);}
    void setForwardStrength(float value){if(!Float.isFinite(value)||value<0||value>4)throw new IllegalArgumentException("Forward scattering must be 0 to 4");forwardStrength=value;}
    void setExposure(float value){VisualPolish.exposure(value);exposureEv=value;}
    void setWater(boolean value){waterEnabled=value;if(!value)water.close();}
    void setWaterHzb(boolean value){water.setHzb(value);}
    void setWaterReflections(boolean value){water.setReflections(value);}
    void setWaterWaves(boolean value){water.setWaves(value);}
    void setWaveStrength(float value){water.setWaveStrength(value);}
    void setWaveSpeed(float value){water.setWaveSpeed(value);}
    void setVolumeTemporal(boolean value){volumetric.setTemporal(value);}
    void setVolumeFilter(boolean value){volumetric.setFiltered(value);}
    void setQuality(com.voxellight.world.VisualQuality value){quality=value;water.setQuality(value);volumetric.setQuality(value);}

    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,GpuTextureView source,GpuBuffer environment,AmbientOcclusionPass ao,boolean terrain,EnvironmentPass weather,MotionFrame motion) {
        if(terrain)bloom.render(encoder,output,material,polished && bloomEnabled);
        water.capture(encoder,output,source,shadows,terrain,polished && (waterEnabled||rtTransmission!=null) && !ao.debug());
        display(encoder,output,shadows,material,source,environment,ao,weather,motion,terrain);
    }
    private GpuTextureView rtTransmission;
    void setRtGuides(GpuTextureView p,GpuTextureView n,GpuTextureView key,GpuTextureView ids){water.setRtGuides(p,n,key,ids);}
    void setRtTransmission(GpuTextureView view){rtTransmission=view;water.setRtTransmission(view);}
    void prepareWater(RenderTarget target,ShadowRenderer shadows,GpuBuffer environment,AmbientOcclusionPass ao,EnvironmentPass weather) {
        water.prepareTranslucent(target,shadows,visualSettings,atmosphereSettings,environment,weather.settings(),bloom,polished && (waterEnabled||rtTransmission!=null) && !ao.debug());
    }
    void usePyramid(DepthPyramid shared){water.usePyramid(shared);}
    boolean bindWater(RenderPass pass){return water.bind(pass);}
    void endFrame(){water.endFrame();}
    String status(){return ", look="+(polished?"polished":"reference")+", quality="+quality.name().toLowerCase(java.util.Locale.ROOT)+", exposureEV="+exposureEv+", coverageBlend="+coverageBlendActive+bloom.status()+", atmosphere="+(atmosphereActive?(volumetric.active()?"shadowed opaque medium; analytic water":"analytic aerial perspective"):"off/native")+", atmosphereDensity="+atmosphereDensity+", volumeDensity="+volumeDensity+", forwardScatter="+forwardStrength+volumetric.status()+clouds.status()+water.status();}
    @Override public void close(){water.close();clouds.close();bloom.close();volumetric.close();if(visualSettings!=null){visualSettings.close();visualSettings=null;}if(atmosphereSettings!=null){atmosphereSettings.close();atmosphereSettings=null;}atmosphereActive=false;}
    private void display(CommandEncoder encoder,RenderTarget output,ShadowRenderer shadows,MaterialCapture material,GpuTextureView source,GpuBuffer environment,AmbientOcclusionPass ao,EnvironmentPass weather,MotionFrame motion,boolean terrain) {
        coverageBlendActive=polished && coverageBlend && !material.nativeTerrain();
        var mc=Minecraft.getInstance();
        var sky=mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        atmosphereActive=polished && atmosphereEnabled && mc.level!=null && sky.skybox==DimensionType.Skybox.OVERWORLD
                && mc.gameRenderer.mainCamera().getFluidInCamera()==FogType.NONE;
        if(terrain)clouds.render(encoder,output,shadows,weather.settings(),environment,motion);
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(atmosphereSettings.slice(),Std140Builder.onStack(stack,Atmosphere.SETTINGS_BYTES)
                    .putVec4(Atmosphere.weatherDensity(atmosphereDensity,sky.rainBrightness),
                            mc.level==null?0:(float)(mc.gameRenderer.mainCamera().position().y-mc.level.getSeaLevel()),atmosphereActive?1:0,Atmosphere.MAX_DISTANCE)
                    .putVec4(Atmosphere.weatherDensity(volumeDensity,sky.rainBrightness),forwardStrength,clouds.active()?1:0,rtTransmission==null?0:1).get());
            encoder.writeToBuffer(visualSettings.slice(),Std140Builder.onStack(stack,VisualPolish.SETTINGS_BYTES)
                    .putVec4(polished?VisualPolish.exposure(exposureEv):1,polished?1:0,polished && bloomEnabled?VisualPolish.BLOOM_STRENGTH:0,coverageBlendActive?1:0)
                    .putVec4(VisualPolish.FADE_START,VisualPolish.FADE_END,0,0).get());
        }
        if(terrain)volumetric.render(encoder,output,material,shadows,atmosphereSettings,environment,weather,motion,(atmosphereActive && (atmosphereDensity>0||volumeDensity>0) || weather.submerged()) && volumetricEnabled && !ao.debug());
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var profile = RenderPassProfile.begin(encoder,"tone_composite"); var pass = encoder.createRenderPass(() -> "VoxelLight tone mapping and native fog",output.getColorTextureView(),Optional.empty())) {
            pass.setPipeline(OUTPUT);
            pass.bindTexture("LightingHdr",source,nearest);clouds.bind(pass,source);pass.bindTexture("RtTransmissionScene",rtTransmission==null?source:rtTransmission,nearest);
            bloom.bind(pass);
            volumetric.bind(pass);
            pass.setUniform("VisualSettings",visualSettings);
            pass.setUniform("AtmosphereSettings",atmosphereSettings);
            pass.setUniform("LightingEnvironment",environment);weather.bind(pass);
            pass.bindTexture("MaterialEmission",material.view(2),nearest);
            pass.bindTexture("MaterialNormal",material.view(1),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindTransform(pass);
            pass.setUniform("Fog",RenderSystem.getShaderFog());
            ao.bindSettings(pass);
            pass.draw(3,1,0,0);weather.composed();
        }
    }
    private static RenderPipeline outputPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/lighting_output"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","lighting_output"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("RtTransmissionScene").withSampler("VoxelCloud").withSampler("LightingHdr").withSampler("SceneDepth").withSampler("EmissiveBloom").withSampler("MaterialEmission").withSampler("MaterialNormal").withSampler("VolumetricScatter").withUniform("VolumetricSettings",UniformType.UNIFORM_BUFFER).withUniform("VisualSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("AtmosphereSettings",UniformType.UNIFORM_BUFFER).withUniform("EnvironmentSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER)
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER)
                        .withUniform("Fog",UniformType.UNIFORM_BUFFER).withUniform("AoSettings",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
