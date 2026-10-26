/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.FeatureCapture;
import net.minecraft.client.renderer.rendertype.*;
import org.spongepowered.asm.mixin.*;
@Mixin(RenderType.class)
public abstract class RenderTypeMixin implements FeatureCapture.RenderTypeAccess {
    @Shadow @Final private RenderSetup state;
    public Object remix$setup(){return state;}
}
