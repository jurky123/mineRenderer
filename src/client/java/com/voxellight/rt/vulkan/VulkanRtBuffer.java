package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static com.voxellight.rt.vulkan.VulkanRtCapabilities.check;

/** Device-local addressable allocation. MC's submission-index destruction queue protects in-flight work. */
public final class VulkanRtBuffer extends VulkanGpuBuffer {
    private final VulkanDevice device;
    private final long memory, address;
    private boolean closed;
    private record Allocation(long buffer, long memory, long address) {}
    public VulkanRtBuffer(VulkanDevice device, long bytes, int usage) { this(device, bytes, allocate(device, bytes, usage)); }
    private VulkanRtBuffer(VulkanDevice device, long bytes, Allocation allocation) {
        super(allocation.buffer, USAGE_COPY_SRC | USAGE_COPY_DST, bytes);
        this.device = device; memory = allocation.memory; address = allocation.address;
    }
    private static Allocation allocate(VulkanDevice device, long bytes, int usage) {
        if(bytes <= 0) throw new IllegalArgumentException("Empty RT allocation");
        var vk = device.vkDevice(); long buffer = 0, memory = 0;
        try(var stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                .usage(usage | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(vk, info, null, out)); buffer = out.get(0);
            var requirements = VkMemoryRequirements.calloc(stack); vkGetBufferMemoryRequirements(vk, buffer, requirements);
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack); vkGetPhysicalDeviceMemoryProperties(vk.getPhysicalDevice(), properties);
            int type = -1;
            for(int i=0;i<properties.memoryTypeCount();i++) if((requirements.memoryTypeBits() & (1<<i))!=0 && (properties.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)!=0) { type=i; break; }
            if(type<0) throw new IllegalStateException("No RT memory type");
            var flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default().flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            var alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(flags.address()).allocationSize(requirements.size()).memoryTypeIndex(type);
            check(vkAllocateMemory(vk, alloc, null, out)); memory=out.get(0); check(vkBindBufferMemory(vk, buffer, memory, 0));
            long address=VK12.vkGetBufferDeviceAddress(vk, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
            if(address==0)throw new IllegalStateException("RT buffer has no device address");
            return new Allocation(buffer,memory,address);
        } catch(RuntimeException error) {
            if(buffer!=0)vkDestroyBuffer(vk,buffer,null); if(memory!=0)vkFreeMemory(vk,memory,null); throw error;
        }
    }
    long address() { return address; }
    @Override public boolean isClosed() { return closed; }
    @Override public GpuBufferSlice.MappedView map(long o,long n,boolean read,boolean write) { throw new UnsupportedOperationException("RT buffers are device-local"); }
    @Override public void close() { if(!closed) { closed=true;device.createCommandEncoder().queueForDestroy(this); } }
    @Override public void destroy() { vkDestroyBuffer(device.vkDevice(),vkBuffer(),null);vkFreeMemory(device.vkDevice(),memory,null); }
}
