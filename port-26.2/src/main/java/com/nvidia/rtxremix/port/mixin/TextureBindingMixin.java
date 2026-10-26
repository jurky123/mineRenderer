/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.FeatureCapture;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.*;
@Mixin(targets="net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public abstract class TextureBindingMixin implements FeatureCapture.TextureBindingAccess {
    @Shadow @Final private Identifier location;
    public Identifier remix$location(){return location;}
}
