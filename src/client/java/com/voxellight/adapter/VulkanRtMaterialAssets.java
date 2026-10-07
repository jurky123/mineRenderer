package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.voxellight.rt.vulkan.VulkanRtBuffer;
import com.voxellight.world.WaterSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.util.Optional;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Reload-owned linear GPU atlas/palette view. Never maps or downloads frame images. */
final class VulkanRtMaterialAssets implements AutoCloseable {
    com.voxellight.rt.vulkan.VulkanRtBuffer buffer(){return buffer;}
    float[] lightingSignature(ShadowRenderer shadows){
        var light=shadows.light();var sun=light.direction();var sky=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        var env=com.voxellight.world.LightingEnvironment.polished(light,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);
        float[] value=new float[16];value[0]=sun.x;value[1]=sun.y;value[2]=sun.z;
        value[3]=env.directR()*env.directStrength();value[4]=env.directG()*env.directStrength();value[5]=env.directB()*env.directStrength();
        var held=shadows.rtVirtualLight();System.arraycopy(held,0,value,6,8);
        value[14]=1-sky.rainBrightness;value[15]=Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera()==net.minecraft.world.level.material.FogType.WATER?1:0;
        return RtBenchmarkRunner.freeze("lightingSignature",value);
    }
    private static final RenderPipeline COPY=RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/vulkan_rt_atlas"))
        .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","rt_atlas"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL))
        .withCull(false).build();
    private GpuTexture albedo;
    private GpuTextureView albedoView;
    private VulkanRtBuffer buffer;
    private int[] widths,heights,offsets;
    private int environmentOffset,emitterOffset,flameOffset,dynamicOffset,runtimeOffset;
    private static final int RUNTIME_BYTES=2*1024*1024;
    private long runtimeGeneration=-1,runtimeProposalRevision=-1,runtimeFrame=-1;
    private int runtimeCellX=Integer.MIN_VALUE,runtimeCellY,runtimeCellZ,runtimeBytes,runtimeLights,runtimeSections;
    private long emitterGeneration=-1,albedoVersion=Long.MIN_VALUE;
    private String lightingStatus="";
    String status(){return lightingStatus+", lightRuntimeLights="+runtimeLights+", lightRuntimeSections="+runtimeSections+", lightRuntimeBytes="+runtimeBytes+", lightProposalRevision="+runtimeProposalRevision+", directLighting="+com.voxellight.rt.RtExecutionOptions.direct();}
    private final VulkanRtEnvironmentAssets environment=new VulkanRtEnvironmentAssets();
    VulkanRtBuffer prepare(CommandEncoder encoder,VulkanDevice device,MaterialCapture material,EnvironmentPass weather,ShadowRenderer shadows,com.voxellight.rt.vulkan.VulkanRtScene scene,RtDynamicScene dynamic) {
        var atlas=Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var ids=material.rtAtlas(0);var normal=material.rtAtlas(1);var palette=material.rtAtlas(2);
        if(ids==null||normal==null||palette==null)return null;
        if(buffer==null) {
            if(!RenderSystem.getDevice().precompilePipeline(COPY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan RT albedo copy shader unavailable");
            // Keep the native albedo resolution; IDs and normals have their existing PbrAtlas dimensions.
            widths=new int[]{atlas.getWidth(0),ids.getWidth(0),normal.getWidth(0),palette.getWidth(0)};
            heights=new int[]{atlas.getHeight(0),ids.getHeight(0),normal.getHeight(0),palette.getHeight(0)};
            offsets=new int[4];long bytes=256;
            for(int i=0;i<4;i++){offsets[i]=Math.toIntExact(bytes);bytes=Math.addExact(bytes,Math.multiplyExact((long)widths[i]*heights[i],4));}
            environmentOffset=Math.toIntExact(bytes);bytes+=VulkanRtEnvironmentAssets.BYTES;emitterOffset=Math.toIntExact(bytes);bytes+=8192*64;flameOffset=Math.toIntExact(bytes);bytes+=16*64;dynamicOffset=Math.toIntExact(bytes);bytes+=(long)RtDynamicScene.SIZE*RtDynamicScene.SIZE*4;runtimeOffset=Math.toIntExact(bytes);bytes+=RUNTIME_BYTES;
            if(bytes>256L*1024*1024)throw new IllegalStateException("Vulkan material atlas budget exceeded (256 MiB)");
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
                var properties=org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
                org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(),properties);
                if(bytes>Integer.toUnsignedLong(properties.limits().maxStorageBufferRange()))throw new IllegalStateException("Vulkan material atlas exceeds device storage-buffer range");
            }
            albedo=device.createTexture("VoxelLight Vulkan animated albedo",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,widths[0],heights[0],1,1);
            albedoView=device.createTextureView(albedo);buffer=new VulkanRtBuffer(device,bytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            encoder.copyTextureToBuffer(ids.texture(),buffer,offsets[1],()->{},0);
            encoder.copyTextureToBuffer(normal.texture(),buffer,offsets[2],()->{},0);
            encoder.copyTextureToBuffer(palette.texture(),buffer,offsets[3],()->{},0);
        }
        // Metadata is small CPU control data; all atlas pixels stay on the GPU.
        var header=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        int[] extra={environmentOffset,environmentOffset+256*128*16,environmentOffset+256*128*32,96};
        for(int i=0;i<4;i++)header.putInt(widths[i]).putInt(heights[i]).putInt(offsets[i]).putInt(extra[i]);
        var controls=weather.rtSettings();var lighting=lightingSignature(shadows);
        var surface=RtBenchmarkRunner.freeze("surfaceControls",new float[]{WaterSurface.clock(),controls[2],WaterSurface.waveStrength(),controls[4],controls[0]});
        for(float value:surface)header.putFloat(value);header.putInt(256).putInt(128).putInt((int)lighting[15]);
        header.putFloat(lighting[0]).putFloat(lighting[1]).putFloat(lighting[2]).putFloat(6.793e-5f);
        header.putFloat(lighting[3]*(float)Math.PI).putFloat(lighting[4]*(float)Math.PI).putFloat(lighting[5]*(float)Math.PI).putFloat(0);
        for(int i=6;i<14;i++)header.putFloat(lighting[i]);
        var water=RtBenchmarkRunner.freeze("waterMedium",material.waterMedium());header.putFloat(water[0]).putFloat(water[1]).putFloat(water[2]).putFloat(water[7]);
        header.putInt(124,scene.emitterCount());header.putInt(156,emitterOffset);
        header.putFloat(water[3]).putFloat(water[4]).putFloat(water[5]).putFloat(water[6]).putInt(scene.flameCount()).putInt(flameOffset).putInt(dynamicOffset).putInt(RtDynamicScene.CELL);
        var position=Minecraft.getInstance().gameRenderer.mainCamera().position();int cx=(int)Math.floor(position.x/16),cy=(int)Math.floor(position.y/16),cz=(int)Math.floor(position.z/16);long frame=RenderPassProfile.frameId();
        if(runtimeGeneration!=scene.emitterGeneration()||cx!=runtimeCellX||cy!=runtimeCellY||cz!=runtimeCellZ||runtimeProposalRevision!=scene.proposalRevision()&&frame-runtimeFrame>=64){
            var runtime=com.voxellight.rt.RtLightRuntime.build(com.voxellight.rt.RtLightRuntime.sources(scene.emitterData(),scene.flameData()),position.x,position.y,position.z,scene.proposalFactors());
            if(runtime.remaining()>RUNTIME_BYTES)throw new IllegalStateException("Light Runtime capacity exceeded");
            runtimeBytes=runtime.remaining();runtimeLights=runtime.getInt(4);runtimeSections=runtime.getInt(8);encoder.writeToBuffer(buffer.slice(runtimeOffset,runtimeBytes),runtime);
            runtimeGeneration=scene.emitterGeneration();runtimeProposalRevision=scene.proposalRevision();runtimeFrame=frame;runtimeCellX=cx;runtimeCellY=cy;runtimeCellZ=cz;
        }
        header.putInt(runtimeOffset).putInt(runtimeBytes).putInt(8).putInt(4).putInt(1).putInt((int)runtimeProposalRevision).putInt(com.voxellight.rt.RtLightRuntime.ADAPTIVE_SCALE);header.position(256);header.flip();
        var player=Minecraft.getInstance().player;
        String heldItems=player==null?"none":net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem())+"/"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem());
        lightingStatus=", vulkanRtHeldItems="+heldItems+", vulkanRtHeldPosition="+header.getFloat(128)+"/"+header.getFloat(132)+"/"+header.getFloat(136)+", vulkanRtHeldEnabled="+(header.getFloat(140)>0)+", vulkanRtHeldIntensity="+header.getFloat(144)+"/"+header.getFloat(148)+"/"+header.getFloat(152)+", vulkanRtSunDirection="+lighting[0]+"/"+lighting[1]+"/"+lighting[2];
        encoder.writeToBuffer(buffer.slice(0,256),header);
        if(emitterGeneration!=scene.emitterGeneration()){if(scene.emitterCount()>0)encoder.writeToBuffer(buffer.slice(emitterOffset,scene.emitterCount()*64L),scene.emitterData());if(scene.flameCount()>0)encoder.writeToBuffer(buffer.slice(flameOffset,scene.flameCount()*64L),scene.flameData());emitterGeneration=scene.emitterGeneration();}
        environment.prepare(encoder,device,buffer,environmentOffset,weather,shadows);
        long version=NativeTextureVersions.version(atlas.texture());
        if(albedoVersion!=version){
        try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan animated albedo copy")
            .withRenderArea(new RenderPass.RenderArea(0,0,widths[0],heights[0])).withColorAttachment(albedoView,Optional.empty()))) {
            pass.setPipeline(COPY);pass.bindTexture("Sampler0",atlas,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
        }
        encoder.copyTextureToBuffer(albedo,buffer,offsets[0],()->{},0);albedoVersion=version;
        }
        if(dynamic!=null)dynamic.uploadTextures(encoder,buffer,dynamicOffset);
        return buffer;
    }
    long bytes(){return buffer==null?0:buffer.size();}
    @Override public void close(){environment.close();if(buffer!=null)buffer.close();if(albedoView!=null)albedoView.close();if(albedo!=null)albedo.close();buffer=null;runtimeGeneration=runtimeProposalRevision=-1;runtimeCellX=Integer.MIN_VALUE;runtimeBytes=runtimeLights=runtimeSections=0;emitterGeneration=-1;albedoVersion=Long.MIN_VALUE;albedoView=null;albedo=null;}
}
