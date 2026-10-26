/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.texture.AnimationFrameAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(targets="net.minecraft.client.renderer.texture.SpriteContents$FrameInfo")
public abstract class AnimationFrameMixin implements AnimationFrameAccess {
    @Shadow @Final private int index,time;
    public int remix$index(){return index;}public int remix$time(){return time;}
}
