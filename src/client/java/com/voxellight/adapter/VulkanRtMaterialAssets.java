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
        return value;
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
    private int environmentOffset,emitterOffset;
    private long emitterGeneration=-1;
    private String lightingStatus="";
    String status(){return lightingStatus;}
    private final VulkanRtEnvironmentAssets environment=new VulkanRtEnvironmentAssets();
    VulkanRtBuffer prepare(CommandEncoder encoder,VulkanDevice device,MaterialCapture material,EnvironmentPass weather,ShadowRenderer shadows,com.voxellight.rt.vulkan.VulkanRtScene scene) {
        var atlas=Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var ids=material.rtAtlas(0);var normal=material.rtAtlas(1);var palette=material.rtAtlas(2);
        if(ids==null||normal==null||palette==null)return null;
        if(buffer==null) {
            if(!RenderSystem.getDevice().precompilePipeline(COPY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan RT albedo copy shader unavailable");
            // Keep the native albedo resolution; IDs and normals have their existing PbrAtlas dimensions.
            widths=new int[]{atlas.getWidth(0),ids.getWidth(0),normal.getWidth(0),palette.getWidth(0)};
            heights=new int[]{atlas.getHeight(0),ids.getHeight(0),normal.getHeight(0),palette.getHeight(0)};
            offsets=new int[4];long bytes=192;
            for(int i=0;i<4;i++){offsets[i]=Math.toIntExact(bytes);bytes=Math.addExact(bytes,Math.multiplyExact((long)widths[i]*heights[i],4));}
            environmentOffset=Math.toIntExact(bytes);bytes+=VulkanRtEnvironmentAssets.BYTES;emitterOffset=Math.toIntExact(bytes);bytes+=8192*64;
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
        var header=ByteBuffer.allocateDirect(192).order(ByteOrder.LITTLE_ENDIAN);
        int[] extra={environmentOffset,environmentOffset+256*128*16,environmentOffset+256*128*32,96};
        for(int i=0;i<4;i++)header.putInt(widths[i]).putInt(heights[i]).putInt(offsets[i]).putInt(extra[i]);
        var controls=weather.rtSettings();header.putFloat(WaterSurface.clock()).putFloat(controls[2]).putFloat(WaterSurface.waveStrength()).putFloat(controls[4])
            .putFloat(controls[0]).putInt(256).putInt(128).putInt(Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera()==net.minecraft.world.level.material.FogType.WATER?1:0);
        var sky=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;var light=shadows.light();
        var env=com.voxellight.world.LightingEnvironment.polished(light,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);var sun=light.direction();
        header.putFloat(sun.x).putFloat(sun.y).putFloat(sun.z).putFloat(6.793e-5f);
        header.putFloat(env.directR()*env.directStrength()*(float)Math.PI).putFloat(env.directG()*env.directStrength()*(float)Math.PI).putFloat(env.directB()*env.directStrength()*(float)Math.PI).putFloat(0);
        for(float value:shadows.rtVirtualLight())header.putFloat(value);
        var water=material.waterMedium();header.putFloat(water[0]).putFloat(water[1]).putFloat(water[2]).putFloat(water[7]);
        header.putInt(124,scene.emitterCount());header.putInt(156,emitterOffset);
        header.putFloat(water[3]).putFloat(water[4]).putFloat(water[5]).putFloat(water[6]).flip();
        lightingStatus=", vulkanRtHeldEnabled="+(header.getFloat(140)>0)+", vulkanRtHeldIntensity="+header.getFloat(144)+"/"+header.getFloat(148)+"/"+header.getFloat(152)+", vulkanRtSunDirection="+sun.x+"/"+sun.y+"/"+sun.z;
        encoder.writeToBuffer(buffer.slice(0,192),header);
        if(emitterGeneration!=scene.generation()){if(scene.emitterCount()>0)encoder.writeToBuffer(buffer.slice(emitterOffset,scene.emitterCount()*64L),scene.emitterData());emitterGeneration=scene.generation();}
        environment.prepare(encoder,device,buffer,environmentOffset,weather,shadows);
        try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan animated albedo copy")
            .withRenderArea(new RenderPass.RenderArea(0,0,widths[0],heights[0])).withColorAttachment(albedoView,Optional.empty()))) {
            pass.setPipeline(COPY);pass.bindTexture("Sampler0",atlas,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
        }
        encoder.copyTextureToBuffer(albedo,buffer,offsets[0],()->{},0);
        return buffer;
    }
    long bytes(){return buffer==null?0:buffer.size();}
    @Override public void close(){environment.close();if(buffer!=null)buffer.close();if(albedoView!=null)albedoView.close();if(albedo!=null)albedo.close();buffer=null;emitterGeneration=-1;albedoView=null;albedo=null;}
}
