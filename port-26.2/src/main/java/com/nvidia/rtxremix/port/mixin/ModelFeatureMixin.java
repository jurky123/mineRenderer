/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortEntities;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureMixin {
    @Inject(method="prepareModel",at=@At("HEAD"))
    private void remix$owner(ModelFeatureRenderer.Submit<?> submit,CallbackInfo ci){PortEntities.enter(submit);}
    @Inject(method="prepareModel",at=@At("RETURN"))
    private void remix$end(ModelFeatureRenderer.Submit<?> submit,CallbackInfo ci){PortEntities.leave();}
}
