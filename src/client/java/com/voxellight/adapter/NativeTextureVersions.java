package com.voxellight.adapter;

import com.mojang.blaze3d.textures.GpuTexture;
import java.util.WeakHashMap;

/** Observe native Vulkan writes without image readback. Only requested textures are tracked. */
public final class NativeTextureVersions {
    private static final WeakHashMap<GpuTexture,Long> versions=new WeakHashMap<>();
    private NativeTextureVersions(){}
    public static synchronized long version(GpuTexture texture){return versions.computeIfAbsent(texture,ignored->0L);}
    public static void written(GpuTexture texture,int mip,int x,int y,int w,int h){com.voxellight.adapter.RtMaterialCoverage.written(texture,mip,x,y,w,h);increment(texture);}
    public static void rendered(com.mojang.blaze3d.textures.GpuTextureView view){RtMaterialCoverage.rendered(view);increment(view.texture());}
    public static void written(GpuTexture texture){written(texture,0,0,0,texture.getWidth(0),texture.getHeight(0));}
    private static synchronized void increment(GpuTexture texture){if(versions.containsKey(texture))versions.put(texture,versions.get(texture)+1);}
}
