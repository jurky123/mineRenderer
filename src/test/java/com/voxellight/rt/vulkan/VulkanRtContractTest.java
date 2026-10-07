package com.voxellight.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class VulkanRtContractTest {
    @Test void scratchSlicesAreAlignedDisjointAndCanRestartOnlyAtBatchBoundary(){
        long address=12345,cursor=0,previousEnd=address;for(long size:new long[]{1,257,4096,0,99999}){long offset=VulkanRtScratch.offset(address,cursor,256);long start=address+offset;assertEquals(0,start%256);assertTrue(start>=previousEnd);previousEnd=start+size;cursor=offset+size;}
        assertTrue(cursor>199);assertEquals(199,VulkanRtScratch.offset(address,0,256));
        assertThrows(ArithmeticException.class,()->VulkanRtScratch.offset(Long.MAX_VALUE,123,256));
    }
    @Test void vanillaAtlasAnimationUploadDrawsOnlyAnimatedSpritesWithoutClearingTheAtlas()throws Exception{
        var atlas=new ClassNode();new ClassReader("net.minecraft.client.renderer.texture.TextureAtlas").accept(atlas,0);
        var upload=atlas.methods.stream().filter(m->m.name.equals("uploadAnimationFrames")).findFirst().orElseThrow();
        var calls=Arrays.stream(upload.instructions.toArray()).filter(i->i instanceof MethodInsnNode).map(i->(MethodInsnNode)i).toList();
        assertTrue(calls.stream().anyMatch(c->c.owner.equals("net/minecraft/client/renderer/texture/SpriteContents$AnimationState")&&c.name.equals("drawToAtlas")));
        assertTrue(calls.stream().anyMatch(c->c.name.equals("createRenderPass")));
        assertFalse(calls.stream().anyMatch(c->c.name.startsWith("clear")||c.name.equals("writeToTexture")||c.name.equals("copyTextureToTexture")));
        assertTrue(calls.stream().anyMatch(c->c.owner.equals("java/util/Optional")&&c.name.equals("empty")),"Native animation pass retains existing static atlas pixels");
    }
    @Test void multiGeometryRefitSignatureIncludesPerRangeCountsFlagsAndLayout(){
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            var g=org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR.calloc(3,stack);
            for(int i=0;i<3;i++){g.get(i).sType$Default().geometryType(0).flags(i==0?1:0);g.get(i).geometry().triangles().sType$Default().vertexStride(40).vertexFormat(106).maxVertex(29).indexType(1000165000);}
            int type=org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
            String initial=VulkanRtAccel.signature(type,g,new int[]{10,20,30},1);
            assertNotEquals(initial,VulkanRtAccel.signature(type,g,new int[]{11,19,30},1));
            g.get(1).flags(1);assertNotEquals(initial,VulkanRtAccel.signature(type,g,new int[]{10,20,30},1));g.get(1).flags(0);
            g.get(2).geometry().triangles().vertexData().deviceAddress(777);assertEquals(initial,VulkanRtAccel.signature(type,g,new int[]{10,20,30},1));
        }
    }
    @Test void visibilitySbtKeepsBothMissesAndAllSevenHitRecordsAligned(){
        var layout=VulkanSbt.layout(32,32,64,4096,2,7);
        assertEquals(0,layout.missOffset()%64);assertEquals(0,layout.hitOffset()%64);
        assertTrue(layout.hitOffset()>=layout.missOffset()+2*layout.stride());assertEquals(layout.hitOffset()+7*layout.stride(),layout.bytes());
    }
    @Test void realtimeTransmissionSbtHasDistinctMissAndTenNonoverlappingHitRecords(){
        var layout=VulkanSbt.layout(32,32,64,4096,3,10);
        assertEquals(0,layout.missOffset()%64);assertEquals(0,layout.hitOffset()%64);
        assertTrue(layout.hitOffset()>=layout.missOffset()+3*layout.stride());
        assertEquals(layout.hitOffset()+10*layout.stride(),layout.bytes());
    }
    @Test void packagedRrRuntimesAndExtensionQueryAreUsableWithoutInitializingAGpu()throws Exception{
        try(var jar=new ZipFile(System.getProperty("voxellight.modJar"))){
            for(String path:List.of("windows-x86_64/voxellight_dlss.dll","windows-x86_64/nvngx_dlssd.dll","linux-x86_64/libvoxellight_dlss.so","linux-x86_64/libnvidia-ngx-dlssd.so.310.9.1","NVIDIA-DLSS-LICENSE.txt","SDK.txt"))assertNotNull(jar.getEntry("voxellight/dlss/"+path),path);
            assertTrue(jar.stream().noneMatch(e->e.getName().contains("dlssg")),"No frame-generation runtime");
        }
        com.voxellight.nvidia.DlssNative.load();
        for(boolean device:new boolean[]{false,true}){
            var extensions=com.voxellight.nvidia.DlssNative.extensions(device);assertNotNull(extensions);
            for(String extension:extensions)assertTrue(extension.startsWith("VK_"),extension);
        }
    }
    @Test void allResizedRangesAreReleasedBeforeGrowingReplacements(){
        var allocator=new com.voxellight.rt.RtGeometryAllocator(100);
        var a=new com.voxellight.world.SectionKey(0,0,0);var b=new com.voxellight.world.SectionKey(1,0,0);
        var ranges=new LinkedHashMap<com.voxellight.world.SectionKey,VulkanRtScene.Packed>();
        ranges.put(a,new VulkanRtScene.Packed(allocator.allocate(60)*120L,1,60,1));
        ranges.put(b,new VulkanRtScene.Packed(allocator.allocate(40)*120L,1,40,2));
        var counts=new LinkedHashMap<com.voxellight.world.SectionKey,Integer>();counts.put(a,80);counts.put(b,20);
        assertFalse(VulkanRtScene.allocateRanges(ranges,counts,allocator));
        assertEquals(0,ranges.get(a).offset());assertEquals(80*120,ranges.get(b).offset());assertEquals(-1,allocator.allocate(1));
    }
    @Test void fragmentedArenaRepacksFinalLayoutWithoutChangingSurvivingIdentities(){
        var allocator=new com.voxellight.rt.RtGeometryAllocator(100);
        var a=new com.voxellight.world.SectionKey(0,0,0);var hole=new com.voxellight.world.SectionKey(1,0,0);
        var b=new com.voxellight.world.SectionKey(2,0,0);var incoming=new com.voxellight.world.SectionKey(3,0,0);
        var ranges=new LinkedHashMap<com.voxellight.world.SectionKey,VulkanRtScene.Packed>();
        ranges.put(a,new VulkanRtScene.Packed(allocator.allocate(20)*120L,10,20,11));
        ranges.put(hole,new VulkanRtScene.Packed(allocator.allocate(30)*120L,10,30,12));
        ranges.put(b,new VulkanRtScene.Packed(allocator.allocate(20)*120L,10,20,13));
        var counts=new LinkedHashMap<com.voxellight.world.SectionKey,Integer>();counts.put(a,20);counts.put(b,20);counts.put(incoming,50);
        assertTrue(VulkanRtScene.allocateRanges(ranges,counts,allocator));
        assertEquals(11,ranges.get(a).identity());assertEquals(13,ranges.get(b).identity());assertEquals(0,ranges.get(incoming).identity());
        assertEquals(Long.MIN_VALUE,ranges.get(b).version());assertEquals(20*120,ranges.get(b).offset());assertFalse(ranges.containsKey(hole));
        assertEquals(90,allocator.allocate(10));
    }
    @Test void impossibleFinalLayoutIsRejectedBeforeMutatingExistingRanges(){
        var allocator=new com.voxellight.rt.RtGeometryAllocator(100);var key=new com.voxellight.world.SectionKey(0,0,0);
        var old=new VulkanRtScene.Packed(allocator.allocate(60)*120L,10,60,11);
        var ranges=new HashMap<com.voxellight.world.SectionKey,VulkanRtScene.Packed>();ranges.put(key,old);
        assertThrows(IllegalStateException.class,()->VulkanRtScene.allocateRanges(ranges,Map.of(key,101),allocator));
        assertEquals(old,ranges.get(key));assertEquals(60,allocator.allocate(40));
    }
    @Test void opaqueClassificationAndMotionTopologyUseMaterialLayoutInsteadOfWorldPose(){
        var vertices=ByteBuffer.allocate(120).order(ByteOrder.nativeOrder());
        long topology=VulkanRtScene.topologyVersion(vertices.array());
        vertices.putFloat(0,20000);assertEquals(topology,VulkanRtScene.topologyVersion(vertices.array()));
        assertFalse(VulkanRtScene.needsAnyHit(vertices.array()));
        vertices.putInt(36,1);assertTrue(VulkanRtScene.needsAnyHit(vertices.array()));assertNotEquals(topology,VulkanRtScene.topologyVersion(vertices.array()));
    }
    @Test void shippedArtifactContainsNoLegacyTracerOrNativeCompiler()throws Exception{
        try(var jar=new ZipFile(System.getProperty("voxellight.modJar"))){
            for(var entry:Collections.list(jar.entries())){
                String name=entry.getName();
                for(String removed:List.of("voxellight/native/","RtxLightingPass.class","PathTracePass.class","OptixBridge.class","OptixNative.class","VulkanCudaInterop.class","pathtrace_capture.fsh","rt_composite.fsh",".ptx",".optixir"))assertFalse(name.contains(removed),name);
            }
            assertNotNull(jar.getEntry("com/voxellight/adapter/VulkanRtAccumulation.class"));
            assertNotNull(jar.getEntry("assets/voxellight/shaders/vulkan_rt_accumulate.fsh"));
        }
    }

    @Test void fullSceneAdmitsNearTerrainAndTracksCameraMovement() {
        long mib=1024L*1024;
        var near=new com.voxellight.world.SectionKey(0,0,0);
        var far=new com.voxellight.world.SectionKey(6,0,0);
        var incoming=new com.voxellight.world.SectionKey(1,0,0);
        var sizes=Map.of(near,32*mib,far,32*mib);
        assertEquals(List.of(far),VulkanRtScene.evictions(sizes,Set.of(incoming),incoming,64*mib,0,8*mib,2,false,8,8,8));
        assertNull(VulkanRtScene.evictions(sizes,Set.of(),new com.voxellight.world.SectionKey(8,0,0),64*mib,0,8*mib,2,false,8,8,8));
        assertEquals(List.of(near),VulkanRtScene.evictions(sizes,Set.of(),new com.voxellight.world.SectionKey(7,0,0),64*mib,0,8*mib,2,false,120,8,8));
    }
    @Test void requestedReflectionPageCanEnterAFullNearWorkingSetWithoutEvictingAnotherProtectedPage(){
        long mib=1024L*1024;var near=new com.voxellight.world.SectionKey(0,0,0);var other=new com.voxellight.world.SectionKey(1,0,0);var reflection=new com.voxellight.world.SectionKey(20,0,0);var sizes=Map.of(near,32*mib,other,32*mib);
        assertNull(VulkanRtScene.evictions(sizes,Set.of(near),reflection,64*mib,0,8*mib,2,false,8,8,8));
        assertEquals(List.of(other),VulkanRtScene.evictions(sizes,Set.of(near),reflection,64*mib,0,8*mib,2,false,8,8,8,Set.of(reflection)));
    }
    @Test void admissionIsAtomicAndPreservesEditedSections() {
        long mib=1024L*1024;
        var edited=new com.voxellight.world.SectionKey(0,0,0);
        var other=new com.voxellight.world.SectionKey(1,0,0);
        var sizes=Map.of(edited,32*mib,other,32*mib);
        assertEquals(List.of(),VulkanRtScene.evictions(sizes,Set.of(edited),edited,64*mib,32*mib,32*mib,2,true,8,8,8));
        assertEquals(List.of(other),VulkanRtScene.evictions(sizes,Set.of(edited),edited,64*mib,32*mib,33*mib,2,true,8,8,8));
        assertNull(VulkanRtScene.evictions(sizes,Set.of(edited,other),edited,64*mib,32*mib,33*mib,2,true,8,8,8));
        assertEquals(2,sizes.size(),"Failed plans must not remove any resident");
        assertNull(VulkanRtScene.evictions(sizes,Set.of(),edited,64*mib,32*mib,65*mib,2,true,8,8,8));
    }
    @Test void backendSwitchPreservesRasterAndDebugRunsBeforeProjectionReset() throws Exception {
        for(String name:List.of("VulkanPathTracer")) {
            var node=new ClassNode();new ClassReader("com.voxellight.adapter."+name).accept(node,0);
            for(var method:node.methods)for(var instruction:method.instructions)
                if(instruction instanceof MethodInsnNode call)
                    assertNotEquals("invalidateCompiledGeometry",call.name,"RT admission must preserve native raster buffers");
        }
        var probe=new ClassNode();new ClassReader("com.voxellight.adapter.RenderProbe").accept(probe,0);
        var render=probe.methods.stream().filter(m->m.name.equals("render")).findFirst().orElseThrow();
        var calls=Arrays.stream(render.instructions.toArray()).filter(i->i instanceof MethodInsnNode call&&call.owner.equals("com/voxellight/adapter/LightingResolvePass"))
            .map(i->((MethodInsnNode)i).name).toList();
        assertTrue(calls.indexOf("displayVulkanRt")>=0);
        assertTrue(calls.indexOf("displayVulkanRt")<calls.indexOf("endFrame"),"Debug view requires the current camera projection");
    }

    @Test void rtBufferDescriptorsActuallyWriteOneBinding() {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            for(int binding=1;binding<=7;binding++) {
                var info=org.lwjgl.vulkan.VkDescriptorBufferInfo.calloc(1,stack).buffer(123).offset(0).range(binding==3?96:4096);
                var write=org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(stack);
                int type=binding==3?org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                VulkanRtPipeline.bufferWrite(write,456,binding,type,info);
                assertEquals(1,write.descriptorCount());assertEquals(binding,write.dstBinding());assertEquals(type,write.descriptorType());
                assertEquals(123,write.pBufferInfo().get(0).buffer());assertEquals(info.get(0).range(),write.pBufferInfo().get(0).range());
            }
        }
    }

    @Test void blasAndTlasBuildsIncludeTheirGeometry() {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            for(int type:new int[]{org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR}) {
                var geometry=org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR.calloc(1,stack);
                int kind=type==org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR?org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR:org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
                geometry.get(0).sType$Default().geometryType(kind);
                var info=org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack);
                VulkanRtAccel.buildInfo(info,type,geometry);
                assertEquals(1,info.geometryCount());assertEquals(type,info.type());
                assertEquals(geometry.address(),info.pGeometries().address());assertEquals(kind,info.pGeometries().get(0).geometryType());
                assertEquals(org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR,info.mode());
            }
        }
    }

    @Test void sectionReplacementBudgetUsesNetSizeAndKeepsExistingSlots() {
        long limit=64L*1024*1024;
        assertTrue(VulkanRtScene.fits(limit,4096,4096,512,true));
        assertFalse(VulkanRtScene.fits(limit,4096,4216,512,true));
        assertTrue(VulkanRtScene.fits(limit-1024,4096,4216,511,true));
        assertFalse(VulkanRtScene.fits(limit,0,120,511,false));
        assertFalse(VulkanRtScene.fits(limit-1024,0,120,512,false));
        assertFalse(VulkanRtScene.fits(limit,4096,limit+120,512,true));
    }

    @Test void driverSizedArraysDoNotConsumeTheThreadStack() {
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            // Leave very little stack space, as in nested Minecraft device initialization.
            stack.nmalloc(1,stack.getPointer()-1024);
            int available=stack.getPointer();
            try(var extensions=VulkanRtCapabilities.allocateExtensions(512);
                var instances=VulkanRtScene.allocateInstances(512)) {
                assertEquals(512,extensions.capacity());assertEquals(512,instances.capacity());
                assertEquals(available,stack.getPointer());
                org.lwjgl.system.MemoryUtil.memPutInt(extensions.get(511).address()+org.lwjgl.vulkan.VkExtensionProperties.SPECVERSION,42);
                instances.get(511).instanceCustomIndex(511);
                assertEquals(42,extensions.get(511).specVersion());
                assertEquals(511,instances.get(511).instanceCustomIndex());
            }
            assertEquals(available,stack.getPointer());
        }
    }

    @Test void sbtSeparatesHandleAndRegionAlignment() {
        var layout=VulkanSbt.layout(24,32,64,4096);
        assertEquals(32,layout.stride());assertEquals(64,layout.missOffset());assertEquals(128,layout.hitOffset());assertEquals(160,layout.bytes());
        assertEquals(0x1040,VulkanSbt.align(0x1030,64));
        assertThrows(IllegalArgumentException.class,()->VulkanSbt.layout(32,32,64,16));
        assertThrows(IllegalArgumentException.class,()->VulkanSbt.align(12,3));
        assertThrows(ArithmeticException.class,()->VulkanSbt.align(Long.MAX_VALUE,64));
    }
    @Test void optionalVariantsContainRealInstructionsWithoutLeakingOptionalCapabilitiesIntoBaseline()throws Exception{
        try(var jar=new ZipFile(System.getProperty("voxellight.modJar"))){
            for(String stage:List.of("material_primary","material_primary_query","material_primary_ser","material_primary_query_ser","material_indirect_query_ser","material_visibility_trace","material_visibility_query")){
                var b=ByteBuffer.wrap(jar.getInputStream(jar.getEntry("assets/voxellight/rt/vulkan/"+stage+".spv")).readAllBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                boolean query=false,reorder=false;Set<Integer> caps=new HashSet<>();
                for(int offset=5;offset<b.limit();){int count=b.get(offset)>>>16,op=b.get(offset)&65535;if(op==17)caps.add(b.get(offset+1));query|=op==4473;reorder|=op==5280;offset+=count;}
                assertEquals(stage.contains("query"),query,stage);assertEquals(stage.contains("ser"),reorder,stage);assertEquals(query,caps.contains(4472));assertEquals(reorder,caps.contains(5383));
            }
        }
    }
    @Test void packagedSpirvHasIndependentNonrecursiveRtStages() throws Exception {
        try(var jar=new ZipFile(System.getProperty("voxellight.modJar"))) {
            Map<String,Integer> models=Map.ofEntries(Map.entry("primary",5313),Map.entry("closest_hit",5316),Map.entry("sky",5317),Map.entry("transport_primary",5313),Map.entry("transport_indirect",5313),Map.entry("transport_closest_hit",5316),Map.entry("transport_sky",5317),Map.entry("material_primary",5313),Map.entry("material_indirect",5313),Map.entry("material_closest_hit",5316),Map.entry("material_sky",5317),Map.entry("material_cutout",5315));
            for(var entry:models.entrySet()) {
                byte[] bytes=jar.getInputStream(jar.getEntry("assets/voxellight/rt/vulkan/"+entry.getKey()+".spv")).readAllBytes();
                var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                assertEquals(0x07230203,buffer.get(0));boolean stage=false,trace=false;
                for(int offset=5;offset<buffer.limit();) {
                    int word=buffer.get(offset),count=word>>>16,opcode=word&65535;assertTrue(count>0);
                    if(opcode==15) {assertEquals(entry.getValue(),buffer.get(offset+1));stage=true;}
                    if(opcode==4445)trace=true;
                    if(opcode==71&&buffer.get(offset+2)==34)assertEquals(0,buffer.get(offset+3),"Descriptor set ABI is set zero");
                    offset+=count;
                }
                assertTrue(stage);assertEquals(entry.getValue()==5313,trace,"Only raygen traces rays; closest-hit is nonrecursive");
            }
        }
    }
    @Test void materialAtlasTransferRemainsGpuOnlyAndEveryCopyHasAnOwner() throws Exception {
        for(String owner:List.of("VulkanRtMaterialAssets","VulkanRtEnvironmentAssets")) {
            var node=new ClassNode();new ClassReader("com.voxellight.adapter."+owner).accept(node,0);
            boolean transfer=false,animated=false;
            for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("com/voxellight/nvidia"));
                assertNotEquals("map",call.name,"Material frame pixels must not return to CPU");
                if(call.name.equals("copyTextureToBuffer"))transfer=true;
                if(call.name.equals("createRenderPass"))animated=true;
            }
            assertTrue(transfer);assertTrue(animated,"Animated albedo and environment must be refreshed on GPU");
        }
    }

    @Test void deviceCreateHookStillMatchesMinecraftCallSite() throws Exception {
        var backend=new ClassNode();new ClassReader("com.mojang.blaze3d.vulkan.VulkanBackend").accept(backend,0);
        String descriptor="(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I";
        long matches=backend.methods.stream().filter(method->method.name.equals("createDevice")).flatMap(method->Arrays.stream(method.instructions.toArray()))
            .filter(instruction->instruction instanceof MethodInsnNode call&&call.owner.equals("org/lwjgl/vulkan/VK12")&&call.name.equals("vkCreateDevice")&&call.desc.equals(descriptor)).count();
        assertEquals(1,matches,"Version-specific RT feature-chain hook must target the real device creation");
    }
    @Test void vulkanTracerNeverReferencesOptixOrCudaOrSubmitsPerSection() throws Exception {
        for(String name:List.of("VulkanRtContext","VulkanRtScene","VulkanRtAccel","VulkanRtBuffer","VulkanRtPipeline")) {
            var node=new ClassNode();new ClassReader("com.voxellight.rt.vulkan."+name).accept(node,0);
            for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("com/voxellight/nvidia"));
                assertFalse(call.name.equals("vkQueueWaitIdle")||call.name.equals("vkDeviceWaitIdle")||call.name.equals("vkQueueSubmit"));
                if(name.equals("VulkanRtScene")||name.equals("VulkanRtAccel"))assertNotEquals("submit",call.name);
            }
        }
    }
    @Test void benchmarkTerrainCannotBeEvictedByGrowingDynamicModels(){
        long mib=1024L*1024;var terrain=new com.voxellight.world.SectionKey(1,0,0);var dynamic=new com.voxellight.world.SectionKey(1,Integer.MIN_VALUE,0);
        var sizes=Map.of(terrain,63*mib,dynamic,mib);
        assertNull(VulkanRtScene.evictions(sizes,Set.of(terrain),dynamic,64*mib,mib,2*mib,1,true,8,8,8));
        assertEquals(List.of(),VulkanRtScene.evictions(sizes,Set.of(terrain),dynamic,64*mib,mib,mib,1,true,8,8,8));
    }
}
