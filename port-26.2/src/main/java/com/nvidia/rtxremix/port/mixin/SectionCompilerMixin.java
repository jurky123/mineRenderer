/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.port.TerrainCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Unique private final ThreadLocal<Long> remix$generation=ThreadLocal.withInitial(TerrainCapture::generation);
    @Inject(method="compile",at=@At("HEAD"))
    private void remix$begin(SectionPos pos,RenderSectionRegion region,VertexSorting sorting,SectionBufferBuilderPack pack,CallbackInfoReturnable<SectionCompiler.Results> ci) {
        remix$generation.set(TerrainCapture.generation());
        TerrainCapture.begin(cutoutLeaves);
    }
    @Inject(method="compile",at=@At("RETURN"))
    private void remix$capture(SectionPos pos,RenderSectionRegion region,VertexSorting sorting,SectionBufferBuilderPack pack,CallbackInfoReturnable<SectionCompiler.Results> ci) {
        TerrainCapture.capture(pos,ci.getReturnValue(),remix$generation.get(),com.nvidia.rtxremix.port.light.PortBlockLights.capture(region,pos));remix$generation.remove();
    }
    @Shadow private boolean cutoutLeaves;
}
