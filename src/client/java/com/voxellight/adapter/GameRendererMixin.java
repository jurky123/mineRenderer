package com.voxellight.adapter;

import com.voxellight.VoxelLightClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    // The world depth is cleared immediately afterwards for the hand/3D HUD.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER))
    private void voxellight$afterWorld(CallbackInfo ci) {
        VoxelLightClient.probe().render(((GameRenderer) (Object) this).mainRenderTarget());
    }

    @Inject(method = {"resize", "resetData", "setLevel", "close"}, at = @At("HEAD"))
    private void voxellight$releaseResources(CallbackInfo ci) {
        VoxelLightClient.probe().reset();
    }
}
