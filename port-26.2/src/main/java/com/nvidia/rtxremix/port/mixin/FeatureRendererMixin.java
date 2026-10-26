/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.port.FeatureCapture;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class FeatureRendererMixin {
    @Inject(method="getVertexBuilder",at=@At("RETURN"),cancellable=true)
    private void remix$tee(RenderType type,CallbackInfoReturnable<VertexConsumer> ci){ci.setReturnValue(FeatureCapture.wrap(type,ci.getReturnValue()));}
}
