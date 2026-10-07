package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.*;
import com.voxellight.nvidia.DlssNative;
import com.voxellight.rt.RtJitter;
import com.voxellight.rt.vulkan.VulkanRtContext;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.Optional;
import static org.lwjgl.vulkan.VK10.*;

/** Vulkan-native joint denoising/upscaling; NGX writes the display-resolution image. */
final class RtDlssReconstruction implements AutoCloseable {
    private static final String[] INPUTS={"Albedo","Normal","Position","MotionPosition","Specular"};
    private static final GpuFormat[] FORMATS={GpuFormat.R32_FLOAT,GpuFormat.RG32_FLOAT,GpuFormat.RGBA32_FLOAT,GpuFormat.RGBA32_FLOAT,GpuFormat.RGBA32_FLOAT};
    static final RenderPipeline GUIDES=guidePipeline();
    private static RenderPipeline guidePipeline(){
        var bindings=BindGroupLayout.builder();for(var name:INPUTS)bindings.withSampler(name);
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/rt_dlss_guides"))
            .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","rt_dlss_guides"))
            .withBindGroupLayout(bindings.withUniform("DlssSettings",UniformType.UNIFORM_BUFFER).build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        for(int i=0;i<FORMATS.length;i++)builder.withColorTargetState(i,new ColorTargetState(Optional.empty(),FORMATS[i],ColorTargetState.WRITE_ALL));return builder.build();
    }
    private VulkanDevice device;
    private long session;
    private int width,height,outputWidth,outputHeight;
    private GpuBuffer settings;
    private final GpuTexture[] raw=new GpuTexture[5],guides=new GpuTexture[5];
    private final GpuTextureView[] rawViews=new GpuTextureView[5],guideViews=new GpuTextureView[5];
    private GpuTexture result;
    private GpuTextureView resultView;
    private final Matrix4f previousClip=new Matrix4f();
    private double previousX,previousY,previousZ;
    private long epoch=Long.MIN_VALUE,lastTime;
    private boolean valid;
    private int quality=0; // NGX MaxPerf; no frame generation.
    void invalidate(){valid=false;}
    private void initialize(){
        if(session!=0)return;
        if(!(((GpuBackendAccess)RenderSystem.getDevice()).voxellight$backend() instanceof VulkanDevice vk))throw new IllegalStateException("DLSS RR requires Vulkan");
        device=vk;var path=DlssNative.load();
        session=DlssNative.create(device.vkDevice().getPhysicalDevice().getInstance().address(),device.vkDevice().getPhysicalDevice().address(),device.vkDevice().address(),VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),device.vkDevice().getCapabilities().vkGetDeviceProcAddr,path.toAbsolutePath().toString());
        if(session==0)throw new IllegalStateException("NGX session unavailable");
    }
    int[] optimal(int w,int h){initialize();var size=DlssNative.optimal(session,w,h,quality);if(size==null||size.length!=2||size[0]<=0||size[1]<=0||size[0]>w||size[1]>h)throw new IllegalStateException("Invalid NGX optimal input dimensions");return size;}
    GpuTextureView resolve(CommandEncoder encoder,VulkanRtContext context,GpuTextureView noisy,Matrix4f clip,double x,double y,double z,long generation,int w,int h,int ow,int oh,Matrix4f viewRotation){
        initialize();
        if(settings==null||w!=width||h!=height||ow!=outputWidth||oh!=outputHeight){
            if(settings!=null){close();initialize();}
            width=w;height=h;outputWidth=ow;outputHeight=oh;
            var gpu=RenderSystem.getDevice();if(!gpu.precompilePipeline(GUIDES,RenderProbe.SHADERS).isValid())throw new IllegalStateException("DLSS guide pipeline unavailable");
            for(int i=0;i<raw.length;i++){
                raw[i]=gpu.createTexture("VoxelLight DLSS raw guide "+i,GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA32_FLOAT,w,h,1,1);rawViews[i]=gpu.createTextureView(raw[i]);
                guides[i]=gpu.createTexture("VoxelLight DLSS guide "+i,GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_RENDER_ATTACHMENT,FORMATS[i],w,h,1,1);guideViews[i]=gpu.createTextureView(guides[i]);
            }
            // Minecraft currently has no public storage-image usage; our Vulkan-only bit
            // is translated by VulkanStorageImageMixin, leaving vanilla textures untouched.
            result=gpu.createTexture("VoxelLight DLSS RR output",32|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA16_FLOAT,ow,oh,1,1);resultView=gpu.createTextureView(result);
            settings=gpu.createBuffer(()->"VoxelLight DLSS guide settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,192);
        }
        for(int i=0;i<raw.length;i++)context.copyGuide(encoder,new int[]{0,1,2,6,7}[i],raw[i]);
        var data=ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());previousClip.get(0,data);clip.get(64,data);
        data.position(128).putFloat((float)previousX).putFloat((float)previousY).putFloat((float)previousZ).putFloat(valid&&epoch==generation?1:0);
        data.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat(0);data.putFloat(w).putFloat(h).putFloat(0).putFloat(0);data.position(192).flip();encoder.writeToBuffer(settings.slice(),data);
        var descriptor=RenderPassDescriptor.create(()->"VoxelLight DLSS RR guides").withRenderArea(new RenderPass.RenderArea(0,0,w,h));for(var view:guideViews)descriptor.withColorAttachment(view,Optional.empty());
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_dlss_guides");var pass=encoder.createRenderPass(descriptor)){
            pass.setPipeline(GUIDES);for(int i=0;i<raw.length;i++)pass.bindTexture(INPUTS[i],rawViews[i],RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.setUniform("DlssSettings",settings);pass.draw(3,1,0,0);
        }
        long[] images=new long[21];GpuTextureView[] inputs={noisy,guideViews[0],guideViews[1],guideViews[2],guideViews[3],guideViews[4],resultView};
        for(int i=0;i<inputs.length;i++){var view=(VulkanGpuTextureView)inputs[i];images[i*3]=view.vkImageView();images[i*3+1]=view.texture().vkImage();images[i*3+2]=VulkanConst.toVk(view.texture().getFormat());}
        float[] matrices=new float[32];new Matrix4f(viewRotation).translate((float)-x,(float)-y,(float)-z).get(matrices);new Matrix4f(clip).mul(new Matrix4f(viewRotation).invert()).get(matrices,16);
        long now=System.nanoTime();float frameMs=lastTime==0?16.67f:Math.clamp((now-lastTime)/1e6f,.1f,200);lastTime=now;
        var nativeEncoder=device.createCommandEncoder();var command=nativeEncoder.allocateAndBeginTransientCommandBuffer();
        RuntimeException failure=null;
        try(var stack=MemoryStack.stackPush();var profile=RenderPassProfile.beginNative(command,"vulkan_rt_dlss_rr")){
            barrier(command,stack,VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
            // Ray sample displacement is opposite to a projection-matrix jitter.
            try{DlssNative.evaluate(session,command.address(),images,w,h,ow,oh,quality,-RtJitter.x(context.lastFrame()),-RtJitter.y(context.lastFrame()),!valid||epoch!=generation,frameMs,matrices);}
            catch(RuntimeException error){failure=error;}
            barrier(command,stack,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT);
        }
        // NGX create/evaluate can leave commands recorded even on a recoverable error.
        // Always close/submit the borrowed command before releasing the feature.
        if(vkEndCommandBuffer(command)!=VK_SUCCESS)throw new IllegalStateException("DLSS command end failed");nativeEncoder.execute(command);
        if(failure!=null)throw failure;
        previousClip.set(clip);previousX=x;previousY=y;previousZ=z;epoch=generation;valid=true;return resultView;
    }
    private static void barrier(VkCommandBuffer command,MemoryStack stack,int source,int destination){
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(source).dstAccessMask(destination),null,null);
    }
    public void close(){
        if(session!=0){device.createCommandEncoder().submit();vkDeviceWaitIdle(device.vkDevice());DlssNative.destroy(session);session=0;}
        for(int i=0;i<raw.length;i++){if(rawViews[i]!=null)rawViews[i].close();if(raw[i]!=null)raw[i].close();if(guideViews[i]!=null)guideViews[i].close();if(guides[i]!=null)guides[i].close();rawViews[i]=null;raw[i]=null;guideViews[i]=null;guides[i]=null;}
        if(resultView!=null)resultView.close();if(result!=null)result.close();if(settings!=null)settings.close();resultView=null;result=null;settings=null;valid=false;epoch=Long.MIN_VALUE;lastTime=0;
    }
}
