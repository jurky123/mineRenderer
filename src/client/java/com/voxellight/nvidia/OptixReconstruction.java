package com.voxellight.nvidia;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.voxellight.rt.vulkan.VulkanRtContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** GPU-only beauty + three AOV temporal denoising, one interop exchange per batched frame. */
public final class OptixReconstruction implements AutoCloseable {
    private final VulkanDevice device;
    private ExternalBuffer buffer;
    private ExternalSemaphore ready,done;
    private long session,plane;
    public OptixReconstruction(VulkanDevice device,int width,int height){
        this.device=device;plane=(long)width*height*16;
        try{
            OptixDenoiserNative.load();buffer=new ExternalBuffer(device,plane*6);ready=new ExternalSemaphore(device);done=new ExternalSemaphore(device);
            byte[] uuid=new byte[16];try(var stack=MemoryStack.stackPush()){
                var id=VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();VK11.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(),VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address()));id.deviceUUID().get(uuid);
            }
            session=OptixDenoiserNative.create(uuid,buffer.exportHandle(),buffer.allocationSize(),ready.exportHandle(),done.exportHandle(),width,height);
            if(session==0)throw new IllegalStateException("OptiX session unavailable");
        }catch(RuntimeException error){close();throw error;}
    }
    public void resolve(CommandEncoder encoder,VulkanRtContext context,GpuTexture normal,GpuTexture flow,GpuTexture trust,GpuTexture destination,boolean previousValid){
        context.copyBeautyToBuffer(encoder,buffer.slice(0,plane));context.copyGuideToBuffer(encoder,0,buffer.slice(plane,plane));
        encoder.copyTextureToBuffer(normal,buffer,plane*2,()->{},0);encoder.copyTextureToBuffer(flow,buffer,plane*3,()->{},0);encoder.copyTextureToBuffer(trust,buffer,plane*4,()->{},0);
        transfer(false);var nativeEncoder=device.createCommandEncoder();nativeEncoder.signalSemaphore(ready.handle(),0,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);nativeEncoder.submit();
        // Queue the external wait before invoking so even a recoverable denoiser failure consumes its signal.
        nativeEncoder.waitSemaphore(done.handle(),0,VK_PIPELINE_STAGE_TRANSFER_BIT);
        OptixDenoiserNative.invoke(session,previousValid);transfer(true);
        encoder.copyBufferToTexture(buffer.slice(plane*5,plane),0,0,destination.getWidth(0),destination.getHeight(0),destination,0,0,destination.getWidth(0),destination.getHeight(0),0,0);
    }
    private void transfer(boolean acquire){try(var stack=MemoryStack.stackPush()){
        var encoder=device.createCommandEncoder();var command=encoder.allocateAndBeginTransientCommandBuffer();
        var barrier=VkBufferMemoryBarrier.calloc(1,stack).sType$Default().buffer(buffer.vkBuffer()).offset(0).size(plane*6)
            .srcQueueFamilyIndex(acquire?VK11.VK_QUEUE_FAMILY_EXTERNAL:device.graphicsQueue().queueFamilyIndex())
            .dstQueueFamilyIndex(acquire?device.graphicsQueue().queueFamilyIndex():VK11.VK_QUEUE_FAMILY_EXTERNAL)
            .srcAccessMask(acquire?0:VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(acquire?VK_ACCESS_TRANSFER_READ_BIT:0);
        vkCmdPipelineBarrier(command,acquire?VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT:VK_PIPELINE_STAGE_TRANSFER_BIT,acquire?VK_PIPELINE_STAGE_TRANSFER_BIT:VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,null,barrier,null);
        ExternalBuffer.check(vkEndCommandBuffer(command));encoder.execute(command);
    }}
    public void close(){if(session!=0){device.createCommandEncoder().submit();OptixDenoiserNative.destroy(session);session=0;}if(done!=null)done.close();if(ready!=null)ready.close();if(buffer!=null)buffer.close();done=null;ready=null;buffer=null;}
}
