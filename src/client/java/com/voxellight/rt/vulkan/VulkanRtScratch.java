package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Scene-owned scratch arena: independent builds use disjoint slices within a submission. */
final class VulkanRtScratch implements AutoCloseable {
    private final VulkanDevice device;
    private final int alignment;
    private final boolean sliced=com.voxellight.rt.RtExecutionOptions.sceneUpdate()==com.voxellight.rt.RtExecutionOptions.SceneUpdate.OPTIMIZED;
    private VulkanRtBuffer buffer;
    private long cursor,barriers,slices;
    private boolean dependency=true;
    private final java.util.List<VulkanRtBuffer> retiring=new java.util.ArrayList<>();
    VulkanRtScratch(VulkanDevice device,int alignment){this.device=device;this.alignment=alignment;}
    static long offset(long address,long cursor,int alignment){return VulkanSbt.align(Math.addExact(address,cursor),alignment)-address;}
    long acquire(VkCommandBuffer command,MemoryStack stack,long bytes){
        long required=Math.addExact(bytes,alignment);
        if(buffer==null||buffer.size()<required){grow(required);}
        long start=offset(buffer.address(),sliced?cursor:0,alignment);
        if(sliced&&start+bytes>buffer.size()){
            if(buffer.size()<16L*1024*1024){grow(Math.max(required,buffer.size()*2));start=offset(buffer.address(),0,alignment);}
            else{cursor=0;dependency=true;start=offset(buffer.address(),0,alignment);}
        }
        if(!sliced||dependency){
            VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            barriers++;dependency=false;
        }
        cursor=start+bytes;slices++;return buffer.address()+start;
    }
    private void grow(long required){
        long capacity=sliced?1024*1024:4096;while(capacity<required)capacity=Math.multiplyExact(capacity,2);
        if(buffer!=null)retiring.add(buffer);buffer=new VulkanRtBuffer(device,capacity,org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);cursor=0;
    }
    void submitted(){for(var old:retiring)old.close();retiring.clear();cursor=0;dependency=true;}
    long bytes(){return buffer==null?0:buffer.size();}
    long barriers(){return barriers;}long slices(){return slices;}
    public void close(){submitted();if(buffer!=null)buffer.close();buffer=null;}
}
