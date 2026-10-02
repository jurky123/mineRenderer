package com.voxellight.mixin.client;

import com.mojang.blaze3d.textures.GpuSampler;
import com.voxellight.VoxelLightClient;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
    // Terrain diagnostics resolve before entities, translucency, weather, particles and hand/UI.
    @Inject(method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", at = @At("TAIL"))
    private void voxellight$afterOpaqueTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE) VoxelLightClient.probe().renderMaterialTerrain(group.outputTarget());
    }
}
