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
    private VulkanRtBuffer scratch;
    private int primitiveCount,buildFlags,geometryType,geometryFlags;
    private long vertexStride;
    private int vertexFormat,indexType;
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
            int flags=info.get(0).flags();
            boolean reuse=previous!=null&&previous.compatible(type,geometry,primitives,flags);
            VulkanRtAccel result;
            if(reuse)result=previous;
            else {
                var sizes=VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(device.vkDevice(),VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,info.get(0),stack.ints(primitives),sizes);
                result=new VulkanRtAccel(device,type,sizes.accelerationStructureSize());
                try{result.scratch=new VulkanRtBuffer(device,Math.max(sizes.buildScratchSize(),sizes.updateScratchSize())+scratchAlignment,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);}
                catch(RuntimeException error){result.close();throw error;}
                result.primitiveCount=primitives;result.buildFlags=flags;
                result.geometryType=geometry.get(0).geometryType();result.geometryFlags=geometry.get(0).flags();
                if(type==VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR){var t=geometry.get(0).geometry().triangles();result.vertexStride=t.vertexStride();result.vertexFormat=t.vertexFormat();result.indexType=t.indexType();}
            }
            try {
                info.get(0).dstAccelerationStructure(result.handle).scratchData().deviceAddress(VulkanSbt.align(result.scratch.address(),scratchAlignment));
                if(reuse)info.get(0).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR).srcAccelerationStructure(result.handle);
                var range=VkAccelerationStructureBuildRangeInfoKHR.calloc(1,stack).primitiveCount(primitives);
                vkCmdBuildAccelerationStructuresKHR(command,info,stack.pointers(range.address()));
                return result;
            } catch(RuntimeException error) {if(!reuse)result.close();throw error;}

        }
    }
    private boolean compatible(int type,VkAccelerationStructureGeometryKHR.Buffer geometry,int primitives,int flags){
        if(closed||(buildFlags&VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)==0||flags!=buildFlags||primitiveCount!=primitives||geometry.remaining()!=1||geometry.get(0).geometryType()!=geometryType||geometry.get(0).flags()!=geometryFlags)return false;
        if(type==VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR){var t=geometry.get(0).geometry().triangles();return t.vertexStride()==vertexStride&&t.vertexFormat()==vertexFormat&&t.indexType()==indexType;}
        return type==VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    }
    long bytes(){return storage.size()+(scratch==null?0:scratch.size());}
    long handle() { return handle; }
    long address() { return address; }
    @Override public void close() { if(!closed) {closed=true;device.createCommandEncoder().queueForDestroy(this);storage.close();if(scratch!=null)scratch.close();} }
    @Override public void destroy() { vkDestroyAccelerationStructureKHR(device.vkDevice(),handle,null); }
}
