/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.cloud.PortClouds;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void remix$physical(CallbackInfo ci){if(PortClouds.replaced)ci.cancel();}
}
