/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;

import com.nvidia.rtxremix.port.TerrainCapture;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Observe vanilla output without taking ownership of SectionCompiler's FRAPI redirect. */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @Shadow @Final private BlockColors blockColors;

    @ModifyVariable(method="tesselateBlock",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private BlockQuadOutput remix$unlit(BlockQuadOutput output,BlockQuadOutput originalOutput,
            float x,float y,float z,BlockAndTintGetter world,BlockPos pos,BlockState state,BlockStateModel model,long seed) {
        if(!TerrainCapture.compiling()) return output;
        boolean solid=ModelBlockRenderer.forceOpaque(TerrainCapture.cutoutLeaves(),state);
        return (qx,qy,qz,quad,lighting)->{
            var source=quad.materialInfo().tintIndex()<0?null:blockColors.getTintSource(state,quad.materialInfo().tintIndex());
            int tint=source==null?-1:source.colorInWorld(state,world,pos);
            TerrainCapture.unlit(solid?ChunkSectionLayer.SOLID:quad.materialInfo().layer(),tint,4);
            output.put(qx,qy,qz,quad,lighting);
        };
    }
}
