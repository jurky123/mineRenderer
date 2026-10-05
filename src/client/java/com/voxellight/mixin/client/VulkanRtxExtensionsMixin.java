package com.voxellight.mixin.client;
import com.mojang.blaze3d.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.PointerBuffer;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.voxellight.rt.vulkan.VulkanRtCapabilities;
import java.util.*;
/** Optional export extensions: unsupported devices retain the native Vulkan feature set. */
@Mixin(VulkanBackend.class)
abstract class VulkanRtxExtensionsMixin {
    @Inject(method="createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",at=@At("HEAD"))
    private static void voxellight$externalMemory(Collection<String> extensions,VulkanPhysicalDevice physical,Set<?> features,CallbackInfoReturnable<VkDevice> ci){
        var capabilities=VulkanRtCapabilities.query(physical.vkPhysicalDevice());
        if(capabilities.supported())for(String extension:VulkanRtCapabilities.REQUIRED)if(!extensions.contains(extension))extensions.add(extension);
        String suffix=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"win32":"fd";
        String memory="VK_KHR_external_memory_"+suffix,semaphore="VK_KHR_external_semaphore_"+suffix;
        if(physical.hasDeviceExtension(memory)&&physical.hasDeviceExtension(semaphore)){extensions.add(memory);extensions.add(semaphore);}
    }

    @WrapOperation(method="createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int voxellight$rtFeatures(VkPhysicalDevice physical,VkDeviceCreateInfo info,VkAllocationCallbacks allocator,PointerBuffer output,Operation<Integer> original){
        if(!VulkanRtCapabilities.query(physical).supported())return original.call(physical,info,allocator,output);
        try(var stack=MemoryStack.stackPush()){
            // MC already owns Vulkan12Features: edit its BDA field rather than insert a duplicate struct.
            long next=info.pNext();boolean found=false;
            for(long node=next;node!=0;node=VkBaseOutStructure.create(node).pNext()==null?0:VkBaseOutStructure.create(node).pNext().address()){
                if(VkBaseOutStructure.create(node).sType()==VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES){VkPhysicalDeviceVulkan12Features.create(node).bufferDeviceAddress(true);found=true;break;}
            }
            if(!found)next=VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().bufferDeviceAddress(true).pNext(next).address();
            var accel=VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().accelerationStructure(true).pNext(next);
            var pipeline=VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default().rayTracingPipeline(true).pNext(accel.address());
            long previous=info.pNext();info.pNext(pipeline.address());
            try{return original.call(physical,info,allocator,output);}finally{info.pNext(previous);}
        }
    }
}
