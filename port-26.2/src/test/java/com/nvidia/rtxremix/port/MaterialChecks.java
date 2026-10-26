/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;

final class MaterialChecks {
    private static Identifier block(String name){return Identifier.parse("minecraft:block/"+name);}
    static void run() throws Exception{
        var layers=new HashMap<PortEntities.Layer,Integer>();Object renderType=new Object();
        var registry=new RetainedEntityPartRegistry<Object,Object>();Object owner=new Object();int[] creates={0};
        Object first=null;
        for(int frame=0;frame<50;frame++){
            // Every frame receives a different temporary model wrapper around the same cube.
            Object wrapper=new Object();int layer=layers.computeIfAbsent(new PortEntities.Layer(wrapper.getClass(),renderType),k->layers.size());
            registry.beginFrame();Object retained=registry.retain(owner,0,layer,7,x->true,()->{creates[0]++;return new Object();},x->{});
            registry.endFrame(x->{});if(first==null)first=retained;assert retained==first:"Hand native instance history survives wrapper replacement";
        }
        assert layers.size()==1&&creates[0]==1;
        var itemGeometry=new PortViewItems.Geometry(List.of(),new ArrayList<>(List.of(0xff804020)));
        assert itemGeometry.equals(new PortViewItems.Geometry(List.of(),List.of(0xff804020))):"Per-frame tint arrays do not recreate item geometry";
        assert !itemGeometry.equals(new PortViewItems.Geometry(List.of(),List.of(0xff102040))):"Tint changes invalidate retained item geometry";
        assert BlockMaterialDefaults.forSprite(block("iron_block")).metallic()==1;
        assert BlockMaterialDefaults.forSprite(block("iron_ore")).metallic()==0;
        assert BlockMaterialDefaults.forSprite(block("oxidized_copper")).metallic()==0;
        assert BlockMaterialDefaults.forSprite(block("oak_planks")).roughness()>BlockMaterialDefaults.forSprite(block("glazed_terracotta")).roughness();
        var mapped=BlockMaterialDefaults.resolve(block("iron_block"),true,true);
        assert mapped.roughness()==1&&mapped.metallic()==1:"Authored maps replace constants, not multiplied by fallback";
        assert BlockMaterialDefaults.forSprite(Identifier.parse("example:block/gold_block")).metallic()==0:"No invented physics for mod namespaces";
        assert BlockMaterialDefaults.gel(block("slime_block")).ior()>1;
        assert BlockMaterialDefaults.gel(block("honey_block_side")).ior()>BlockMaterialDefaults.gel(block("slime_block")).ior();
        assert BlockMaterialDefaults.gel(block("honeycomb_block"))==null:"Opaque honeycomb is not honey gel";
        MaterialLayout base=new MaterialLayout(2,4,2,2),staticMap=base.variant(8,8),animatedMap=base.variant(8,16);
        assert staticMap.frames()==1&&animatedMap.frames()==2;
        assert base.variant(8,40).frames()==5:"Legacy PBR sheets may retain unused animation tiles";
        assert base.sourceIndex(1,3,staticMap)==6*8+6:"Normalized map samples cover whole static map in every frame";
        assert base.sourceIndex(1,3,animatedMap)==14*8+6;
        Path temp=Files.createTempDirectory("remix26-pbr-check");
        try(var albedo=new NativeImage(2,4,true);var n=new NativeImage(8,8,true);var s=new NativeImage(8,8,true)){
            for(int y=0;y<4;y++)for(int x=0;x<2;x++)albedo.setPixel(x,y,y<2?0xffff0000:0xff00ff00);
            for(int y=0;y<8;y++)for(int x=0;x<8;x++){
                n.setPixel(x,y,(x<4?0x20:0xe0)<<24|0x00808000|255);
                s.setPixel(x,y,0x7f800a00);
            }
            n.writeToFile(temp.resolve("n.png"));s.writeToFile(temp.resolve("s.png"));
            var resources=new HashMap<Identifier,Resource>();
            resources.put(Identifier.parse("test:textures/block/test_n.png"),new Resource(null,()->Files.newInputStream(temp.resolve("n.png"))));
            resources.put(Identifier.parse("test:textures/block/test_s.png"),new Resource(null,()->Files.newInputStream(temp.resolve("s.png"))));
            var rm=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class[]{ResourceManager.class},(p,m,a)->resources);
            var pixels=new SpritePixels(Identifier.parse("test:block/test"),albedo,2,2,LabPBRConverter.buildVariantCache(rm));
            assert pixels.frameWidth(1)==8&&pixels.frameHeight(1)==8&&pixels.frame(1).length==8*8*4:"Native normal keeps high resolution";
            assert !pixels.animated(1)&&!pixels.animated(3)&&pixels.animated(5):"Static PBR maps and animated emissive albedo have independent update lifetimes";
            assert (pixels.frame(2)[0]&255)==0x20&&(pixels.frame(2)[7]&255)==0xe0:"Height map keeps both quadrants";
            pixels.currentFrame=1;pixels.nextFrame=0;
            assert (pixels.frame(5)[0]&255)==0&&(pixels.frame(5)[1]&255)>0:"Static emission mask follows animated albedo color";
            pixels.interpolate=true;pixels.subFrame=1;pixels.duration=2;
            assert (pixels.frame(5)[0]&255)>0&&(pixels.frame(5)[1]&255)>0;
        }finally{
            Files.deleteIfExists(temp.resolve("n.png"));Files.deleteIfExists(temp.resolve("s.png"));Files.deleteIfExists(temp);
        }
        System.out.println("PASS: stable hand instance identity, vanilla material families, authored precedence and mixed-resolution animated PBR channels");
    }
}
