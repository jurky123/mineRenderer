/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.renderer.texture.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method="upload",at=@At("HEAD"))
    private void remix$atlas(SpriteLoader.Preparations preparations,CallbackInfo ci) {
        PortTextures.atlas((TextureAtlas)(Object)this,preparations);
    }
}
