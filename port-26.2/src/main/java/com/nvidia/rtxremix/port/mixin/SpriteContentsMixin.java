/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin implements SpriteContentsAccess {
    @Shadow @Final private NativeImage originalImage;
    public NativeImage remix$image(){return originalImage;}
    @Inject(method="createAnimationState",at=@At("RETURN"))
    private void remix$animation(CallbackInfoReturnable<SpriteContents.AnimationState> ci) {
        PortTextures.animation((SpriteContents)(Object)this,ci.getReturnValue());
    }
}
