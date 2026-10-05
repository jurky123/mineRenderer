package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.util.Optional;

/** Linear HDR running mean, with distinct read/write attachments and no CPU image readback. */
final class VulkanRtAccumulation implements AutoCloseable {
    static final RenderPipeline PIPELINE=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/vulkan_rt_accumulate"))
        .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","vulkan_rt_accumulate"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("CurrentSample").withSampler("History").withUniform("AccumulationSettings",UniformType.UNIFORM_BUFFER).build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).withCull(false).build();
    private final GpuTexture[] textures=new GpuTexture[2];
    private final GpuTextureView[] views=new GpuTextureView[2];
    private GpuBuffer settings;
    private int read,width,height;
    GpuTextureView view(){return views[read];}
    GpuTextureView add(CommandEncoder encoder,GpuTextureView sample,int samples,int w,int h){
        var device=RenderSystem.getDevice();
        if(settings==null||width!=w||height!=h){
            close();width=w;height=h;
            if(!device.precompilePipeline(PIPELINE,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan accumulation shader unavailable");
            for(int i=0;i<2;i++){textures[i]=device.createTexture("VoxelLight stationary HDR "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,w,h,1,1);views[i]=device.createTextureView(textures[i]);}
            settings=device.createBuffer(()->"VoxelLight stationary mean settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
        }
        var data=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());data.putFloat(1f/(samples+1)).putFloat(samples).putFloat(0).putFloat(0).flip();encoder.writeToBuffer(settings.slice(),data);
        int write=1-read;
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_accumulate");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight stationary HDR mean").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(views[write],Optional.empty()))){
            pass.setPipeline(PIPELINE);var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("CurrentSample",sample,sampler);pass.bindTexture("History",views[read],sampler);pass.setUniform("AccumulationSettings",settings);pass.draw(3,1,0,0);
        }
        read=write;return views[read];
    }
    public void close(){for(int i=0;i<2;i++){if(views[i]!=null)views[i].close();if(textures[i]!=null)textures[i].close();views[i]=null;textures[i]=null;}if(settings!=null)settings.close();settings=null;read=0;}
}
