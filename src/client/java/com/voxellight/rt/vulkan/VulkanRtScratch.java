package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** One growable, serially reused AS scratch allocation per scene/queue. */
final class VulkanRtScratch implements AutoCloseable {
    private final VulkanDevice device;
    private final int alignment;
    private VulkanRtBuffer buffer;
    private final java.util.List<VulkanRtBuffer> retiring=new java.util.ArrayList<>();
    VulkanRtScratch(VulkanDevice device,int alignment){this.device=device;this.alignment=alignment;}
    long acquire(VkCommandBuffer command,MemoryStack stack,long bytes){
        if(buffer==null||buffer.size()<bytes+alignment){
            long capacity=4096;while(capacity<bytes+alignment)capacity=Math.multiplyExact(capacity,2);
            if(buffer!=null)retiring.add(buffer);buffer=new VulkanRtBuffer(device,capacity,org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
        VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        return VulkanSbt.align(buffer.address(),alignment);
    }
    void submitted(){for(var old:retiring)old.close();retiring.clear();}
    long bytes(){return buffer==null?0:buffer.size();}
    public void close(){submitted();if(buffer!=null)retiring.add(buffer);buffer=null;}
}
