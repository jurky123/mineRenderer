/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortEntities;
import com.nvidia.rtxremix.port.PortViewItems;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureMixin {
    @Inject(method="prepareMainSubmit",at=@At("HEAD"))
    private void remix$owner(ItemFeatureRenderer.Submit node,CallbackInfo ci){PortEntities.enterFeature(node);PortViewItems.enter(node);}
    @Inject(method="prepareMainSubmit",at=@At("RETURN"))
    private void remix$end(CallbackInfo ci){PortViewItems.leave();PortEntities.leaveFeature();}
}
