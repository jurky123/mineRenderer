package com.voxellight.mixin.client;

import com.voxellight.VoxelLightClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice voxellight$actualWorldProjection(ProjectionMatrixBuffer buffer,Matrix4f projection,Operation<GpuBufferSlice> original) {
        VoxelLightClient.probe().jitterWorldProjection(projection);
        VoxelLightClient.probe().captureWorldProjection(projection);
        return original.call(buffer,projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void voxellight$beginWorldMaterials(CallbackInfo ci) { VoxelLightClient.probe().beginWorldBudget();VoxelLightClient.probe().beginEntityMaterialFrame(); }

    // The world depth is cleared immediately afterwards for the hand/3D HUD.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER))
    private void voxellight$afterWorld(CallbackInfo ci) {
        var target=((GameRenderer)(Object)this).mainRenderTarget();
        VoxelLightClient.probe().render(target);
        VoxelLightClient.probe().endWorldBudget(target);
    }

    @Inject(method = {"resize", "resetData", "setLevel", "close"}, at = @At("HEAD"))
    private void voxellight$releaseResources(CallbackInfo ci) {
        VoxelLightClient.probe().reset();
    }
}
