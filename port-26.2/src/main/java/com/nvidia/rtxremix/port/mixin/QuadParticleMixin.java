/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Only the replaced billboard draws are omitted; extraction and other particle groups remain vanilla. */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleMixin {
    @Inject(method="executeGroup",at=@At("HEAD"),cancellable=true)
    private void remix$physical(CallbackInfo ci){if(PortRenderer.api()!=null)ci.cancel();}
}
