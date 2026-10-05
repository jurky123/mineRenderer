package com.voxellight.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class VulkanRtContractTest {
    @Test void backendSwitchPreservesRasterAndDebugRunsBeforeProjectionReset() throws Exception {
        for(String name:List.of("VulkanRtDebugPass","RtxLightingPass")) {
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
            for(int binding=1;binding<=6;binding++) {
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
        var node=new ClassNode();new ClassReader("com.voxellight.adapter.VulkanRtMaterialAssets").accept(node,0);
        boolean transfer=false,animated=false;
        for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call) {
            assertFalse(call.owner.startsWith("com/voxellight/nvidia"));
            assertNotEquals("map",call.name,"Material frame pixels must not return to CPU");
            if(call.name.equals("copyTextureToBuffer"))transfer=true;
            if(call.name.equals("createRenderPass"))animated=true;
        }
        assertTrue(transfer);assertTrue(animated,"Animated albedo must be refreshed on GPU");
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
}
