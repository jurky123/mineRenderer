package com.voxellight.mixin.client;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanDebug;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.voxellight.nvidia.DlssNative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Set;

/** NGX requirements must be enabled before the borrowed Vulkan instance is created. */
@Mixin(VulkanInstance.class)
abstract class VulkanNgxInstanceMixin {
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;"))
    private VulkanDebug voxellight$ngx(int verbosity,boolean labels,Set<String> available,Set<String> enabled,Operation<VulkanDebug> original){
        try{DlssNative.load();for(String extension:DlssNative.extensions(false))if(available.contains(extension))enabled.add(extension);}
        catch(RuntimeException error){com.mojang.logging.LogUtils.getLogger().warn("NGX instance requirements unavailable; DLSS RR may fall back",error);}
        return original.call(verbosity,labels,available,enabled);
    }
}
