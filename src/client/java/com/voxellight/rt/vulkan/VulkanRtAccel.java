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
    private String signature;
    private long buildScratchBytes,updateScratchBytes;
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
    static VulkanRtAccel build(VulkanDevice device,VkCommandBuffer command,int type,VkAccelerationStructureGeometryKHR.Buffer geometry,int primitives,VulkanRtScratch scratch,boolean dynamic,VulkanRtAccel previous){
        return build(device,command,type,geometry,new int[]{primitives},scratch,dynamic,previous);
    }
    static VulkanRtAccel build(VulkanDevice device,VkCommandBuffer command,int type,VkAccelerationStructureGeometryKHR.Buffer geometry,int[] primitives,VulkanRtScratch scratch,boolean dynamic,VulkanRtAccel previous){
        if(geometry.remaining()!=primitives.length)throw new IllegalArgumentException("Geometry/count mismatch");
        try(var stack=MemoryStack.stackPush()){
            var info=VkAccelerationStructureBuildGeometryInfoKHR.calloc(1,stack);buildInfo(info.get(0),type,geometry);
            if(dynamic)info.get(0).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR|VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR);
            if(type==VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR&&java.util.stream.IntStream.range(0,geometry.remaining()).anyMatch(i->geometry.get(i).geometry().triangles().pNext()!=0))info.get(0).flags(info.get(0).flags()|EXTOpacityMicromap.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DISABLE_OPACITY_MICROMAPS_BIT_EXT);
            String signature=signature(type,geometry,primitives,info.get(0).flags());
            boolean reuse=dynamic&&previous!=null&&!previous.closed&&signature.equals(previous.signature);
            VulkanRtAccel result;
            if(reuse)result=previous;
            else{
                var sizes=VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(device.vkDevice(),VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,info.get(0),stack.ints(primitives),sizes);
                result=new VulkanRtAccel(device,type,sizes.accelerationStructureSize());result.signature=signature;
                result.buildScratchBytes=sizes.buildScratchSize();result.updateScratchBytes=sizes.updateScratchSize();
            }
            try{
                info.get(0).dstAccelerationStructure(result.handle).scratchData().deviceAddress(scratch.acquire(command,stack,reuse?result.updateScratchBytes:result.buildScratchBytes));
                if(reuse)info.get(0).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR).srcAccelerationStructure(result.handle);
                var ranges=VkAccelerationStructureBuildRangeInfoKHR.calloc(primitives.length,stack);
                for(int i=0;i<primitives.length;i++)ranges.get(i).primitiveCount(primitives[i]);
                vkCmdBuildAccelerationStructuresKHR(command,info,stack.pointers(ranges.address()));return result;
            }catch(RuntimeException error){if(!reuse)result.close();throw error;}
        }
    }
    static String signature(int type,VkAccelerationStructureGeometryKHR.Buffer geometry,int[] primitives,int flags){
        var result=new StringBuilder().append(type).append('/').append(flags);
        for(int i=0;i<geometry.remaining();i++){
            var g=geometry.get(geometry.position()+i);result.append('/').append(g.geometryType()).append(':').append(g.flags()).append(':').append(primitives[i]);
            if(type==VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR){var t=g.geometry().triangles();result.append(':').append(t.vertexStride()).append(':').append(t.vertexFormat()).append(':').append(t.indexType()).append(':').append(t.maxVertex()).append(':').append(t.pNext()!=0);}
        }
        return result.toString();
    }
    long bytes(){return storage.size();}
    long handle() { return handle; }
    long address() { return address; }
    @Override public void close() { if(!closed) {closed=true;device.createCommandEncoder().queueForDestroy(this);storage.close();} }
    @Override public void destroy() { vkDestroyAccelerationStructureKHR(device.vkDevice(),handle,null); }
}
