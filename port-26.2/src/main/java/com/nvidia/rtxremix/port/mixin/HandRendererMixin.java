/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortViewModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemInHandRenderer.class)
public abstract class HandRendererMixin {
    @Inject(method="submitHandsWithItems",at=@At("HEAD"))
    private void remix$view(CallbackInfo ci){if(PortViewModel.capturing())PortViewModel.handModelView();}
}
