/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.client.renderer.texture.SpriteContents;
import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public abstract class AnimatedTextureMixin implements SpriteAnimationAccess {
    @Shadow @Final private SpriteContents this$0;
    @Shadow @Final private List<?> frames;
    @Shadow @Final private boolean interpolateFrames;
    public SpriteContents remix$contents(){return this$0;}
    public List<?> remix$frames(){return frames;}
    public boolean remix$interpolate(){return interpolateFrames;}
    @Inject(method="createAnimationState",at=@At("RETURN"))
    private void remix$info(CallbackInfoReturnable<SpriteContents.AnimationState> ci) {
        PortTextures.animationInfo(ci.getReturnValue(),this);
    }
}
