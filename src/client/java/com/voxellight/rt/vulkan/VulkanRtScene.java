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
    private record Section(long version, VulkanRtBuffer vertices, VulkanRtAccel blas, byte[] normals, List<com.voxellight.rt.RtEmitterTable.Triangle> emitters) {}
    private final VulkanDevice device;
    private final int scratchAlignment;
    private final boolean material;
    private VulkanRtBuffer geometryBuffer;
    private final LinkedHashMap<SectionKey,Long> requestedPages=new LinkedHashMap<>();
    public void requestPages(Set<SectionKey> keys){long now=System.nanoTime();requestedPages.entrySet().removeIf(entry->now-entry.getValue()>3_000_000_000L);for(var key:keys){requestedPages.remove(key);requestedPages.put(key,now);while(requestedPages.size()>16){var iterator=requestedPages.keySet().iterator();iterator.next();iterator.remove();}}}
    private final Map<SectionKey,Section> sections=new LinkedHashMap<>();
    private VulkanRtAccel tlas;
    private VulkanRtBuffer normalBuffer;
    private ByteBuffer flameData=ByteBuffer.allocateDirect(0);
    public ByteBuffer flameData(){return flameData.asReadOnlyBuffer();}
    public int flameCount(){return flameData.remaining()/64;}
    private ByteBuffer emitterData=ByteBuffer.allocateDirect(0);
    public ByteBuffer emitterData(){return emitterData.asReadOnlyBuffer();}
    public int emitterCount(){return emitterData.remaining()/64;}
    private long terrainGeneration;
    public long historyGeneration(){return terrainGeneration;}
    private long generation, builds, refits,tlasBuilds, bytes,geometryCopyBytes;
    private record Packed(long offset,long version){}
    private final Map<SectionKey,Packed> packedGeometry=new HashMap<>();
    VulkanRtScene(VulkanDevice device,int scratchAlignment) {this(device,scratchAlignment,false);}
    VulkanRtScene(VulkanDevice device,int scratchAlignment,boolean material) { this.device=device;this.scratchAlignment=scratchAlignment;this.material=material; }
    public Set<SectionKey> resident() { var keys=new HashSet<>(sections.keySet());keys.removeIf(VulkanRtScene::dynamic);return Set.copyOf(keys); }
    public long generation() { return generation; }
    long tlas() { return tlas==null?0:tlas.handle(); }
    VulkanRtBuffer geometry() {return geometryBuffer;}
    VulkanRtBuffer normals() { return normalBuffer; }
    public String status() { return "sections="+resident().size()+", dynamicMeshes="+(sections.size()-resident().size())+", rayPriorityPages="+requestedPages.size()+", blasBuilds="+builds+", blasRefits="+refits+", geometryCopyBytes="+geometryCopyBytes+", tlasBuilds="+tlasBuilds+", sceneBytes="+bytes+", deterministicFlames="+flameCount()+", emissiveTriangles="+emitterCount()+", shaderGeometryBytes="+(geometryBuffer==null?0:geometryBuffer.size()); }
    public void update(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,List<RtGeometryStream.Section> changes,double x,double y,double z) {
        geometryCopyBytes=0;boolean dirty=false;
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
        ordered.sort(Comparator.<RtGeometryStream.Section>comparingInt(change->sections.containsKey(change.key())?0:1)
            .thenComparingDouble(change->distance(change.key(),x,y,z)));
        Set<SectionKey> protectedKeys=new HashSet<>();changes.forEach(change->protectedKeys.add(change.key()));protectedKeys.addAll(requestedPages.keySet());
        long plannedBytes=bytes;int plannedCount=resident().size();
        for(var change:ordered) {
            if(!admitted(change.key(),x,y,z))continue;
            var old=sections.get(change.key());if(old!=null&&old.version==change.version())continue;
            if(change.vertices()==0) {
                if(old!=null){sections.remove(change.key());plannedBytes-=old.vertices.size();if(!dynamic(change.key()))plannedCount--;release(old);dirty=true;}
                continue;
            }
            long oldBytes=old==null?0:old.vertices.size();
            if(change.triangles().length>64L*1024*1024)continue;
            // Plan the whole eviction before releasing anything. New arrivals may replace
            // strictly farther residents; edits retain priority and their previous BLAS.
            var sizes=new LinkedHashMap<SectionKey,Long>();
            sections.forEach((key,value)->sizes.put(key,value.vertices.size()));
            var victims=evictions(sizes,protectedKeys,change.key(),plannedBytes,oldBytes,
                change.triangles().length,plannedCount,old!=null||dynamic(change.key()),x,y,z,requestedPages.keySet());
            if(victims==null)continue;
            for(var victim:victims) {
                var removed=sections.remove(victim);plannedBytes-=removed.vertices.size();plannedCount--;release(removed);dirty=true;
            }
            accepted.add(change);plannedBytes=plannedBytes-oldBytes+change.triangles().length;
            if(old==null&&!dynamic(change.key()))plannedCount++;
            dirty=true;
        }
        if(!dirty)return;
        boolean terrainDirty=changes.stream().anyMatch(change->!dynamic(change.key()));
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
                barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                for(var change:accepted) {
                    var buffer=uploads.remove(change.key());
                    try(var sectionStack=MemoryStack.stackPush()) {
                        var geometry=VulkanRtAccel.triangles(sectionStack,buffer,change.vertices());
                        if(material)geometry.get(0).flags(0); // Let any-hit reject cutout texels.
                        var old=sections.get(change.key());
                        boolean canRefit=dynamic(change.key())&&old!=null&&old.vertices.size()==buffer.size();
                        var blas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,geometry,change.vertices()/3,scratchAlignment,dynamic(change.key()),canRefit?old.blas:null);
                        if(canRefit)refits++;else builds++;
                        var source=ByteBuffer.wrap(change.triangles()).order(ByteOrder.nativeOrder());
                        var normals=ByteBuffer.allocate(change.vertices()/3*16).order(ByteOrder.nativeOrder());
                        for(int triangle=0;triangle<change.vertices()/3;triangle++)normals.putFloat(source.getFloat(triangle*120+20)).putFloat(source.getFloat(triangle*120+24)).putFloat(source.getFloat(triangle*120+28)).putFloat(0);
                        var previous=sections.put(change.key(),new Section(change.version(),buffer,blas,normals.array(),material&&!dynamic(change.key())?com.voxellight.rt.RtEmitterTable.extract(change.triangles()):List.of()));
                        bytes+=buffer.size();if(previous!=null)release(previous);
                    } catch(RuntimeException error) { buffer.close();throw error; }
                }
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
            }
            try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_tlas")){rebuildTlas(profileEncoder,x,y,z);}generation++;if(terrainDirty)terrainGeneration++;
        } finally { uploads.values().forEach(VulkanRtBuffer::close); }
    }
    private void rebuildTlas(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,double x,double y,double z) {
        if(tlas!=null) {tlas.close();tlas=null;}
        emitterData=ByteBuffer.allocateDirect(0);flameData=ByteBuffer.allocateDirect(0);
        if(sections.isEmpty()){packedGeometry.clear();return;}
        // Keep animated groups at the tail: their size changes must not relocate terrain.
        var entries=sections.entrySet().stream().sorted(Comparator.comparing(entry->dynamic(entry.getKey()))).toList();
        int triangles=sections.values().stream().mapToInt(section->section.normals.length/16).sum();
        if(triangles>=0x1000000)throw new IllegalStateException("RT instance normal base exceeds 24 bits");
        var encoder=device.createCommandEncoder();
        if(normalBuffer==null)normalBuffer=new VulkanRtBuffer(device,(64L*1024*1024/120)*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if(material&&geometryBuffer==null)geometryBuffer=new VulkanRtBuffer(device,64L*1024*1024,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_geometry_copy")){
            try(var stack=MemoryStack.stackPush()){
                var command=encoder.allocateAndBeginTransientCommandBuffer();
                barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
            }
            long offset=0,normalOffset=0;var next=new HashMap<SectionKey,Packed>();
            for(var entry:entries){
                var section=entry.getValue();var old=packedGeometry.get(entry.getKey());
                if(old==null||old.offset!=offset||old.version!=section.version){
                    if(material)encoder.copyToBuffer(section.vertices.slice(),geometryBuffer.slice(offset,section.vertices.size()));
                    encoder.writeToBuffer(normalBuffer.slice(normalOffset,section.normals.length),ByteBuffer.allocateDirect(section.normals.length).put(section.normals).flip());
                    geometryCopyBytes+=section.vertices.size();
                }
                next.put(entry.getKey(),new Packed(offset,section.version));offset+=section.vertices.size();normalOffset+=section.normals.length;
            }
            packedGeometry.clear();packedGeometry.putAll(next);
        }
        var instances=new VulkanRtBuffer(device,(long)sections.size()*VkAccelerationStructureInstanceKHR.SIZEOF,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
        try(var packed=allocateInstances(sections.size());var stack=MemoryStack.stackPush()) {
            int i=0,base=0;
            var emitters=new ArrayList<com.voxellight.rt.RtEmitterTable.Triangle>();
            for(var entry:entries) {
                var instance=packed.get(i++);var k=entry.getKey();
                for(var emitter:entry.getValue().emitters)emitters.add(com.voxellight.rt.RtEmitterTable.world(emitter,base,i-1,k.x(),k.y(),k.z()));
                instance.transform().matrix(0,1).matrix(5,1).matrix(10,1).matrix(3,dynamic(k)?0:k.x()*16f).matrix(7,dynamic(k)?0:k.y()*16f).matrix(11,dynamic(k)?0:k.z()*16f);
                instance.instanceCustomIndex(base).mask(255).instanceShaderBindingTableRecordOffset(0).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(entry.getValue().blas.address());
                base+=entry.getValue().normals.length/16;
            }
            var proposals=com.voxellight.rt.RtEmitterTable.proposals(emitters,x,y,z);emitterData=proposals.stochastic();flameData=proposals.flames();
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
    /** Null means no feasible admission; an empty list means no eviction is necessary. */
    static List<SectionKey> evictions(Map<SectionKey,Long> sizes,Set<SectionKey> protectedKeys,
            SectionKey incoming,long residentBytes,long previousBytes,long incomingBytes,int count,
            boolean replacement,double x,double y,double z) {
        return evictions(sizes,protectedKeys,incoming,residentBytes,previousBytes,incomingBytes,count,replacement,x,y,z,Set.of());
    }
    static List<SectionKey> evictions(Map<SectionKey,Long> sizes,Set<SectionKey> protectedKeys,SectionKey incoming,long residentBytes,long previousBytes,long incomingBytes,int count,boolean replacement,double x,double y,double z,Set<SectionKey> requested) {
        var victims=new ArrayList<SectionKey>();
        if(incomingBytes<=0||incomingBytes>64L*1024*1024)return null;
        var candidates=sizes.keySet().stream()
            .filter(key->!dynamic(key)).filter(key->!key.equals(incoming)&&!protectedKeys.contains(key))
            .filter(key->replacement||requested.contains(incoming)||distance(key,x,y,z)>distance(incoming,x,y,z))
            .sorted(Comparator.comparingDouble((SectionKey key)->distance(key,x,y,z)).reversed()).toList();
        for(var key:candidates) {
            if(fits(residentBytes,previousBytes,incomingBytes,count,replacement))break;
            residentBytes-=sizes.get(key);count--;victims.add(key);
        }
        return fits(residentBytes,previousBytes,incomingBytes,count,replacement)?victims:null;
    }
    private static boolean dynamic(SectionKey key){return key.y()==Integer.MIN_VALUE;}
    private static double distance(SectionKey key,double x,double y,double z) {
        if(dynamic(key))return 0;double dx=key.x()*16.+8-x,dy=key.y()*16.+8-y,dz=key.z()*16.+8-z;return dx*dx+dy*dy+dz*dz;
    }
    private static boolean admitted(SectionKey key,double x,double y,double z) {return dynamic(key)||Math.abs(key.x()*16.+8-x)<=512&&Math.abs(key.y()*16.+8-y)<=512&&Math.abs(key.z()*16.+8-z)<=512;}
    private void release(Section section) {bytes-=section.vertices.size();section.blas.close();section.vertices.close();}
    @Override public void close() { sections.values().forEach(this::release);sections.clear();packedGeometry.clear();requestedPages.clear();emitterData=ByteBuffer.allocateDirect(0);flameData=ByteBuffer.allocateDirect(0);if(tlas!=null)tlas.close();if(normalBuffer!=null)normalBuffer.close();if(geometryBuffer!=null)geometryBuffer.close();geometryBuffer=null;tlas=null;normalBuffer=null;generation++;terrainGeneration++; }
}
