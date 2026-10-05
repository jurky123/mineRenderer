package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.voxellight.adapter.RtGeometryStream;
import com.voxellight.world.SectionKey;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Terrain bringup. Version-identical sections do no GPU work; replacements share one frame command batch. */
public final class VulkanRtScene implements AutoCloseable {
    private record Section(long version, VulkanRtBuffer vertices, VulkanRtAccel blas, byte[] normals) {}
    private final VulkanDevice device;
    private final int scratchAlignment;
    private final Map<SectionKey,Section> sections=new LinkedHashMap<>();
    private VulkanRtAccel tlas;
    private VulkanRtBuffer normalBuffer;
    private long generation, builds, tlasBuilds, bytes;
    VulkanRtScene(VulkanDevice device,int scratchAlignment) { this.device=device;this.scratchAlignment=scratchAlignment; }
    public Set<SectionKey> resident() { return Set.copyOf(sections.keySet()); }
    public long generation() { return generation; }
    long tlas() { return tlas==null?0:tlas.handle(); }
    VulkanRtBuffer normals() { return normalBuffer; }
    public String status() { return "sections="+sections.size()+", blasBuilds="+builds+", tlasBuilds="+tlasBuilds+", sceneBytes="+bytes; }
    public void update(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,List<RtGeometryStream.Section> changes,double x,double y,double z) {
        boolean dirty=false;
        var encoder=device.createCommandEncoder();
        var iterator=sections.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var k=entry.getKey();
            if(!admitted(k,x,y,z)) {
                release(entry.getValue());iterator.remove();dirty=true;
            }
        }
        List<RtGeometryStream.Section> accepted=new ArrayList<>();
        var ordered=new ArrayList<>(changes);
        ordered.sort(Comparator.comparingInt(change->sections.containsKey(change.key())?0:1));
        Set<SectionKey> protectedKeys=new HashSet<>();changes.forEach(change->protectedKeys.add(change.key()));
        long plannedBytes=bytes;int plannedCount=sections.size();
        for(var change:ordered) {
            if(!admitted(change.key(),x,y,z))continue;
            var old=sections.get(change.key());if(old!=null&&old.version==change.version())continue;
            if(change.vertices()==0) {
                if(old!=null){sections.remove(change.key());plannedBytes-=old.vertices.size();plannedCount--;release(old);dirty=true;}
                continue;
            }
            long oldBytes=old==null?0:old.vertices.size();
            if(change.triangles().length>64L*1024*1024)continue;
            // Existing edited sections take priority. Keep their old BLAS until replacement is built.
            while(old!=null && !fits(plannedBytes,oldBytes,change.triangles().length,plannedCount, true)) {
                var victim=sections.keySet().stream().filter(key->!protectedKeys.contains(key))
                    .max(Comparator.comparingDouble(key->distance(key,x,y,z))).orElse(null);
                if(victim==null)break;
                var removed=sections.remove(victim);plannedBytes-=removed.vertices.size();plannedCount--;release(removed);dirty=true;
            }
            if(!fits(plannedBytes,oldBytes,change.triangles().length,plannedCount,old!=null))continue;
            accepted.add(change);plannedBytes=plannedBytes-oldBytes+change.triangles().length;
            if(old==null)plannedCount++;
            dirty=true;
        }
        if(!dirty)return;
        // Upload commands precede build commands through MC's encoder.execute ordering.
        Map<SectionKey,VulkanRtBuffer> uploads=new HashMap<>();
        try {
            for(var change:accepted) {
                var buffer=new VulkanRtBuffer(device,change.triangles().length,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
                uploads.put(change.key(),buffer);
                encoder.writeToBuffer(buffer.slice(),ByteBuffer.allocateDirect(change.triangles().length).put(change.triangles()).flip());
            }
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_blas");var stack=MemoryStack.stackPush()) {
                barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                for(var change:accepted) {
                    var buffer=uploads.remove(change.key());
                    try(var sectionStack=MemoryStack.stackPush()) {
                        var geometry=VulkanRtAccel.triangles(sectionStack,buffer,change.vertices());
                        var blas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,geometry,change.vertices()/3,scratchAlignment);
                        var source=ByteBuffer.wrap(change.triangles()).order(ByteOrder.nativeOrder());
                        var normals=ByteBuffer.allocate(change.vertices()/3*16).order(ByteOrder.nativeOrder());
                        for(int triangle=0;triangle<change.vertices()/3;triangle++)normals.putFloat(source.getFloat(triangle*120+20)).putFloat(source.getFloat(triangle*120+24)).putFloat(source.getFloat(triangle*120+28)).putFloat(0);
                        var previous=sections.put(change.key(),new Section(change.version(),buffer,blas,normals.array()));
                        bytes+=buffer.size();if(previous!=null)release(previous);builds++;
                    } catch(RuntimeException error) { buffer.close();throw error; }
                }
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
            }
            try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_tlas")){rebuildTlas();}generation++;
        } finally { uploads.values().forEach(VulkanRtBuffer::close); }
    }
    private void rebuildTlas() {
        if(tlas!=null) {tlas.close();tlas=null;}
        if(normalBuffer!=null) {normalBuffer.close();normalBuffer=null;}
        if(sections.isEmpty())return;
        int triangles=sections.values().stream().mapToInt(section->section.normals.length/16).sum();
        if(triangles>=0x1000000)throw new IllegalStateException("RT instance normal base exceeds 24 bits");
        var data=ByteBuffer.allocateDirect(triangles*16);
        sections.values().forEach(section->data.put(section.normals));data.flip();
        normalBuffer=new VulkanRtBuffer(device,data.remaining(),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        var encoder=device.createCommandEncoder();encoder.writeToBuffer(normalBuffer.slice(),data);
        var instances=new VulkanRtBuffer(device,(long)sections.size()*VkAccelerationStructureInstanceKHR.SIZEOF,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
        try(var packed=allocateInstances(sections.size());var stack=MemoryStack.stackPush()) {
            int i=0,base=0;
            for(var entry:sections.entrySet()) {
                var instance=packed.get(i++);var k=entry.getKey();
                instance.transform().matrix(0,1).matrix(5,1).matrix(10,1).matrix(3,k.x()*16f).matrix(7,k.y()*16f).matrix(11,k.z()*16f);
                instance.instanceCustomIndex(base).mask(255).instanceShaderBindingTableRecordOffset(0).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(entry.getValue().blas.address());
                base+=entry.getValue().normals.length/16;
            }
            encoder.writeToBuffer(instances.slice(),org.lwjgl.system.MemoryUtil.memByteBuffer(packed.address(),packed.remaining()*VkAccelerationStructureInstanceKHR.SIZEOF));
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack);geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address());
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            tlas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,geometry,sections.size(),scratchAlignment);
            barrier(command,stack,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_SHADER_READ_BIT);
            VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);tlasBuilds++;
        } finally {instances.close();}
    }
    static VkAccelerationStructureInstanceKHR.Buffer allocateInstances(int count) { return VkAccelerationStructureInstanceKHR.calloc(count); }
    static void barrier(VkCommandBuffer command,MemoryStack stack,int sourceStage,int sourceAccess,int destinationStage,int destinationAccess) {
        var barrier=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier(command,sourceStage,destinationStage,0,barrier,null,null);
    }
    static boolean fits(long residentBytes,long previousBytes,long incomingBytes,int count,boolean replacement) {
        return incomingBytes>0&&incomingBytes<=64L*1024*1024&&residentBytes-previousBytes+incomingBytes<=64L*1024*1024&&(replacement?count<=512:count<512);
    }
    private static double distance(SectionKey key,double x,double y,double z) {
        double dx=key.x()*16.+8-x,dy=key.y()*16.+8-y,dz=key.z()*16.+8-z;return dx*dx+dy*dy+dz*dz;
    }
    private static boolean admitted(SectionKey key,double x,double y,double z) {return Math.abs(key.x()*16.+8-x)<=144&&Math.abs(key.y()*16.+8-y)<=144&&Math.abs(key.z()*16.+8-z)<=144;}
    private void release(Section section) {bytes-=section.vertices.size();section.blas.close();section.vertices.close();}
    @Override public void close() { sections.values().forEach(this::release);sections.clear();if(tlas!=null)tlas.close();if(normalBuffer!=null)normalBuffer.close();tlas=null;normalBuffer=null;generation++; }
}
