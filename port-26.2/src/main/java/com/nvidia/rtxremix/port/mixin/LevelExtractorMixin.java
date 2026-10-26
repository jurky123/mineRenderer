/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortEntities;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method="extract",at=@At("HEAD"))
    private void remix$begin(CallbackInfo ci){PortEntities.beginExtraction();}
    @Inject(method="extractVisibleEntities",at=@At("RETURN"))
    private void remix$reflections(Camera camera,Frustum frustum,DeltaTracker delta,LevelRenderState state,CallbackInfo ci){PortEntities.addOffscreen(camera,delta,state);}
}
