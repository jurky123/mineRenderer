package com.voxellight.world;

import java.nio.ByteBuffer;

/** Existing inline native material metadata, inspected once while CPU vertices still exist. */
public final class CutoutAnimation {
    private CutoutAnimation() { }
    public static boolean needsRefresh(ByteBuffer vertices,int stride) {
        // Unknown/custom layouts or raw emitters retain the conservative animated-alpha behavior.
        if(stride!=36 || vertices.remaining()%stride!=0)return true;
        for(int offset=vertices.position();offset<vertices.limit();offset+=stride) {
            int marker=Byte.toUnsignedInt(vertices.get(offset+35));
            if(marker<16 || marker>31 || (Byte.toUnsignedInt(vertices.get(offset+31))&0x80)!=0)return true;
        }
        return false;
    }
}
