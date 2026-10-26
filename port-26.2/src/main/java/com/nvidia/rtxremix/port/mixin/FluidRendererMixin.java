/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.TerrainCapture;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Shadow @Final private FluidStateModelSet fluidModels;
    @Unique private int remix$tint;
    @Unique private ChunkSectionLayer remix$layer;
    @Inject(method="tesselate",at=@At("HEAD"))
    private void remix$fluid(BlockAndTintGetter world,BlockPos pos,FluidRenderer.Output output,BlockState state,FluidState fluid,CallbackInfo ci) {
        if(!TerrainCapture.compiling()) return;
        var model=fluidModels.get(fluid);remix$layer=model.layer();
        remix$tint=model.tintSource()==null?-1:model.tintSource().colorInWorld(state,world,pos);
    }
    @Inject(method="vertex",at=@At("HEAD"))
    private void remix$vertex(CallbackInfo ci){if(TerrainCapture.compiling()) TerrainCapture.unlit(remix$layer,remix$tint,1);}
}
