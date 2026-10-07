package com.voxellight.mixin.client;

import com.voxellight.adapter.RenderPassProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.systems.GpuSurface;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wall-clock submission/presentation cost; separate from GPU elapsed timestamps. */
@Mixin(Minecraft.class)
abstract class MinecraftFrameMixin {
    @Unique private long voxellight$frameStart;
    @Unique private long voxellight$acquire,voxellight$extract,voxellight$present,voxellight$limit;
    @Inject(method="renderFrame",at=@At("HEAD"))
    private void voxellight$begin(boolean tick,CallbackInfo ci){com.voxellight.adapter.RtBenchmarkRunner.keepBenchmarkActive();voxellight$frameStart=System.nanoTime();voxellight$acquire=voxellight$extract=voxellight$present=voxellight$limit=0;}
    @Inject(method="renderFrame",at=@At("RETURN"))
    private void voxellight$end(boolean tick,CallbackInfo ci){
        RenderPassProfile.cpu("client_render_frame_wall",System.nanoTime()-voxellight$frameStart);
        RenderPassProfile.cpu("client_acquire_wall",voxellight$acquire);
        RenderPassProfile.cpu("client_extract_wall",voxellight$extract);
        RenderPassProfile.cpu("client_present_wall",voxellight$present);
        RenderPassProfile.cpu("client_limiter_wall",voxellight$limit);
    }
    @WrapOperation(method="renderFrame",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
    private void voxellight$acquire(GpuSurface surface,Operation<Void> original){long start=System.nanoTime();try{original.call(surface);}finally{voxellight$acquire+=System.nanoTime()-start;}}
    @WrapOperation(method="renderFrame",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
    private void voxellight$present(GpuSurface surface,Operation<Void> original){long start=System.nanoTime();try{original.call(surface);}finally{voxellight$present+=System.nanoTime()-start;}}
    @WrapOperation(method="renderFrame",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void voxellight$extract(GameRenderer renderer,DeltaTracker delta,boolean tick,Operation<Void> original){long start=System.nanoTime();try{original.call(renderer,delta,tick);}finally{voxellight$extract+=System.nanoTime()-start;}}
    @WrapOperation(method="renderFrame",at=@At(value="INVOKE",target="Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
    private void voxellight$limiter(int limit,Operation<Void> original){long start=System.nanoTime();try{original.call(limit);}finally{voxellight$limit+=System.nanoTime()-start;}}
}
