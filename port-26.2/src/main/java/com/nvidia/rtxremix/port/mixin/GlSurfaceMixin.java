/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.mojang.blaze3d.opengl.GlSurface;
import com.nvidia.rtxremix.port.PortRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GlSurface.class)
public abstract class GlSurfaceMixin {
    @Inject(method="present",at=@At("HEAD"),cancellable=true)
    private void remix$present(CallbackInfo ci) { if(PortRenderer.present()) ci.cancel(); }
}
