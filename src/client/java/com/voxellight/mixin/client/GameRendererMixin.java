package com.voxellight.mixin.client;

import com.voxellight.VoxelLightClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
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

    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void voxellight$world(LevelRenderer renderer,GraphicsResourceAllocator allocator,DeltaTracker delta,boolean outline,CameraRenderState camera,Matrix4fc view,GpuBufferSlice fog,Vector4f fogColor,boolean sky,Operation<Void> original){
        var probe=VoxelLightClient.probe();var target=((GameRenderer)(Object)this).mainRenderTarget();
        probe.beginWorldBudget();probe.beginEntityMaterialFrame();
        try{
            if(probe.tryReplaceWorld(target))((com.voxellight.adapter.RtWorldMaintenance)renderer).voxellight$maintainWorld(camera);
            else original.call(renderer,allocator,delta,outline,camera,view,fog,fogColor,sky);
            probe.render(target);
        }finally{probe.endWorldBudget(target);}
    }

    // Native hands are drawn after the PT composite; retain them when PT capture/display failed.
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void voxellight$ptHands(CallbackInfo ci) {
        if(VoxelLightClient.probe().replacesHands())ci.cancel();
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void voxellight$resizeResources(CallbackInfo ci) {
        VoxelLightClient.probe().resize();
    }

    @Inject(method = {"resetData", "setLevel", "close"}, at = @At("HEAD"))
    private void voxellight$releaseResources(CallbackInfo ci) {
        VoxelLightClient.probe().reset();
    }
}
