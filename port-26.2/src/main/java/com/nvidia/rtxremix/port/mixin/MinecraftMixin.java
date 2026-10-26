/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method="close",at=@At("HEAD"))
    private void remix$close(CallbackInfo ci){PortRenderer.close();}
    @Inject(method="close",at=@At("RETURN"))
    private void remix$closed(CallbackInfo ci){PortRenderer.shutdownNative();}
    @Inject(method="setLevel",at=@At("HEAD"))
    private void remix$level(net.minecraft.client.multiplayer.ClientLevel level,CallbackInfo ci){PortRenderer.changeLevel(level);}
}
