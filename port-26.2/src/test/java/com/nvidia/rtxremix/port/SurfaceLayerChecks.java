/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.lang.reflect.Proxy;
import java.nio.*;
import java.util.*;
final class SurfaceLayerChecks {
    private static byte[] quad(float z,float normal){
        var b=ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        for(float[] xy:new float[][]{{0,0},{1,0},{1,1},{0,1}}){b.putFloat(xy[0]).putFloat(xy[1]).putFloat(z).putFloat(0).putFloat(0).putFloat(normal).putFloat(xy[0]).putFloat(xy[1]).putInt(-1);for(int i=0;i<7;i++)b.putInt(0);}
        return b.array();
    }
    static void run(){
        var rm=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class[]{ResourceManager.class},(p,m,a)->Map.of());
        try(var base=new NativeImage(2,2,true);var overlay=new NativeImage(2,2,true)){
            for(int y=0;y<2;y++)for(int x=0;x<2;x++){base.setPixel(x,y,0xff804020);overlay.setPixel(x,y,y==0?0xff80ff80:0);}
            var cache=LabPBRConverter.buildVariantCache(rm);
            var dirt=new SpritePixels(Identifier.parse("test:block/dirt"),base,2,2,cache);
            var grass=new SpritePixels(Identifier.parse("test:block/overlay"),overlay,2,2,cache);
            assert !dirt.hasTransparency&&grass.hasTransparency;
            var glass=new SpritePixels(Identifier.parse("minecraft:block/glass"),overlay,2,2,cache);
            assert PortTextures.key(glass,PortTextures.Kind.BLEND).kind()==PortTextures.Kind.GLASS:"World glass keeps physical transmission";
            var opaque=new MeshBatch(PortTextures.key(dirt,PortTextures.Kind.CUTOUT),quad(0,1),true);
            var decal=new MeshBatch(PortTextures.key(grass,PortTextures.Kind.CUTOUT),quad(0,1),true);
            var classified=CoplanarLayers.classify(List.of(opaque,decal));
            assert classified.size()==2;
            assert classified.stream().anyMatch(b->b.material().kind()==PortTextures.Kind.SOLID&&b.categories()==0):"Opaque texels must be a solid base even in a cutout layer";
            assert classified.stream().anyMatch(b->b.material().kind()==PortTextures.Kind.DECAL&&b.categories()==InstanceCategoryBit.DECAL_STATIC):"Coplanar transparent grass layer needs native decal semantics";
            for(var unrelated:List.of(quad(.01f,1),quad(0,-1))){
                var result=CoplanarLayers.classify(List.of(opaque,new MeshBatch(PortTextures.key(grass,PortTextures.Kind.CUTOUT),unrelated,true)));
                assert result.stream().noneMatch(b->b.material().kind()==PortTextures.Kind.DECAL):"Separate or back-facing foliage must not become a decal";
            }
            var side=new SpritePixels(Identifier.parse("minecraft:block/grass_block_side"),base,2,2,cache);
            var coating=new SpritePixels(Identifier.parse("minecraft:block/grass_block_side_overlay"),overlay,2,2,cache);
            var merged=CoplanarLayers.classify(List.of(new MeshBatch(PortTextures.key(side,PortTextures.Kind.CUTOUT),quad(0,1),true),new MeshBatch(PortTextures.key(coating,PortTextures.Kind.CUTOUT),quad(0,1),true)));
            assert merged.size()==1&&merged.get(0).vertices().length==256:"Grass side is one closed opaque face, not two intersecting layers";
            var pixels=merged.get(0).material().pixels();assert !pixels.hasTransparency;
            assert (pixels.channels[0][1]&255)==255&&(pixels.channels[0][8]&255)==128:"Grass covers opaque soil only inside its authored mask";
            GrassComposites.clear();
        }
        assert PortSampling.valid(0)&&PortSampling.valid(.5f)&&PortSampling.valid(1)&&!PortSampling.valid(2)&&!PortSampling.valid(Float.NaN)&&!PortSampling.valid(.3f);
        var legacy=new com.google.gson.Gson().fromJson("{\"cloudSize\":1}",PortPreferences.class);assert legacy.samplingRate==0&&legacy.samplingRestore.isEmpty():"Legacy preferences preserve native sampling defaults";
        try{net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase.class.getDeclaredMethod("submit",net.minecraft.client.renderer.feature.submit.TranslucentSubmit.class);}catch(NoSuchMethodException e){throw new AssertionError("Transparent-owner hook must target the real 26.2 submission entry",e);}
        System.out.println("PASS: opaque promotion, coplanar grass decals, separated/backface foliage and supported sampling budgets");
    }
}
