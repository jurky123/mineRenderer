package com.voxellight.mixin.client;

import com.mojang.blaze3d.textures.GpuSampler;
import com.voxellight.VoxelLightClient;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
    @Inject(method="renderGroup",at=@At("HEAD"))
    private void voxellight$prepareWater(ChunkSectionLayerGroup group,GpuSampler sampler,CallbackInfo ci) {
        if(group==ChunkSectionLayerGroup.TRANSLUCENT)VoxelLightClient.probe().prepareWater(group.outputTarget());
    }
    @WrapOperation(method="renderGroup",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void voxellight$waterPipeline(RenderPass pass,RenderPipeline pipeline,Operation<Void> original) {
        if(pipeline!=RenderPipelines.TRANSLUCENT_TERRAIN || !VoxelLightClient.probe().bindWater(pass))original.call(pass,pipeline);
    }
    // Terrain diagnostics resolve before entities, translucency, weather, particles and hand/UI.
    @Inject(method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", at = @At("TAIL"))
    private void voxellight$afterOpaqueTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) VoxelLightClient.probe().renderMaterialTerrain(group.outputTarget(), sampler);
    }
}
