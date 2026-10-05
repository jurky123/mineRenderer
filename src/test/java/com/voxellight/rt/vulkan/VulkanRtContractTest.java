package com.voxellight.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class VulkanRtContractTest {
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
            Map<String,Integer> models=Map.of("primary",5313,"closest_hit",5316,"sky",5317);
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
                assertTrue(stage);assertEquals(entry.getKey().equals("primary"),trace,"Only raygen traces rays; closest-hit is nonrecursive");
            }
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
}
