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
    private record Section(long version, VulkanRtBuffer vertices, VulkanRtAccel blas, byte[] normals, List<com.voxellight.rt.RtEmitterTable.Triangle> emitters,double x,double y,double z,boolean viewModel,boolean motionValid,long topology,int[] counts,byte[] positions,VulkanRtAccel opaqueBlas,VulkanRtBuffer ommIndices) {}
    private final VulkanDevice device;
    private final int scratchAlignment;
    private final VulkanRtScratch scratch;
    private final boolean material,ommEnabled;
    private VulkanRtBuffer geometryBuffer;
    private final LinkedHashMap<SectionKey,Long> requestedPages=new LinkedHashMap<>();
    public void requestPages(Set<SectionKey> keys){long now=System.nanoTime();requestedPages.entrySet().removeIf(entry->now-entry.getValue()>3_000_000_000L);for(var key:keys){requestedPages.remove(key);requestedPages.put(key,now);while(requestedPages.size()>16){var iterator=requestedPages.keySet().iterator();iterator.next();iterator.remove();}}}
    private final Map<SectionKey,int[]> opacityClassification=new HashMap<>();
    private final Map<SectionKey,long[]> opacityCounts=new HashMap<>();
    private final Map<SectionKey,RtGeometryStream.Section> opacitySources=new HashMap<>();
    private final Map<SectionKey,Section> sections=new LinkedHashMap<>();
    private VulkanRtAccel tlas,opaqueTlas;
    private VulkanRtBuffer opaqueInstanceBuffer;
    private int opaqueTlasCount;
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
    private long terrainGeneration,emitterGeneration,proposalRevision;
    private final float[] proposalFactors={1,1,1,1,1,1,1,1};
    public long proposalRevision(){return proposalRevision;}
    public float[] proposalFactors(){return proposalFactors.clone();}
    public void proposalFeedback(long sourceGeneration,long[] count,long[] visible){
        if(sourceGeneration!=emitterGeneration)return;boolean changed=false;
        for(int i=0;i<8;i++)if(count[i]>=16){float observed=Math.clamp((float)(visible[i]/((double)com.voxellight.rt.RtLightRuntime.ADAPTIVE_SCALE*count[i])),.25f,1f);float next=proposalFactors[i]*.85f+observed*.15f;if(Math.abs(next-proposalFactors[i])>.005f)changed=true;proposalFactors[i]=next;}
        if(changed)proposalRevision++;
    }
    public long emitterGeneration(){return emitterGeneration;}
    private long emitterTerrainVersion=-1;
    private final Map<SectionKey,Section> previousPose=new HashMap<>();
    public long historyGeneration(){return terrainGeneration;}
    private long opacityEpoch=Long.MIN_VALUE;
    private long generation, builds, refits,tlasBuilds, bytes,geometryCopyBytes,geometryCompactions;
    private long attributeOnlyUpdates,staticBuilds,dynamicBuilds,dynamicRefits,updatedTriangles,positionUpdates,layoutUpdates,opaqueUpdates;
    static final long GEOMETRY_BYTES=64L*1024*1024;
    static final int TRIANGLE_CAPACITY=(int)(GEOMETRY_BYTES/120);
    private final com.voxellight.rt.RtGeometryAllocator allocator=new com.voxellight.rt.RtGeometryAllocator(TRIANGLE_CAPACITY);
    record Packed(long offset,long version,int count,int identity){}
    private int nextIdentity=1;
    private final Map<SectionKey,Packed> packedGeometry=new HashMap<>();
    VulkanRtScene(VulkanDevice device,int scratchAlignment) {this(device,scratchAlignment,false);}
    VulkanRtScene(VulkanDevice device,int scratchAlignment,boolean material) { this.device=device;this.scratchAlignment=scratchAlignment;this.material=material;ommEnabled=material&&com.voxellight.rt.RtExecutionOptions.omm()&&device.vkDevice().getCapabilities().VK_EXT_opacity_micromap&&VulkanRtCapabilities.micromap(device.vkDevice().getPhysicalDevice());scratch=new VulkanRtScratch(device,scratchAlignment); }
    public Set<SectionKey> resident() { var keys=new HashSet<>(sections.keySet());keys.removeIf(VulkanRtScene::dynamic);return Set.copyOf(keys); }
    private Set<SectionKey> benchmarkTerrain=Set.of();
    public void benchmarkTerrain(Set<SectionKey> keys){benchmarkTerrain=Set.copyOf(keys);}
    public List<RtGeometryStream.Section> benchmarkSnapshot(double x,double y,double z){
        var sizes=new HashMap<SectionKey,Long>();sections.forEach((key,value)->{if(!dynamic(key))sizes.put(key,value.vertices.size());});
        var selected=benchmarkSelection(sizes,x,y,z);var result=new ArrayList<RtGeometryStream.Section>();
        for(var key:selected)result.add(RtGeometryStream.snapshot(key,sections.get(key).version));return List.copyOf(result);
    }
    public static List<SectionKey> benchmarkSelection(Map<SectionKey,Long> sizes,double x,double y,double z){
        long used=0,budget=GEOMETRY_BYTES-4L*1024*1024;var result=new ArrayList<SectionKey>();
        var keys=sizes.keySet().stream().sorted(Comparator.comparingDouble((SectionKey key)->distance(key,x,y,z)).thenComparingInt(SectionKey::x).thenComparingInt(SectionKey::y).thenComparingInt(SectionKey::z)).toList();
        for(var key:keys){long size=sizes.get(key);if(size>0&&size<=budget-used){used+=size;result.add(key);}}return List.copyOf(result);
    }
    public static long benchmarkSignature(List<RtGeometryStream.Section> snapshot){long signature=0;for(var section:snapshot)signature+=terrainEntrySignature(section.key(),section.version());return signature;}
    private static long terrainEntrySignature(SectionKey key,long version){long value=key.hashCode()*0x9e3779b97f4a7c15L+version;value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;return value^(value>>>27);}
    public long sceneBytes(){return bytes;}
    public long terrainSignature(){long signature=0;for(var entry:sections.entrySet())if(!dynamic(entry.getKey()))signature+=terrainEntrySignature(entry.getKey(),entry.getValue().version);return signature;}
    public long generation() { return generation; }
    long opaqueTlas(){return opaqueTlas==null?tlas():opaqueTlas.handle();}
    public boolean hasOpaque(){return opaqueTlas!=null;}
    public boolean hasNonOpaque(){return sections.values().stream().anyMatch(section->section.counts.length>1&&(section.counts[1]>0||section.counts[2]>0));}
    long tlas() { return tlas==null?0:tlas.handle(); }
    VulkanRtBuffer geometry() {return geometryBuffer;}
    VulkanRtBuffer normals() { return normalBuffer; }
    public String status() { return "sections="+resident().size()+", dynamicMeshes="+(sections.size()-resident().size())+", rayPriorityPages="+requestedPages.size()+", blasBuilds="+builds+", blasRefits="+refits+", blasAttributeOnly="+attributeOnlyUpdates+", blasStaticBuilds="+staticBuilds+", blasDynamicBuilds="+dynamicBuilds+", blasDynamicRefits="+dynamicRefits+", blasPositionUpdates="+positionUpdates+", blasLayoutUpdates="+layoutUpdates+", blasOpaqueUpdates="+opaqueUpdates+", blasUpdatedTriangles="+updatedTriangles+", geometryCopyBytes="+geometryCopyBytes+", geometryCompactions="+geometryCompactions+", tlasRefits="+tlasRefits+", tlasBuilds="+tlasBuilds+", sceneBytes="+bytes+", deterministicFlames="+flameCount()+", emissiveTriangles="+emitterCount()+", geometryRanges="+rangeCounts()+", ommCoverage="+opacityCounts()+", knownOpacityTexels="+com.voxellight.adapter.RtMaterialCoverage.knownOpacityTexels()+", ommValidity="+com.voxellight.adapter.RtMaterialCoverage.opacityValid()+", asScratchBytes="+scratch.bytes()+", asScratchBarriers="+scratch.barriers()+", asScratchSlices="+scratch.slices()+", sceneUpdate="+com.voxellight.rt.RtExecutionOptions.sceneUpdate()+", shaderGeometryBytes="+(geometryBuffer==null?0:geometryBuffer.size()); }
    private String opacityCounts(){long[] total=new long[3];for(var counts:opacityCounts.values())for(int i=0;i<3;i++)total[i]+=counts[i];return "opaque:"+total[0]+"/transparent:"+total[1]+"/unknown:"+total[2];}
    private String rangeCounts(){long[] counts=new long[3];for(var section:sections.values())for(int i=0;i<section.counts.length;i++)counts[i]+=section.counts[i];return "opaque:"+counts[0]+"/cutout:"+counts[1]+"/transmission:"+counts[2];}
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
            var data=ByteBuffer.allocateDirect(sections.size()*48).order(ByteOrder.nativeOrder());
            for(var entry:orderedSections()){
                var current=entry.getValue();var previous=previousPose.get(entry.getKey());var allocation=packedGeometry.get(entry.getKey());
                boolean valid=current.motionValid&&previous!=null&&previous.topology==current.topology&&previous.normals.length==current.normals.length;
                data.putFloat((float)current.x).putFloat((float)current.y).putFloat((float)current.z).putFloat(current.viewModel?1:0);
                data.putFloat((float)(previous==null?current.x:previous.x)).putFloat((float)(previous==null?current.y:previous.y)).putFloat((float)(previous==null?current.z:previous.z)).putFloat(valid?allocation.identity:-allocation.identity);
                data.putInt(0).putInt(current.counts[0]).putInt(current.counts.length>1?current.counts[0]+current.counts[1]:0).putInt(0);
            }
            encoder.writeToBuffer(normalBuffer.slice((long)TRIANGLE_CAPACITY*16,data.position()),data.flip());
        }
    }
    public void update(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,List<RtGeometryStream.Section> changes,double x,double y,double z) {
        geometryCopyBytes=0;long currentOpacity=com.voxellight.adapter.RtMaterialCoverage.opacityEpoch();boolean opacityDirty=ommEnabled&&opacityEpoch!=currentOpacity;boolean dirty=opacityDirty;opacityEpoch=currentOpacity;
        var encoder=device.createCommandEncoder();
        var iterator=sections.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var k=entry.getKey();
            if(!admitted(k,x,y,z)||!benchmarkTerrain.isEmpty()&&!dynamic(k)&&!benchmarkTerrain.contains(k)) {
                opacityClassification.remove(k);opacityCounts.remove(k);opacitySources.remove(k);release(entry.getValue());iterator.remove();dirty=true;if(!dynamic(k))terrainGeneration++;
            }
        }
        boolean terrainDirty=false;
        List<RtGeometryStream.Section> accepted=new ArrayList<>();
        var refresh=new LinkedHashMap<SectionKey,RtGeometryStream.Section>();if(opacityDirty)opacitySources.forEach((key,source)->{if(!Arrays.equals(opacityClassification.get(key),com.voxellight.adapter.RtMaterialCoverage.indices(source.triangles())))refresh.put(key,source);});for(var change:changes)refresh.put(change.key(),change);
        var ordered=new ArrayList<>(refresh.values());
        ordered.sort(Comparator.<RtGeometryStream.Section>comparingInt(change->benchmarkTerrain.contains(change.key())?-1:sections.containsKey(change.key())?0:1)
            .thenComparingDouble(change->distance(change.key(),x,y,z)));
        Set<SectionKey> protectedKeys=new HashSet<>();changes.forEach(change->protectedKeys.add(change.key()));protectedKeys.addAll(requestedPages.keySet());protectedKeys.addAll(benchmarkTerrain);
        long plannedBytes=bytes;int plannedCount=resident().size();
        for(var change:ordered) {
            if(!admitted(change.key(),x,y,z))continue;
            var old=sections.get(change.key());
            // The instance motion table has a fixed 1024-entry tail. Never admit an out-of-range instance.
            if(old==null&&sections.size()+accepted.size()>=1024)continue;
            if(old!=null&&old.version==change.version()&&!(opacityDirty&&!dynamic(change.key()))){
                if(old.x!=change.x()||old.y!=change.y()||old.z!=change.z()||old.viewModel!=change.viewModel()||old.motionValid!=change.motionValid()){
                    sections.put(change.key(),new Section(old.version,old.vertices,old.blas,old.normals,old.emitters,change.x(),change.y(),change.z(),change.viewModel(),change.motionValid(),old.topology,old.counts,old.positions,old.opaqueBlas,old.ommIndices));dirty=true;
                }
                continue;
            }
            if(change.vertices()==0) {
                if(old!=null){sections.remove(change.key());opacityClassification.remove(change.key());opacityCounts.remove(change.key());opacitySources.remove(change.key());plannedBytes-=old.vertices.size();if(!dynamic(change.key()))plannedCount--;release(old);dirty=true;if(!dynamic(change.key()))terrainDirty=true;}
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
                var removed=sections.remove(victim);opacityClassification.remove(victim);opacityCounts.remove(victim);opacitySources.remove(victim);plannedBytes-=removed.vertices.size();plannedCount--;release(removed);dirty=true;terrainGeneration++;
            }
            accepted.add(change);if(!dynamic(change.key()))terrainDirty=true;plannedBytes=plannedBytes-oldBytes+change.triangles().length;
            if(old==null&&!dynamic(change.key()))plannedCount++;
            dirty=true;
        }
        if(!dirty)return;
        // Only accepted content changes reset realtime history; unrelated/stale events do not.
        // Upload commands precede build commands through MC's encoder.execute ordering.
        Map<SectionKey,VulkanRtBuffer> uploads=new HashMap<>();
        var layouts=new HashMap<SectionKey,com.voxellight.rt.RtGeometryRanges>();
        var micromapUploads=new HashMap<SectionKey,VulkanRtBuffer>();
        try {
            // Previous frames may still read vertices/AS. Same-queue dependency protects in-place updates.
            try(var stack=MemoryStack.stackPush()){
                var command=encoder.allocateAndBeginTransientCommandBuffer();
                barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);scratch.submitted();
            }
            for(var change:accepted) {
                var old=sections.get(change.key());
                var buffer=old!=null&&old.vertices.size()==change.triangles().length?old.vertices:new VulkanRtBuffer(device,change.triangles().length,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
                var layout=material?com.voxellight.rt.RtGeometryRanges.split(change.triangles(),offset->com.voxellight.adapter.RtMaterialCoverage.transmissive(change.triangles(),offset)):new com.voxellight.rt.RtGeometryRanges(change.triangles(),new int[]{change.vertices()/3});
                layouts.put(change.key(),layout);uploads.put(change.key(),buffer);
                if(ommEnabled&&!dynamic(change.key())&&layout.counts()[1]>0){
                    var indices=new VulkanRtBuffer(device,layout.counts()[1]*4L,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
                    micromapUploads.put(change.key(),indices);var data=ByteBuffer.allocateDirect(layout.counts()[1]*4).order(ByteOrder.nativeOrder());
                    var classifications=new int[layout.counts()[1]];var counts=new long[3];for(int triangle=0;triangle<layout.counts()[1];triangle++){int classification=com.voxellight.adapter.RtMaterialCoverage.opacity(layout.triangles(),(layout.base(1)+triangle)*120);classifications[triangle]=classification;data.putInt(classification);counts[classification==-2?0:classification==-1?1:2]++;}opacityClassification.put(change.key(),classifications);opacityCounts.put(change.key(),counts);
                    encoder.writeToBuffer(indices.slice(),data.flip());
                }
                encoder.writeToBuffer(buffer.slice(),ByteBuffer.allocateDirect(layout.triangles().length).put(layout.triangles()).flip());
            }
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_blas_submit");var stack=MemoryStack.stackPush()) {
                try(var nativeProfile=com.voxellight.adapter.RenderPassProfile.beginNative(command,"vulkan_rt_blas")){
                barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                for(var change:accepted) {
                    var buffer=uploads.remove(change.key());
                    VulkanRtAccel built=null,opaqueBuilt=null;var retained=sections.get(change.key());
                    try(var sectionStack=MemoryStack.stackPush()) {
                        var layout=layouts.get(change.key());
                        var geometry=rangeGeometry(sectionStack,buffer,layout.counts());
                        var omm=micromapUploads.get(change.key());
                        if(omm!=null){var opacity=VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(sectionStack).sType$Default().indexType(VK_INDEX_TYPE_UINT32).indexStride(4).micromap(0);opacity.indexBuffer().deviceAddress(omm.address());geometry.get(1).geometry().triangles().pNext(opacity.address());}
                        var old=sections.get(change.key());
                        byte[] positions=dynamic(change.key())?layout.positions():null;
                        boolean attributesOnly=com.voxellight.rt.RtExecutionOptions.sceneUpdate()==com.voxellight.rt.RtExecutionOptions.SceneUpdate.OPTIMIZED&&dynamic(change.key())&&old!=null&&old.vertices==buffer&&Arrays.equals(old.counts,layout.counts())&&Arrays.equals(old.positions,positions);
                        VulkanRtAccel blas,opaque;
                        if(attributesOnly){blas=old.blas;opaque=old.opaqueBlas;attributeOnlyUpdates++;}
                        else{
                            if(dynamic(change.key())&&old!=null){if(!Arrays.equals(old.positions,positions))positionUpdates++;if(!Arrays.equals(old.counts,layout.counts())||old.vertices.size()!=buffer.size())layoutUpdates++;}
                            boolean canRefit=dynamic(change.key())&&old!=null&&old.vertices.size()==buffer.size();
                            blas=built=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,geometry,layout.counts(),scratch,dynamic(change.key()),canRefit?old.blas:null);
                            updatedTriangles+=change.vertices()/3;
                            if(old!=null&&blas==old.blas){refits++;dynamicRefits++;}else{builds++;if(dynamic(change.key()))dynamicBuilds++;else staticBuilds++;}
                            opaque=null;
                            if(material&&layout.counts()[0]>0){opaqueUpdates++;var opaqueGeometry=VulkanRtAccel.triangles(sectionStack,buffer,layout.counts()[0]*3);opaque=opaqueBuilt=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,opaqueGeometry,layout.counts()[0],scratch,dynamic(change.key()),old==null?null:old.opaqueBlas);}
                        }
                        var source=ByteBuffer.wrap(layout.triangles()).order(ByteOrder.nativeOrder());
                        var normals=ByteBuffer.allocate(change.vertices()/3*16).order(ByteOrder.nativeOrder());
                        for(int triangle=0;triangle<change.vertices()/3;triangle++)normals.putFloat(source.getFloat(triangle*120+20)).putFloat(source.getFloat(triangle*120+24)).putFloat(source.getFloat(triangle*120+28)).putFloat(0);
                        var previous=sections.put(change.key(),new Section(change.version(),buffer,blas,normals.array(),material&&!dynamic(change.key())?com.voxellight.rt.RtEmitterTable.extract(layout.triangles()):List.of(),change.x(),change.y(),change.z(),change.viewModel(),change.motionValid(),topologyVersion(layout.triangles()),layout.counts(),positions,opaque,omm));
                        micromapUploads.remove(change.key());if(ommEnabled&&!dynamic(change.key())&&omm!=null)opacitySources.put(change.key(),change);else{opacitySources.remove(change.key());opacityClassification.remove(change.key());opacityCounts.remove(change.key());}
                        bytes+=buffer.size();
                        if(previous!=null){bytes-=previous.vertices.size();if(previous.ommIndices!=null)previous.ommIndices.close();if(previous.opaqueBlas!=null&&previous.opaqueBlas!=opaque)previous.opaqueBlas.close();if(previous.blas!=blas)previous.blas.close();if(previous.vertices!=buffer)previous.vertices.close();}
                    } catch(RuntimeException error) {if(sections.get(change.key())==retained){if(built!=null&&(retained==null||built!=retained.blas))built.close();if(opaqueBuilt!=null&&(retained==null||opaqueBuilt!=retained.opaqueBlas))opaqueBuilt.close();if(retained==null||retained.vertices!=buffer)buffer.close();}throw error; }
                }
                } // Record native end timestamp before ending/submitting the command buffer.
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);scratch.submitted();
            }
            if(terrainDirty)terrainGeneration++;try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_tlas")){rebuildTlas(profileEncoder,x,y,z);}generation++;
        } finally {for(var buffer:micromapUploads.values())buffer.close(); for(var buffer:uploads.values())if(sections.values().stream().noneMatch(section->section.vertices==buffer))buffer.close(); }
    }
    private void rebuildTlas(com.mojang.blaze3d.systems.CommandEncoder profileEncoder,double x,double y,double z) {
        if(sections.isEmpty()){packedGeometry.clear();allocator.clear();emitterData=ByteBuffer.allocateDirect(0);flameData=ByteBuffer.allocateDirect(0);emitterGeneration++;if(tlas!=null)tlas.close();tlas=null;tlasCount=0;if(opaqueTlas!=null)opaqueTlas.close();opaqueTlas=null;opaqueTlasCount=0;return;}
        // Keep animated groups at the tail: their size changes must not relocate terrain.
        var entries=orderedSections();
        int triangles=sections.values().stream().mapToInt(section->section.normals.length/16).sum();
        if(triangles>=0x1000000)throw new IllegalStateException("RT instance normal base exceeds 24 bits");
        var encoder=device.createCommandEncoder();
        if(normalBuffer==null)normalBuffer=new VulkanRtBuffer(device,(long)TRIANGLE_CAPACITY*16+1024*48L,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if(material&&geometryBuffer==null)geometryBuffer=new VulkanRtBuffer(device,GEOMETRY_BYTES*2,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        try(var profile=com.voxellight.adapter.RenderPassProfile.begin(profileEncoder,"vulkan_rt_geometry_copy")){
            try(var stack=MemoryStack.stackPush()){
                var command=encoder.allocateAndBeginTransientCommandBuffer();
                barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);scratch.submitted();
            }
            // Release every removed/resized range before allocating any replacement.
            // A grow followed by a shrink must fit the final frame, not both old and new sizes.
            var counts=new LinkedHashMap<SectionKey,Integer>();
            for(var entry:entries)counts.put(entry.getKey(),entry.getValue().normals.length/16);
            if(allocateRanges(packedGeometry,counts,allocator)){
                geometryCompactions++;terrainGeneration++;previousPose.clear();
            }
            for(var entry:entries){
                var section=entry.getValue();var old=packedGeometry.get(entry.getKey());int count=section.normals.length/16;
                if(old.identity==0){
                    if(nextIdentity>=16777216)throw new IllegalStateException("RT surface identity exhausted; reset required");
                    old=new Packed(old.offset,old.version,count,nextIdentity++);packedGeometry.put(entry.getKey(),old);
                }
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
                instance.instanceCustomIndex(base).mask(entry.getValue().viewModel?1:255).instanceShaderBindingTableRecordOffset(0).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR|(entry.getValue().ommIndices!=null&&!com.voxellight.adapter.RtMaterialCoverage.opacityValid()?EXTOpacityMicromap.VK_GEOMETRY_INSTANCE_DISABLE_OPACITY_MICROMAPS_BIT_EXT:0)).accelerationStructureReference(entry.getValue().blas.address());

            }
            if(emitterTerrainVersion!=terrainGeneration){var proposals=com.voxellight.rt.RtEmitterTable.proposals(emitters,x,y,z);emitterData=proposals.stochastic();flameData=proposals.flames();emitterGeneration++;emitterTerrainVersion=terrainGeneration;}
            encoder.writeToBuffer(instances.slice(0,instanceBytes),org.lwjgl.system.MemoryUtil.memByteBuffer(packed.address(),packed.remaining()*VkAccelerationStructureInstanceKHR.SIZEOF));
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack);geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address());
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            var previous=tlas;
            tlas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,geometry,sections.size(),scratch,true,tlasCount==sections.size()?previous:null);
            if(previous!=null&&previous!=tlas)previous.close();
            if(previous==tlas)tlasRefits++;else tlasBuilds++;tlasCount=sections.size();
            barrier(command,stack,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_SHADER_READ_BIT);
            VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);scratch.submitted();
        }
        if(material)rebuildOpaqueTlas();
    }
    static VkAccelerationStructureGeometryKHR.Buffer rangeGeometry(MemoryStack stack,VulkanRtBuffer vertices,int[] counts){
        var geometry=VkAccelerationStructureGeometryKHR.calloc(counts.length,stack);int base=0;
        for(int i=0;i<counts.length;i++){
            var g=geometry.get(i);g.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(i==0?VK_GEOMETRY_OPAQUE_BIT_KHR:0);
            g.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(40).maxVertex(Math.max(0,counts[i]*3-1)).indexType(VK_INDEX_TYPE_NONE_KHR).vertexData().deviceAddress(vertices.address()+(counts[i]==0?0:base*120L));base+=counts[i];
        }
        return geometry;
    }
    private void rebuildOpaqueTlas(){
        var entries=orderedSections().stream().filter(entry->!entry.getValue().viewModel&&entry.getValue().opaqueBlas!=null).toList();
        if(entries.isEmpty()){if(opaqueTlas!=null)opaqueTlas.close();opaqueTlas=null;opaqueTlasCount=0;return;}
        var encoder=device.createCommandEncoder();long bytes=(long)entries.size()*VkAccelerationStructureInstanceKHR.SIZEOF;
        if(opaqueInstanceBuffer==null||opaqueInstanceBuffer.size()<bytes){if(opaqueInstanceBuffer!=null)opaqueInstanceBuffer.close();opaqueInstanceBuffer=new VulkanRtBuffer(device,Math.max(bytes,1024L*VkAccelerationStructureInstanceKHR.SIZEOF),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);}
        try(var instances=allocateInstances(entries.size());var stack=MemoryStack.stackPush()){
            int i=0;for(var entry:entries){var section=entry.getValue();var instance=instances.get(i++);instance.transform().matrix(0,1).matrix(5,1).matrix(10,1).matrix(3,(float)section.x).matrix(7,(float)section.y).matrix(11,(float)section.z);instance.mask(254).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(section.opaqueBlas.address());}
            encoder.writeToBuffer(opaqueInstanceBuffer.slice(0,bytes),org.lwjgl.system.MemoryUtil.memByteBuffer(instances.address(),(int)bytes));
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack);geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);geometry.get(0).geometry().instances().sType$Default().data().deviceAddress(opaqueInstanceBuffer.address());
            var command=encoder.allocateAndBeginTransientCommandBuffer();barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            var previous=opaqueTlas;opaqueTlas=VulkanRtAccel.build(device,command,VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,geometry,entries.size(),scratch,true,opaqueTlasCount==entries.size()?previous:null);opaqueTlasCount=entries.size();if(previous!=null&&previous!=opaqueTlas)previous.close();
            barrier(command,stack,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);scratch.submitted();
        }
    }
    /** Plan the whole final layout before any GPU copies. True means fragmented ranges were repacked. */
    static boolean allocateRanges(Map<SectionKey,Packed> ranges,Map<SectionKey,Integer> counts,com.voxellight.rt.RtGeometryAllocator allocator){
        long total=0;for(int count:counts.values()){if(count<=0)throw new IllegalArgumentException("Empty geometry range");total+=count;}
        if(total>allocator.capacity())throw new IllegalStateException("RT final geometry exceeds admitted capacity");
        var removed=ranges.entrySet().iterator();
        while(removed.hasNext()){
            var entry=removed.next();if(!Objects.equals(counts.get(entry.getKey()),entry.getValue().count)){
                allocator.release((int)(entry.getValue().offset/120),entry.getValue().count);removed.remove();
            }
        }
        for(var entry:counts.entrySet())if(!ranges.containsKey(entry.getKey())){
            int base=allocator.allocate(entry.getValue());
            if(base<0){
                // Total capacity was checked above. This is fragmentation, so rebuild only the
                // attribute layout; existing BLAS geometry and surface identities remain intact.
                allocator.clear();
                for(var current:counts.entrySet()){
                    var old=ranges.get(current.getKey());int offset=allocator.allocate(current.getValue());
                    ranges.put(current.getKey(),new Packed(offset*120L,Long.MIN_VALUE,current.getValue(),old==null?0:old.identity));
                }
                return true;
            }
            ranges.put(entry.getKey(),new Packed(base*120L,Long.MIN_VALUE,entry.getValue(),0));
        }
        return false;
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
    private void release(Section section) {if(section.ommIndices!=null)section.ommIndices.close();if(section.opaqueBlas!=null)section.opaqueBlas.close();bytes-=section.vertices.size();section.blas.close();section.vertices.close();}
    @Override public void close() {scratch.close();if(opaqueTlas!=null)opaqueTlas.close();opaqueTlas=null;if(opaqueInstanceBuffer!=null)opaqueInstanceBuffer.close();opaqueInstanceBuffer=null; sections.values().forEach(this::release);sections.clear();opacityClassification.clear();opacityCounts.clear();opacitySources.clear();pending.clear();previousPose.clear();packedGeometry.clear();allocator.clear();requestedPages.clear();emitterData=ByteBuffer.allocateDirect(0);flameData=ByteBuffer.allocateDirect(0);if(instanceBuffer!=null)instanceBuffer.close();instanceBuffer=null;tlasCount=0;if(tlas!=null)tlas.close();if(normalBuffer!=null)normalBuffer.close();if(geometryBuffer!=null)geometryBuffer.close();geometryBuffer=null;tlas=null;normalBuffer=null;generation++;terrainGeneration++; }
}
