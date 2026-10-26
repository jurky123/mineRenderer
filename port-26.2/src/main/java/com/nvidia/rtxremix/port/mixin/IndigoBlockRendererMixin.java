/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;

import com.nvidia.rtxremix.port.TerrainCapture;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Indigo's output bypasses vanilla BlockQuadOutput. Capture after culling, before AO. */
@Pseudo
@Mixin(targets="net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl",remap=false)
public abstract class IndigoBlockRendererMixin {
    @Shadow private BlockAndTintGetter level;
    @Shadow private BlockPos pos;
    @Shadow private BlockState blockState;
    @Shadow private int getTintColor(BlockAndTintGetter world,BlockState state,BlockPos pos,int index) {throw new AssertionError();}

    @Inject(method="transform",at=@At(value="INVOKE",target="Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl;shadeQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;ZZZ)V"))
    private void remix$unlit(MutableQuadView quad,CallbackInfoReturnable<Boolean> ci) {
        if(!TerrainCapture.compiling()) return;
        int tint=quad.tintIndex()<0?-1:getTintColor(level,blockState,pos,quad.tintIndex());
        for(int vertex=0;vertex<4;vertex++)
            TerrainCapture.unlit(quad.chunkLayer(),ARGB.multiply(quad.color(vertex),tint),1);
    }
}
