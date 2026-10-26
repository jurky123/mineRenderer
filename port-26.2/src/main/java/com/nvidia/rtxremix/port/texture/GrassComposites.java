/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;
import java.util.*;
/** Bounded worker-safe cache; native textures are still created only on the render thread. */
public final class GrassComposites {
    private record Key(SpritePixels soil,SpritePixels grass,int soilTint,int grassTint){}
    private static final Map<Key,SpritePixels> cache=new HashMap<>();
    private static long bytes;
    private GrassComposites(){}
    public static synchronized SpritePixels get(SpritePixels soil,SpritePixels grass,int soilTint,int grassTint){
        if(!soil.id.toString().equals("minecraft:block/grass_block_side")||!grass.id.toString().equals("minecraft:block/grass_block_side_overlay"))return null;
        var key=new Key(soil,grass,soilTint,grassTint);var old=cache.get(key);if(old!=null)return old;
        long estimate=0;for(byte[] c:soil.channels)if(c!=null)estimate+=c.length;
        if(cache.size()>=512||bytes+estimate>32L*1024*1024)return null;
        var result=soil.grassComposite(grass,soilTint,grassTint);if(result!=null){cache.put(key,result);bytes+=estimate;}return result;
    }
    public static synchronized void clear(){cache.clear();bytes=0;}
}
