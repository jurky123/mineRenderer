package com.voxellight.mixin.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Private usage bit 32 is used only for NGX's storage-image output. */
@Mixin(VulkanConst.class)
abstract class VulkanStorageImageMixin {
    @Inject(method="textureUsageToVk",at=@At("RETURN"),cancellable=true)
    private static void voxellight$storage(int usage,GpuFormat format,CallbackInfoReturnable<Integer> ci){
        if((usage&32)!=0)ci.setReturnValue(ci.getReturnValue()|org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT);
    }
}
