/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortEntities;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Glass uses a separate phase; binding only SimpleFeatureRenderPhase loses its player owner. */
@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class TranslucentFeatureSubmitMixin {
    @Inject(method="submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V",at=@At("HEAD"))
    private void remix$owner(TranslucentSubmit node,CallbackInfo ci){PortEntities.bindSubmit(node);}
}
