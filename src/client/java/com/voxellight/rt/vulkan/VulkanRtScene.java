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

/** One scene commit per frame, with stable attribute ranges and persistent updateable acceleration structures. */
public final class VulkanRtScene implements AutoCloseable {
    private record Section(long version, VulkanRtBuffer vertices, VulkanRtAccel blas, byte[] normals, List<com.voxellight.rt.RtEmitterTable.Triangle> emitters,double x,double y,double z,boolean viewModel,boolean motionValid,long topology) {}
    private final VulkanDevice device;
    private final int scratchAlignment;
    private final boolean material;
    private VulkanRtBuffer geometryBuffer;
    private final LinkedHashMap<SectionKey,Long> requestedPages=new LinkedHashMap<>();
    public void requestPages(Set<SectionKey> keys){long now=System.nanoTime();requestedPages.entrySet().removeIf(entry->now-entry.getValue()>3_000_000_000L);for(var key:keys){requestedPages.remove(key);requestedPages.put(key,now);while(requestedPages.size()>16){var iterator=requestedPages.keySet().iterator();iterator.next();iterator.remove();}}}
    private final Map<SectionKey,Section> sections=new LinkedHashMap<>();
    private VulkanRtAccel tlas;
    private VulkanRtBuffer instanceBuffer;
    private int tlasCount;
    private long tlasRefits;
    private VulkanRtBuffer normalBuffer;
    private ByteBuffer flameData=ByteBuffer.allocateDirect(0);
    public ByteBuffer flameData(){return flameData.asReadOnlyBuffer();}
    public int flameCount(){return flameData.remaining()/64;}
    private ByteBuffer emitterData=ByteBuffer.allocateDirect(0);
    public ByteBuffer emitterData(){return emitterData.asReadOnlyBuffer();}
    public int emitterCount(){return emitterData.remaining()/64;}
    private long terrainGeneration,emitterGeneration;
    public long emitterGeneration(){return emitterGeneration;}
    private long emitterTerrainVersion=-1;
    private final Map<SectionKey,Section> previousPose=new HashMap<>();
    public long historyGeneration(){return terrainGeneration;}
    private long generation, builds, refits,tlasBuilds, bytes,geometryCopyBytes;
    static final long GEOMETRY_BYTES=64L*1024*1024;
    static final int TRIANGLE_CAPACITY=(int)(GEOMETRY_BYTES/120);
    private final com.voxellight.rt.RtGeometryAllocator allocator=new com.voxellight.rt.RtGeometryAllocator(TRIANGLE_CAPACITY);
    private record Packed(long offset,long version,int count,int identity){}
    private int nextIdentity=1;
    private final Map<SectionKey,Packed> packedGeometry=new HashMap<>();
    VulkanRtScene(VulkanDevice device,int scratchAlignment) {this(device,scratchAlignment,false);}
    VulkanRtScene(VulkanDevice device,int scratchAlignment,boolean material) { this.device=device;this.scratchAlignment=scratchAlignment;this.material=material; }
    public Set<SectionKey> resident() { var keys=new HashSet<>(sections.keySet());keys.removeIf(VulkanRtScene::dynamic);return Set.copyOf(keys); }
    public long generation() { return generation; }
    long tlas() { return tlas==null?0:tlas.handle(); }
    VulkanRtBuffer geometry() {return geometryBuffer;}
    VulkanRtBuffer normals() { return normalBuffer; }
    public String status() { return "sections="+resident().size()+", dynamicMeshes="+(sections.size()-resident().size())+", rayPriorityPages="+requestedPages.size()+", blasBuilds="+builds+", blasRefits="+refits+", geometryCopyBytes="+geometryCopyBytes+", tlasRefits="+tlasRefits+", tlasBuilds="+tlasBuilds+", sceneBytes="+bytes+", deterministicFlames="+flameCount()+", emissiveTriangles="+emitterCount()+", shaderGeometryBytes="+(geometryBuffer==null?0:geometryBuffer.size()); }
    private final LinkedHashMap<SectionKey,RtGeometryStream.Section> pending=new LinkedHashMap<>();
    public void collect(List<RtGeometryStream.Section> changes){for(var change:changes)pending.put(change.key(),change);}
    public void commit(com.mojang.blaze3d.systems.CommandEncoder encoder,double x,double y,double z){
        previousPose.clear();previousPose.putAll(sections);
        if(material&&geometryBuffer!=null){
            var nativeEncoder=device.createCommandEncoder();
            try(var stack=MemoryStack.stackPush()){
                var command=nativeEncoder.allocateAndBeginTransientCommandBuffer();
                barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));nativeEncoder.execute(command);
            }
            for(var entry:packedGeometry.entrySet())if(dynamic(entry.getKey()))nativeEncoder.copyToBuffer(geometryBuffer.slice(entry.getValue().offset,entry.getValue().count*120L),geometryBuffer.slice(GEOMETRY_BYTES+entry.getValue().offset,entry.getValue().count*120L));
        }
        var changes=List.copyOf(pending.values());pending.clear();update(encoder,changes,x,y,z);
        if(material&&normalBuffer!=null&&!sections.isEmpty()){
            var data=ByteBuffer.allocateDirect(sections.size()*32).order(ByteOrder.nativeOrder());
            for(var entry:orderedSections()){
                var current=entry.getValue();var previous=previousPose.get(entry.getKey());var allocation=packedGeometry.get(entry.getKey());
                boolean valid=current.motionValid&&previous!=null&&previous.topology==current.topology&&previous.normals.length==current.normals.length;
                data.putFloat((float)current.x).putFloat((float)current.y).putFloat((float)current.z).putFloat(current.viewModel?1:0);
                data.putFloat((float)(previous==null?current.x:previous.x)).putFloat((float)(previous==null?current.y:previous.y)).putFloat((float)(previous==null?current.z:previous.z)).putFloat(valid?allocation.identity:-allocation.identity);
            }
            encoder.writeToBuffer(normalBuffer.slice((long)TRIANGLE_CAPACITY*16,data.position()),data.flip());
        }
    }
    public void update(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,List<RtGeometryStream.Section> changes,double x,double y,double z) {
        geometryCopyBytes=0;boolean dirty=false;
        var encoder=device.createCommandEncoder();
        var iterator=sections.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var k=entry.getKey();
            if(!admitted(k,x,y,z)) {
                release(entry.getValue());iterator.remove();dirty=true;terrainGeneration++;
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
            var old=sections.get(change.key());
            // The instance motion table has a fixed 1024-entry tail. Never admit an out-of-range instance.
            if(old==null&&sections.size()+accepted.size()>=1024)continue;
            if(old!=null&&old.version==change.version()){
                if(old.x!=change.x()||old.y!=change.y()||old.z!=change.z()||old.viewModel!=change.viewModel()||old.motionValid!=change.motionValid()){
                    sections.put(change.key(),new Section(old.version,old.vertices,old.blas,old.normals,old.emitters,change.x(),change.y(),change.z(),change.viewModel(),change.motionValid(),old.topology));dirty=true;
                }
                continue;
            }
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
                var removed=sections.remove(victim);plannedBytes-=removed.vertices.size();plannedCount--;release(removed);dirty=true;terrainGeneration++;
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
            // Previous frames may still read vertices/AS. Same-queue dependency protects in-place updates.
            try(var stack=MemoryStack.stackPush()){
                var command=encoder.allocateAndBeginTransientCommandBuffer();
                barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
            }
            for(var change:accepted) {
                var old=sections.get(change.key());
                var buffer=old!=null&&old.vertices.size()==change.triangles().length?old.vertices:new VulkanRtBuffer(device,change.triangles().length,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
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
                        if(material&&needsAnyHit(change.triangles()))geometry.get(0).flags(0); // Opaque geometry bypasses any-hit.
                        var old=sections.get(change.key());
                        boolean canRefit=dynamic(change.key())&&old!=null&&old.vertices.size()==buffer.size();
                        var blas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,geometry,change.vertices()/3,scratchAlignment,dynamic(change.key()),canRefit?old.blas:null);
                        if(old!=null&&blas==old.blas)refits++;else builds++;
                        var source=ByteBuffer.wrap(change.triangles()).order(ByteOrder.nativeOrder());
                        var normals=ByteBuffer.allocate(change.vertices()/3*16).order(ByteOrder.nativeOrder());
                        for(int triangle=0;triangle<change.vertices()/3;triangle++)normals.putFloat(source.getFloat(triangle*120+20)).putFloat(source.getFloat(triangle*120+24)).putFloat(source.getFloat(triangle*120+28)).putFloat(0);
                        var previous=sections.put(change.key(),new Section(change.version(),buffer,blas,normals.array(),material&&!dynamic(change.key())?com.voxellight.rt.RtEmitterTable.extract(change.triangles()):List.of(),change.x(),change.y(),change.z(),change.viewModel(),change.motionValid(),topologyVersion(change.triangles())));
                        bytes+=buffer.size();
                        if(previous!=null){bytes-=previous.vertices.size();if(previous.blas!=blas)previous.blas.close();if(previous.vertices!=buffer)previous.vertices.close();}
                    } catch(RuntimeException error) {if(sections.get(change.key())==null||sections.get(change.key()).vertices!=buffer)buffer.close();throw error; }
                }
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
            }
            if(terrainDirty)terrainGeneration++;try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_tlas")){rebuildTlas(profileEncoder,x,y,z);}generation++;
        } finally { for(var buffer:uploads.values())if(sections.values().stream().noneMatch(section->section.vertices==buffer))buffer.close(); }
    }
    private void rebuildTlas(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,double x,double y,double z) {
        if(sections.isEmpty()){packedGeometry.clear();allocator.clear();emitterData=ByteBuffer.allocateDirect(0);flameData=ByteBuffer.allocateDirect(0);emitterGeneration++;if(tlas!=null)tlas.close();tlas=null;tlasCount=0;return;}
        // Keep animated groups at the tail: their size changes must not relocate terrain.
        var entries=orderedSections();
        int triangles=sections.values().stream().mapToInt(section->section.normals.length/16).sum();
        if(triangles>=0x1000000)throw new IllegalStateException("RT instance normal base exceeds 24 bits");
        var encoder=device.createCommandEncoder();
        if(normalBuffer==null)normalBuffer=new VulkanRtBuffer(device,(long)TRIANGLE_CAPACITY*16+1024*32L,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if(material&&geometryBuffer==null)geometryBuffer=new VulkanRtBuffer(device,GEOMETRY_BYTES*2,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_geometry_copy")){
            try(var stack=MemoryStack.stackPush()){
                var command=encoder.allocateAndBeginTransientCommandBuffer();
                barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
            }
            // Keep surviving ranges stable. Reclaim is safe after the read -> write dependency above.
            var removed=packedGeometry.entrySet().iterator();
            while(removed.hasNext()){var entry=removed.next();if(!sections.containsKey(entry.getKey())){allocator.release((int)(entry.getValue().offset/120),entry.getValue().count);removed.remove();}}
            for(var entry:entries){
                var section=entry.getValue();var old=packedGeometry.get(entry.getKey());int count=section.normals.length/16;
                if(old!=null&&old.count!=count){allocator.release((int)(old.offset/120),old.count);packedGeometry.remove(entry.getKey());old=null;}
                if(old==null){if(nextIdentity>=16777216)throw new IllegalStateException("RT surface identity exhausted; reset required");int base=allocator.allocate(count);if(base<0)throw new IllegalStateException("RT stable geometry arena fragmented/full");old=new Packed(base*120L,Long.MIN_VALUE,count,nextIdentity++);packedGeometry.put(entry.getKey(),old);}
                if(old.version!=section.version){
                    if(material)encoder.copyToBuffer(section.vertices.slice(),geometryBuffer.slice(old.offset,section.vertices.size()));
                    encoder.writeToBuffer(normalBuffer.slice(old.offset/120*16,section.normals.length),ByteBuffer.allocateDirect(section.normals.length).put(section.normals).flip());
                    geometryCopyBytes+=section.vertices.size();
                    packedGeometry.put(entry.getKey(),new Packed(old.offset,section.version,count,old.identity));
                }
            }
        }
        long instanceBytes=(long)sections.size()*VkAccelerationStructureInstanceKHR.SIZEOF;
        if(instanceBuffer==null||instanceBuffer.size()<instanceBytes){if(instanceBuffer!=null)instanceBuffer.close();instanceBuffer=new VulkanRtBuffer(device,Math.max(instanceBytes,1024L*VkAccelerationStructureInstanceKHR.SIZEOF),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);}
        var instances=instanceBuffer;
        try(var packed=allocateInstances(sections.size());var stack=MemoryStack.stackPush()) {
            int i=0;
            var emitters=new ArrayList<com.voxellight.rt.RtEmitterTable.Triangle>();
            for(var entry:entries) {
                var instance=packed.get(i++);var k=entry.getKey();int base=(int)(packedGeometry.get(k).offset/120);
                if(emitterTerrainVersion!=terrainGeneration)for(var emitter:entry.getValue().emitters)emitters.add(com.voxellight.rt.RtEmitterTable.world(emitter,base,base,k.x(),k.y(),k.z()));
                instance.transform().matrix(0,1).matrix(5,1).matrix(10,1).matrix(3,(float)entry.getValue().x).matrix(7,(float)entry.getValue().y).matrix(11,(float)entry.getValue().z);
                instance.instanceCustomIndex(base).mask(entry.getValue().viewModel?1:255).instanceShaderBindingTableRecordOffset(0).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(entry.getValue().blas.address());

            }
            if(emitterTerrainVersion!=terrainGeneration){var proposals=com.voxellight.rt.RtEmitterTable.proposals(emitters,x,y,z);emitterData=proposals.stochastic();flameData=proposals.flames();emitterGeneration++;emitterTerrainVersion=terrainGeneration;}
            encoder.writeToBuffer(instances.slice(0,instanceBytes),org.lwjgl.system.MemoryUtil.memByteBuffer(packed.address(),packed.remaining()*VkAccelerationStructureInstanceKHR.SIZEOF));
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack);geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address());
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            var previous=tlas;
            tlas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,geometry,sections.size(),scratchAlignment,true,tlasCount==sections.size()?previous:null);
            if(previous!=null&&previous!=tlas)previous.close();
            if(previous==tlas)tlasRefits++;else tlasBuilds++;tlasCount=sections.size();
            barrier(command,stack,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_SHADER_READ_BIT);
            VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);
        }
    }
    private List<Map.Entry<SectionKey,Section>> orderedSections(){return sections.entrySet().stream().sorted(Comparator.comparing(entry->dynamic(entry.getKey()))).toList();}
    static boolean needsAnyHit(byte[] triangles){var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());for(int offset=36;offset<triangles.length;offset+=120)if((data.getInt(offset)&1)!=0)return true;return false;}
    static long topologyVersion(byte[] triangles){
        var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());long hash=0xcbf29ce484222325L;
        for(int offset=0;offset<triangles.length;offset+=40)for(int index:new int[]{12,16,32,36}){hash^=Integer.toUnsignedLong(data.getInt(offset+index));hash*=0x100000001b3L;}
        return hash;
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
    @Override public void close() { sections.values().forEach(this::release);sections.clear();pending.clear();previousPose.clear();packedGeometry.clear();allocator.clear();requestedPages.clear();emitterData=ByteBuffer.allocateDirect(0);flameData=ByteBuffer.allocateDirect(0);if(instanceBuffer!=null)instanceBuffer.close();instanceBuffer=null;tlasCount=0;if(tlas!=null)tlas.close();if(normalBuffer!=null)normalBuffer.close();if(geometryBuffer!=null)geometryBuffer.close();geometryBuffer=null;tlas=null;normalBuffer=null;generation++;terrainGeneration++; }
}
