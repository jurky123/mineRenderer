/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(FontTexture.class)
public abstract class FontTextureMixin {
    @Inject(method="add",at=@At("RETURN"))
    private void remix$glyph(CallbackInfoReturnable<?> ci){if(ci.getReturnValue()!=null) PortTextures.changed((FontTexture)(Object)this);}
}
