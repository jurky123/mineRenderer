/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;
import net.minecraft.client.renderer.texture.SpriteContents;
import java.util.List;
public interface SpriteAnimationAccess {
    SpriteContents remix$contents();
    List<?> remix$frames();
    boolean remix$interpolate();
}
