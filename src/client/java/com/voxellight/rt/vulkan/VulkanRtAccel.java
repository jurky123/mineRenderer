package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static com.voxellight.rt.vulkan.VulkanRtCapabilities.check;

/** One BLAS/TLAS owner; build recording never submits per section. */
public final class VulkanRtAccel implements AutoCloseable, Destroyable {
    private final VulkanDevice device;
    private final VulkanRtBuffer storage;
    private final long handle, address;
    private boolean closed;
    private VulkanRtAccel(VulkanDevice device, int type, long bytes) {
        this.device=device;storage=new VulkanRtBuffer(device,bytes,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
        try(var stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);
            var create=VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(storage.vkBuffer()).size(bytes).type(type);
            check(vkCreateAccelerationStructureKHR(device.vkDevice(),create,null,out));handle=out.get(0);
            address=vkGetAccelerationStructureDeviceAddressKHR(device.vkDevice(),VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle));
        } catch(RuntimeException error) { storage.close();throw error; }
    }
    static VkAccelerationStructureGeometryKHR.Buffer triangles(MemoryStack stack,VulkanRtBuffer vertices,int count) {
        var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack);
        geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(40).maxVertex(count-1).indexType(VK_INDEX_TYPE_NONE_KHR).vertexData().deviceAddress(vertices.address());
        return geometry;
    }
    static void buildInfo(VkAccelerationStructureBuildGeometryInfoKHR info,int type,VkAccelerationStructureGeometryKHR.Buffer geometry) {
        info.sType$Default().type(type).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geometry.remaining()).pGeometries(geometry);
    }
    static VulkanRtAccel build(VulkanDevice device,VkCommandBuffer command,int type,VkAccelerationStructureGeometryKHR.Buffer geometry,int primitives,int scratchAlignment) {
        return build(device,command,type,geometry,primitives,scratchAlignment,false,null);
    }
    static VulkanRtAccel build(VulkanDevice device,VkCommandBuffer command,int type,VkAccelerationStructureGeometryKHR.Buffer geometry,int primitives,int scratchAlignment,boolean dynamic,VulkanRtAccel previous) {
        try(var stack=MemoryStack.stackPush()) {
            var info=VkAccelerationStructureBuildGeometryInfoKHR.calloc(1,stack);
            buildInfo(info.get(0),type,geometry);
            if(dynamic)info.get(0).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR|VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR);
            var sizes=VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device.vkDevice(),VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,info.get(0),stack.ints(primitives),sizes);
            var result=new VulkanRtAccel(device,type,sizes.accelerationStructureSize());
            VulkanRtBuffer scratch=null;
            try {
                scratch=new VulkanRtBuffer(device,(previous==null?sizes.buildScratchSize():sizes.updateScratchSize())+scratchAlignment,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                info.get(0).dstAccelerationStructure(result.handle).scratchData().deviceAddress(VulkanSbt.align(scratch.address(),scratchAlignment));
                if(previous!=null)info.get(0).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR).srcAccelerationStructure(previous.handle);
                var range=VkAccelerationStructureBuildRangeInfoKHR.calloc(1,stack).primitiveCount(primitives);
                vkCmdBuildAccelerationStructuresKHR(command,info,stack.pointers(range.address()));
                return result;
            } catch(RuntimeException error) { result.close();throw error; }
            finally { if(scratch!=null)scratch.close(); }
        }
    }
    long handle() { return handle; }
    long address() { return address; }
    @Override public void close() { if(!closed) {closed=true;device.createCommandEncoder().queueForDestroy(this);storage.close();} }
    @Override public void destroy() { vkDestroyAccelerationStructureKHR(device.vkDevice(),handle,null); }
}
