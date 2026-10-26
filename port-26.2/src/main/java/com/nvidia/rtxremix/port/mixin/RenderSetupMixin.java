/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.FeatureCapture;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
@Mixin(RenderSetup.class)
public abstract class RenderSetupMixin implements FeatureCapture.RenderSetupAccess {
    @Shadow @Final private Map<String,?> textures;
    public Map<String,?> remix$textures(){return textures;}
}
