/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow private LevelRenderState levelRenderState;
    /** Runs inside the frame graph, before weather and always-on-top raster passes. */
    @Inject(method="lambda$addMainPass$0",at=@At("RETURN"))
    private void remix$compose(CallbackInfo ci){PortRenderer.renderWorld(Minecraft.getInstance().gameRenderer,levelRenderState);}
}
