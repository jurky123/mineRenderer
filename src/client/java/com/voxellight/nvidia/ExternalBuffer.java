package com.voxellight.nvidia;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
/** Dedicated exportable allocation; never maps to CPU. CUDA imports are released before this owner closes. */
public final class ExternalBuffer extends VulkanGpuBuffer {
    private final VulkanDevice device;
    private final long memory,allocationSize;
    private boolean closed;
    private record Allocation(long buffer,long memory,long bytes){}
    public ExternalBuffer(VulkanDevice device,long bytes){this(device,bytes,allocate(device,bytes));}
    private ExternalBuffer(VulkanDevice d,long bytes,Allocation a){super(a.buffer,USAGE_COPY_DST|USAGE_COPY_SRC,bytes);device=d;memory=a.memory;allocationSize=a.bytes;}
    static final boolean WINDOWS=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    public static int handleType(){return WINDOWS?VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT:VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;}
    private static Allocation allocate(VulkanDevice d,long bytes){
        long buffer=0,memory=0;
        try(var s=MemoryStack.stackPush()){
            int usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            var capability=VkPhysicalDeviceExternalBufferInfo.calloc(s).sType$Default().usage(usage).handleType(handleType());
            var support=VkExternalBufferProperties.calloc(s).sType$Default();
            VK11.vkGetPhysicalDeviceExternalBufferProperties(d.vkDevice().getPhysicalDevice(),capability,support);
            if((support.externalMemoryProperties().externalMemoryFeatures()&VK11.VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)==0)
                throw new IllegalStateException("Vulkan device cannot export RTX buffers");
            var external=VkExternalMemoryBufferCreateInfo.calloc(s).sType$Default().handleTypes(handleType());
            var create=VkBufferCreateInfo.calloc(s).sType$Default().pNext(external.address()).size(bytes).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var out=s.mallocLong(1);check(vkCreateBuffer(d.vkDevice(),create,null,out));buffer=out.get(0);
            var requirements=VkMemoryRequirements.calloc(s);vkGetBufferMemoryRequirements(d.vkDevice(),buffer,requirements);
            var properties=VkPhysicalDeviceMemoryProperties.calloc(s);vkGetPhysicalDeviceMemoryProperties(d.vkDevice().getPhysicalDevice(),properties);
            int type=-1;for(int i=0;i<properties.memoryTypeCount();i++)if((requirements.memoryTypeBits()&(1<<i))!=0&&(properties.memoryTypes(i).propertyFlags()&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)!=0){type=i;break;}
            if(type<0)throw new IllegalStateException("No device-local external allocation type");
            var dedicated=VkMemoryDedicatedAllocateInfo.calloc(s).sType$Default().buffer(buffer);
            var export=VkExportMemoryAllocateInfo.calloc(s).sType$Default().handleTypes(handleType()).pNext(dedicated.address());
            var alloc=VkMemoryAllocateInfo.calloc(s).sType$Default().pNext(export.address()).allocationSize(requirements.size()).memoryTypeIndex(type);
            check(vkAllocateMemory(d.vkDevice(),alloc,null,out));memory=out.get(0);check(vkBindBufferMemory(d.vkDevice(),buffer,memory,0));
            return new Allocation(buffer,memory,requirements.size());
        }catch(RuntimeException e){if(buffer!=0)vkDestroyBuffer(d.vkDevice(),buffer,null);if(memory!=0)vkFreeMemory(d.vkDevice(),memory,null);throw e;}
    }
    public long exportHandle(){try(var s=MemoryStack.stackPush()){
        if(WINDOWS){var info=VkMemoryGetWin32HandleInfoKHR.calloc(s).sType$Default().memory(memory).handleType(handleType());var out=s.mallocPointer(1);check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device.vkDevice(),info,out));return out.get(0);}
        var info=VkMemoryGetFdInfoKHR.calloc(s).sType$Default().memory(memory).handleType(handleType());var out=s.mallocInt(1);check(KHRExternalMemoryFd.vkGetMemoryFdKHR(device.vkDevice(),info,out));return out.get(0);
    }}
    public long allocationSize(){return allocationSize;}
    public boolean isClosed(){return closed;}
    public GpuBufferSlice.MappedView map(long o,long n,boolean read,boolean write){throw new UnsupportedOperationException("RTX external buffers are GPU-only");}
    public void close(){if(!closed){closed=true;device.createCommandEncoder().queueForDestroy(this);}}
    public void destroy(){vkDestroyBuffer(device.vkDevice(),vkBuffer(),null);vkFreeMemory(device.vkDevice(),memory,null);}
    public static void check(int result){if(result!=VK_SUCCESS)throw new IllegalStateException("Vulkan interop error "+result);}
}
