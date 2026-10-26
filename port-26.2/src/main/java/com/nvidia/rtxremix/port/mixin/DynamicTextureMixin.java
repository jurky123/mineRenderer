/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(DynamicTexture.class)
public abstract class DynamicTextureMixin {
    @Inject(method="upload",at=@At("RETURN"))
    private void remix$upload(CallbackInfo ci){PortTextures.changed((DynamicTexture)(Object)this);}
}
