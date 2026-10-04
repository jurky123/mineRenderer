package com.voxellight.nvidia;
import com.mojang.blaze3d.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
/** Binary external semaphore. Exactly one Vulkan and one CUDA signal/wait pair per frame. */
public final class ExternalSemaphore implements AutoCloseable,Destroyable {
    private final VulkanDevice device;
    private long semaphore;
    private boolean closed;
    public ExternalSemaphore(VulkanDevice d){device=d;try(var s=MemoryStack.stackPush()){
        var capability=VkPhysicalDeviceExternalSemaphoreInfo.calloc(s).sType$Default().handleType(type());
        var support=VkExternalSemaphoreProperties.calloc(s).sType$Default();
        VK11.vkGetPhysicalDeviceExternalSemaphoreProperties(d.vkDevice().getPhysicalDevice(),capability,support);
        if((support.externalSemaphoreFeatures()&VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)==0)
            throw new IllegalStateException("Vulkan device cannot export RTX semaphores");
        var export=VkExportSemaphoreCreateInfo.calloc(s).sType$Default().handleTypes(type());
        var info=VkSemaphoreCreateInfo.calloc(s).sType$Default().pNext(export.address());var out=s.mallocLong(1);
        ExternalBuffer.check(vkCreateSemaphore(d.vkDevice(),info,null,out));semaphore=out.get(0);
    }}
    private static int type(){return ExternalBuffer.WINDOWS?VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT:VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;}
    public long handle(){return semaphore;}
    public long exportHandle(){try(var s=MemoryStack.stackPush()){
        if(ExternalBuffer.WINDOWS){var i=VkSemaphoreGetWin32HandleInfoKHR.calloc(s).sType$Default().semaphore(semaphore).handleType(type());var p=s.mallocPointer(1);ExternalBuffer.check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device.vkDevice(),i,p));return p.get(0);}
        var i=VkSemaphoreGetFdInfoKHR.calloc(s).sType$Default().semaphore(semaphore).handleType(type());var p=s.mallocInt(1);ExternalBuffer.check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device.vkDevice(),i,p));return p.get(0);
    }}
    public void close(){if(!closed){closed=true;device.createCommandEncoder().queueForDestroy(this);}}
    public void destroy(){if(semaphore!=0){vkDestroySemaphore(device.vkDevice(),semaphore,null);semaphore=0;}}
}
