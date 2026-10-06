package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.rt.vulkan.VulkanRtContext;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.nio.*;
import java.util.Optional;

/** Moving-camera reconstruction. History is validated using RT geometry, never raster visibility. */
final class RtReconstruction implements AutoCloseable {
    private static final String[] INPUTS={"Noisy","Previous","Albedo","Normal","Position","PreviousAlbedo","PreviousNormal","PreviousPosition"};
    private static RenderPipeline pipeline(String shader){
        var bindings=BindGroupLayout.builder();for(String name:INPUTS)bindings.withSampler(name);
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/"+shader))
            .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",shader))
            .withBindGroupLayout(bindings.withUniform("ReconstructionSettings",UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).withCull(false);
        if(shader.equals("rt_denoiser_guides"))for(int i=1;i<3;i++)builder.withColorTargetState(i,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL));
        return builder.build();
    }
    private static final RenderPipeline PIPELINE=pipeline("rt_reconstruct");
    private static final RenderPipeline GUIDES=pipeline("rt_denoiser_guides");
    private com.voxellight.nvidia.OptixReconstruction optix;
    private final GpuTexture[] denoiserGuides=new GpuTexture[3];
    private final GpuTextureView[] denoiserViews=new GpuTextureView[3];
    private GpuTexture denoised;
    private GpuTextureView denoisedView;
    private boolean useOptix=true;
    private String backend="Vulkan temporal/spatial";
    void optix(boolean value){close();useOptix=value;}
    String status(){return backend;}
    private final GpuTexture[][] guides=new GpuTexture[2][3];
    private final GpuTextureView[][] guideViews=new GpuTextureView[2][3];
    private final GpuTexture[] colors=new GpuTexture[2];
    private final GpuTextureView[] colorViews=new GpuTextureView[2];
    private GpuBuffer settings;
    private final Matrix4f previousClip=new Matrix4f();
    private double previousX,previousY,previousZ;
    private long generation=-1;
    private int read,width,height;
    private boolean valid,reactive;
    private float[] lighting;
    void lighting(float[] value){
        reactive=false;
        if(lighting!=null){
            for(int i=9;i<14;i++)if(Math.abs(value[i]-lighting[i])>1e-4f)valid=false;
            if(value[9]>0)for(int i=6;i<9;i++)reactive|=Math.abs(value[i]-lighting[i])>.02f;
            for(int i=3;i<6;i++)if(Math.abs(value[i]-lighting[i])>Math.max(.002f,Math.abs(lighting[i])*.05f))valid=false;
            if(Math.abs(value[14]-lighting[14])>.02f||value[15]!=lighting[15])valid=false;
        }
        lighting=value.clone();
    }
    GpuTextureView resolve(CommandEncoder encoder,VulkanRtContext context,GpuTextureView noisy,Matrix4f clip,double x,double y,double z,long epoch,int w,int h,Matrix4f viewRotation){
        var device=RenderSystem.getDevice();
        if(settings==null||width!=w||height!=h){
            close();width=w;height=h;
            if(!device.precompilePipeline(PIPELINE,RenderProbe.SHADERS).isValid())throw new IllegalStateException("RT reconstruction shader unavailable");
            for(int bank=0;bank<2;bank++){
                colors[bank]=device.createTexture("VoxelLight realtime HDR history",GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.RGBA32_FLOAT,w,h,1,1);colorViews[bank]=device.createTextureView(colors[bank]);
                for(int plane=0;plane<3;plane++){guides[bank][plane]=device.createTexture("VoxelLight RT guide "+plane,GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA32_FLOAT,w,h,1,1);guideViews[bank][plane]=device.createTextureView(guides[bank][plane]);}
            }
            settings=device.createBuffer(()->"VoxelLight RT reprojection settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,160);
        }
        int write=1-read;for(int plane=0;plane<3;plane++)context.copyGuide(encoder,plane,guides[write][plane]);
        var data=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());previousClip.get(0,data);data.position(64).putFloat((float)previousX).putFloat((float)previousY).putFloat((float)previousZ).putFloat(valid&&generation==epoch?1:0).putFloat(w).putFloat(h).putFloat(reactive?4:32).putFloat(0);viewRotation.get(96,data);data.position(160).flip();encoder.writeToBuffer(settings.slice(),data);
        GpuTextureView result=null;
        if(useOptix&&optix==null&&!backend.startsWith("fallback")){
            try{
                if(!device.precompilePipeline(GUIDES,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Denoiser guide pipeline unavailable");
                for(int i=0;i<3;i++){denoiserGuides[i]=device.createTexture("VoxelLight denoiser guide "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA32_FLOAT,w,h,1,1);denoiserViews[i]=device.createTextureView(denoiserGuides[i]);}
                denoised=device.createTexture("VoxelLight OptiX HDR beauty",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,w,h,1,1);denoisedView=device.createTextureView(denoised);
                optix=new com.voxellight.nvidia.OptixReconstruction(((GpuBackendAccess)device).voxellight$backend() instanceof com.mojang.blaze3d.vulkan.VulkanDevice d?d:null,w,h);
                backend="OptiX temporal AOV";
            }catch(RuntimeException error){backend="fallback Vulkan temporal/spatial: "+error.getMessage();org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("OptiX reconstruction unavailable; Vulkan reconstruction retained",error);}
        }
        if(optix!=null){
            var descriptor=RenderPassDescriptor.create(()->"VoxelLight denoiser motion/normal/trust").withRenderArea(new RenderPass.RenderArea(0,0,w,h));
            for(var guide:denoiserViews)descriptor.withColorAttachment(guide,Optional.empty());
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_denoiser_guides");var pass=encoder.createRenderPass(descriptor)){
                pass.setPipeline(GUIDES);var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                GpuTextureView[] inputs={noisy,colorViews[read],guideViews[write][0],guideViews[write][1],guideViews[write][2],guideViews[read][0],guideViews[read][1],guideViews[read][2]};
                for(int i=0;i<inputs.length;i++)pass.bindTexture(INPUTS[i],inputs[i],sampler);pass.setUniform("ReconstructionSettings",settings);pass.draw(3,1,0,0);
            }
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_optix_exchange")){optix.resolve(encoder,context,denoiserGuides[0],denoiserGuides[1],denoiserGuides[2],denoised,valid&&generation==epoch);result=denoisedView;}
            catch(RuntimeException error){optix.close();optix=null;backend="fallback Vulkan temporal/spatial: "+error.getMessage();valid=false;data.putFloat(76,0);encoder.writeToBuffer(settings.slice(),data);org.slf4j.LoggerFactory.getLogger("VoxelLight").error("OptiX denoiser failed; Vulkan reconstruction retained",error);}
        }
        if(result==null){
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_reconstruction");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight RT temporal reconstruction").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(colorViews[write],Optional.empty()))){
            pass.setPipeline(PIPELINE);var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            GpuTextureView[] inputs={noisy,colorViews[read],guideViews[write][0],guideViews[write][1],guideViews[write][2],guideViews[read][0],guideViews[read][1],guideViews[read][2]};
            for(int i=0;i<inputs.length;i++)pass.bindTexture(INPUTS[i],inputs[i],sampler);pass.setUniform("ReconstructionSettings",settings);pass.draw(3,1,0,0);
        }
            result=colorViews[write];
        }
        previousClip.set(clip);previousX=x;previousY=y;previousZ=z;generation=epoch;valid=true;read=write;return result;
    }
    public void close(){if(optix!=null)optix.close();optix=null;for(int i=0;i<3;i++){if(denoiserViews[i]!=null)denoiserViews[i].close();if(denoiserGuides[i]!=null)denoiserGuides[i].close();denoiserViews[i]=null;denoiserGuides[i]=null;}if(denoisedView!=null)denoisedView.close();if(denoised!=null)denoised.close();denoisedView=null;denoised=null;backend="Vulkan temporal/spatial";for(int bank=0;bank<2;bank++){if(colorViews[bank]!=null)colorViews[bank].close();if(colors[bank]!=null)colors[bank].close();colorViews[bank]=null;colors[bank]=null;for(int p=0;p<3;p++){if(guideViews[bank][p]!=null)guideViews[bank][p].close();if(guides[bank][p]!=null)guides[bank][p].close();guideViews[bank][p]=null;guides[bank][p]=null;}}if(settings!=null)settings.close();settings=null;valid=false;lighting=null;generation=-1;read=0;}
}
