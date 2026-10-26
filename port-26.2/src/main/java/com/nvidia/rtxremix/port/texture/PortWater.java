/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;

import com.nvidia.rtxremix.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Render-thread owner of the official water flipbook and separate camera medium. */
public final class PortWater implements AutoCloseable {
    public static final int TILE_SIZE=10;
    public static final float IOR=1.333f;
    // Additional neutral absorption at the two-metre reference distance.
    // It lowers deep-water visibility without shifting the established hue ratios.
    private static final float DENSITY_TRANSMISSION=.80f;
    public static final float RED=.80f*DENSITY_TRANSMISSION,GREEN=.94f*DENSITY_TRANSMISSION,
            BLUE=.93f*DENSITY_TRANSMISSION,DISTANCE=2f;
    private final RemixAPI api;
    private long normal,white,surface,medium;

    public PortWater(RemixAPI api,Path gameDirectory) {
        this.api=api;
        boolean complete=false;
        try {
            normal=api.createTextureFromFile(gameDirectory.resolve("textures/water_normals_flipbook.dds").toAbsolutePath().toString());
            ByteBuffer pixel=MemoryUtil.memAlloc(4).putInt(-1).flip();
            try {
                white=api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x26270101L)
                        .size(1,1).format(TextureFormat.RGBA8_UNORM).purpose(TexturePurpose.COLOR).pixelData(pixel).build());
            } finally {MemoryUtil.memFree(pixel);}
            surface=api.createMaterial(MaterialInfo.builder().hash(0x26270102L).albedoTexture(white)
                    .normalTexture(normal).filterMode(1).spriteSheetRow(10).spriteSheetCol(12).spriteSheetFps(20)
                    .translucentExt(MaterialInfoTranslucentEXT.builder().refractiveIndex(IOR)
                            .transmittanceColor(RED,GREEN,BLUE).transmittanceMeasurementDistance(DISTANCE)
                            .useDiffuseLayer(false).build()).build());
            medium=api.createMaterial(MaterialInfo.builder().hash(0x26270103L).albedoTexture(white).filterMode(1)
                    .translucentExt(MaterialInfoTranslucentEXT.builder().refractiveIndex(IOR)
                            .transmittanceColor(.98f,.98f,.98f).transmittanceMeasurementDistance(1)
                            .useDiffuseLayer(false).build()).build());
            complete=true;
        } finally {if(!complete)close();}
    }

    public long surface(){return surface;}
    public long medium(){return medium;}

    /** Dominant-axis projection matches the upstream fluid and cauldron mapping. */
    public static int uAxis(float nx,float ny,float nz){
        return Math.abs(ny)>=Math.abs(nx)&&Math.abs(ny)>=Math.abs(nz)?0:Math.abs(nx)>=Math.abs(nz)?2:0;
    }
    public static int vAxis(float nx,float ny,float nz){
        return Math.abs(ny)>=Math.abs(nx)&&Math.abs(ny)>=Math.abs(nz)?2:1;
    }
    /** Keep repeat-equivalent UVs small without wrapping across the interior of a quad. */
    public static float coordinate(int worldBase,float local){return (Math.floorMod(worldBase,TILE_SIZE)+local)/TILE_SIZE;}
    public static float coordinate(double worldBase,float local){
        double phase=worldBase-Math.floor(worldBase/TILE_SIZE)*TILE_SIZE;
        return (float)((phase+local)/TILE_SIZE);
    }
    public static float transmission(float color,float distance){return (float)Math.pow(color,distance/DISTANCE);}

    @Override public void close(){
        if(surface!=0)api.destroyMaterial(surface);if(medium!=0)api.destroyMaterial(medium);
        if(normal!=0)api.destroyTexture(normal);if(white!=0)api.destroyTexture(white);
        surface=medium=normal=white=0;
    }
}
