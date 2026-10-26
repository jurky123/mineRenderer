/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SpriteContents.AnimationState.class)
public abstract class AnimationStateMixin {
    @Shadow private int frame,subFrame;
    @Inject(method="tick",at=@At("RETURN"))
    private void remix$tick(CallbackInfo ci) {
        PortTextures.tick((SpriteContents.AnimationState)(Object)this,frame,subFrame);
    }
}
