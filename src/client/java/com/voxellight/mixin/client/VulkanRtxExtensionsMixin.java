package com.voxellight.mixin.client;
import com.mojang.blaze3d.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.vulkan.VkDevice;
import java.util.*;
/** Optional export extensions: unsupported devices retain the native Vulkan feature set. */
@Mixin(VulkanBackend.class)
abstract class VulkanRtxExtensionsMixin {
    @Inject(method="createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",at=@At("HEAD"))
    private static void voxellight$externalMemory(Collection<String> extensions,VulkanPhysicalDevice physical,Set<?> features,CallbackInfoReturnable<VkDevice> ci){
        String suffix=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"win32":"fd";
        String memory="VK_KHR_external_memory_"+suffix,semaphore="VK_KHR_external_semaphore_"+suffix;
        if(physical.hasDeviceExtension(memory)&&physical.hasDeviceExtension(semaphore)){extensions.add(memory);extensions.add(semaphore);}
    }
}
