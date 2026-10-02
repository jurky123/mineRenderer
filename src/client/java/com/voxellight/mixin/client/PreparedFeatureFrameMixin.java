package com.voxellight.mixin.client;

import com.voxellight.VoxelLightClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
abstract class PreparedFeatureFrameMixin {
    @Inject(method="executeSolid",at=@At("TAIL"))
    private void voxellight$afterSolidModels(CallbackInfo ci) {
        VoxelLightClient.probe().renderMaterialEntities(Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }
}
