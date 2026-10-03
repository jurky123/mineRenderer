package com.voxellight.mixin.client;

import com.voxellight.VoxelLightClient;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void voxellight$ownedCloudLayer(CallbackInfo ci){if(VoxelLightClient.probe().replacesClouds())ci.cancel();}
}
