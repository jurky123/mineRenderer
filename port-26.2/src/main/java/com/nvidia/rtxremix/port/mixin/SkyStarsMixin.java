/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.port.sky.PortCelestials;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.nio.ByteBuffer;
@Mixin(SkyRenderer.class)
public abstract class SkyStarsMixin {
    @Redirect(method="buildStars",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/MeshData;vertexBuffer()Ljava/nio/ByteBuffer;"))
    private ByteBuffer remix$stars(MeshData data){PortCelestials.captureStars(data);return data.vertexBuffer();}
}
