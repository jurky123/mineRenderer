package com.voxellight.adapter;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;

/** Sparse asynchronous diagnostics, independently reset from sampling and reconstruction. */
final class RtDiagnostics {
    private int frames;
    private long generation;
    private volatile String value="pending";
    void reset(){generation++;frames=0;value="pending";}
    String value(){return value;}
    void observe(CommandEncoder encoder,com.voxellight.rt.vulkan.VulkanRtContext context,GpuTexture texture,boolean materials,int width,int height){if(++frames==30)diagnose(encoder,context,texture,materials,width,height);}
    private void diagnose(CommandEncoder encoder,com.voxellight.rt.vulkan.VulkanRtContext context,GpuTexture texture,boolean materials,int width,int height) {
        long token=generation;
        var read=RenderSystem.getDevice().createBuffer(()->"VoxelLight POC two-pixel diagnostic",com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST|com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ,materials?96:32);
        if(materials)context.copyLightingDiagnostic(encoder,read);
        encoder.copyTextureToBuffer(texture,read,0,()->{},0,0,0,1,1);
        encoder.copyTextureToBuffer(texture,read,16,()->{
            try(var mapped=read.map(true,false)) {
                if(token!=generation)return;
                var b=mapped.data().order(java.nio.ByteOrder.nativeOrder());
                value="marker="+b.getFloat(0)+"/"+b.getFloat(4)+"/"+b.getFloat(8)+"/"+b.getFloat(12)+",center="+b.getFloat(16)+"/"+b.getFloat(20)+"/"+b.getFloat(24)+"/"+b.getFloat(28);
                if(materials){String[] fields={"heldIncident","heldBsdf","heldHemisphere","heldVisibility"};for(int i=0;i<4;i++){int o=32+i*16;value+=","+fields[i]+"="+b.getFloat(o)+"/"+b.getFloat(o+4)+"/"+b.getFloat(o+8)+"/"+b.getFloat(o+12);}}
                org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT POC GPU diagnostic: {}",value);
            }catch(RuntimeException error){value="readback failed";org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("POC two-pixel diagnostic failed",error);}
            finally{read.close();}
        },0,width/2,height/2,1,1);
    }
}
