/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.nvidia.rtxremix.port.texture.PortTextures;
public record MeshBatch(PortTextures.Key material,byte[] vertices,boolean quads,int categories) {
    public MeshBatch(PortTextures.Key material,byte[] vertices,boolean quads){this(material,vertices,quads,0);}
}
