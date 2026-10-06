package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Output-resolution HDR history with RT-surface reprojection, independent of the denoiser. */
final class RtTemporalUpscale implements AutoCloseable {
    static final int HISTORY_USAGE=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_DST;
    private static final String[] INPUTS={"Noisy","Previous","Albedo","Normal","Position","PreviousAlbedo","PreviousNormal","PreviousPosition","MotionPosition","PreviousMotionPosition"};
    private static final RenderPipeline PIPELINE=create();
    private static RenderPipeline create(){
        var bindings=BindGroupLayout.builder();for(var input:INPUTS)bindings.withSampler(input);
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/rt_temporal_upscale"))
            .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","rt_temporal_upscale"))
            .withBindGroupLayout(bindings.withUniform("ReconstructionSettings",UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).withCull(false).build();
    }
    private final GpuTexture[] textures=new GpuTexture[2];
    private final GpuTextureView[] views=new GpuTextureView[2];
    private int width,height,read;
    private boolean valid;
    GpuTextureView resolve(CommandEncoder encoder,GpuTextureView noisy,GpuTextureView[] current,GpuTextureView[] previous,GpuBuffer settings,int w,int h){
        var device=RenderSystem.getDevice();
        if(width!=w||height!=h||textures[0]==null){
            close();width=w;height=h;
            if(!device.precompilePipeline(PIPELINE,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Temporal upscaler unavailable");
            for(int i=0;i<2;i++){textures[i]=device.createTexture("VoxelLight output HDR temporal history",HISTORY_USAGE,GpuFormat.RGBA32_FLOAT,w,h,1,1);views[i]=device.createTextureView(textures[i]);}
        }
        int write=1-read;
        // Cleared alpha prevents reading uninitialized history on first use or output resize.
        if(!valid)encoder.clearColorTexture(textures[read],new org.joml.Vector4f(0));
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_temporal_upscale");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight temporal output reconstruction").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(views[write],Optional.empty()))){
            pass.setPipeline(PIPELINE);
            GpuTextureView[] inputs={noisy,views[read],current[0],current[1],current[2],previous[0],previous[1],previous[2],current[3],previous[3]};
            for(int i=0;i<inputs.length;i++)pass.bindTexture(INPUTS[i],inputs[i],RenderSystem.getSamplerCache().getClampToEdge(i==1?FilterMode.LINEAR:FilterMode.NEAREST));
            pass.setUniform("ReconstructionSettings",settings);pass.draw(3,1,0,0);
        }
        read=write;valid=true;return views[write];
    }
    long bytes(){return (long)width*height*32;}
    @Override public void close(){for(int i=0;i<2;i++){if(views[i]!=null)views[i].close();if(textures[i]!=null)textures[i].close();views[i]=null;textures[i]=null;}width=height=read=0;valid=false;}
}
