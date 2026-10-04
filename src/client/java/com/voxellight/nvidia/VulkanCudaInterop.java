package com.voxellight.nvidia;
import com.mojang.blaze3d.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
/** Owns allocations/semaphores; native imports close before deferred Vulkan destruction. */
public final class VulkanCudaInterop implements AutoCloseable {
 public final ExternalBuffer[] buffers;
 public final ExternalSemaphore ready,done;
 private final VulkanDevice device;
 public VulkanCudaInterop(VulkanDevice device,long context,long[] sizes){
  long total=0;for(long bytes:sizes){if(bytes<=0)throw new IllegalArgumentException("Invalid external buffer size");total=Math.addExact(total,bytes);}if(total>768L*1024*1024)throw new IllegalStateException("RTX external allocation budget exceeds 768 MiB");
  this.device=device;buffers=new ExternalBuffer[sizes.length];ExternalSemaphore a=null,b=null;
  try{for(int i=0;i<sizes.length;i++){buffers[i]=new ExternalBuffer(device,sizes[i]);OptixNative.importBuffer(context,buffers[i].exportHandle(),buffers[i].allocationSize(),sizes[i]);}
   a=new ExternalSemaphore(device);b=new ExternalSemaphore(device);OptixNative.importSemaphore(context,a.exportHandle());OptixNative.importSemaphore(context,b.exportHandle());
  }catch(RuntimeException error){for(var buffer:buffers)if(buffer!=null)buffer.close();if(a!=null)a.close();if(b!=null)b.close();throw error;}
  ready=a;done=b;
 }
 public void releaseToCuda(){ownership(true);var encoder=device.createCommandEncoder();encoder.signalSemaphore(ready.handle(),0,VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);encoder.submit();}
 public void acquireFromCuda(){var encoder=device.createCommandEncoder();encoder.waitSemaphore(done.handle(),0,VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);ownership(false);}
 private void ownership(boolean release){var encoder=device.createCommandEncoder();var command=encoder.allocateAndBeginTransientCommandBuffer();try(var stack=MemoryStack.stackPush()){
  var barriers=VkBufferMemoryBarrier.calloc(buffers.length,stack);int family=device.graphicsQueue().queueFamilyIndex();
  for(int i=0;i<buffers.length;i++)barriers.get(i).sType$Default().srcAccessMask(release?VK10.VK_ACCESS_MEMORY_WRITE_BIT|VK10.VK_ACCESS_MEMORY_READ_BIT:0).dstAccessMask(release?0:VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT).srcQueueFamilyIndex(release?family:VK11.VK_QUEUE_FAMILY_EXTERNAL).dstQueueFamilyIndex(release?VK11.VK_QUEUE_FAMILY_EXTERNAL:family).buffer(buffers[i].vkBuffer()).offset(0).size(buffers[i].size());
  VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,null,barriers,null);ExternalBuffer.check(VK10.vkEndCommandBuffer(command));encoder.execute(command);
 }}
 public void close(){for(var b:buffers)b.close();ready.close();done.close();}
}
