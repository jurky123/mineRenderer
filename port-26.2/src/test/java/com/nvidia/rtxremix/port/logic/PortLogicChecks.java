/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.logic;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
public final class PortLogicChecks {
    public static void run() throws Exception {
        assert ConfLayerDriver.timeLayerStrengths(6000).day()==1;
        assert ConfLayerDriver.timeLayerStrengths(18000).night()==1;
        assert ConfLayerDriver.timeLayerStrengths(24000).equals(ConfLayerDriver.timeLayerStrengths(0));
        assert Math.abs(ConfLayerDriver.smoothStep(0,1,.5f)-.5f)<1e-6;
        var fade=new ViewDistanceFade();assert fade.layerStrength(2)==1&&fade.layerStrength(32)==0;
        var sphere=ZoneDefinition.parse("rtx zone define cave --inner 10 --outer 30",0,0,0);
        var box=ZoneDefinition.parse("rtx zone define room --box -2 -2 -2 2 2 2 --fade 4",0,0,0);
        assert sphere.weight(0,0,0)==1&&sphere.weight(31,0,0)==0&&Math.abs(sphere.weight(20,0,0)-.5)<1e-6;
        assert box.weight(0,0,0)==1&&box.weight(10,0,0)==0;
        var state=new ZonePersistentState();state.put(sphere);state.put(box);
        var tag=state.writeNbt(new CompoundTag(),null);
        assert ZonePersistentState.fromNbt(tag,null).snapshot().equals(state.snapshot());
        tag.putInt("version",100);boolean refused=false;
        try{ZonePersistentState.fromNbt(tag,null).put(sphere);}catch(IllegalStateException expected){refused=true;}
        assert refused:"Unknown zone schema must not be overwritten";
        Path game=Files.createTempDirectory("remix26-scene-check"),mod=game.resolve("rtx-remix/mods/minecraft-logic");
        for(var row:ConfLayerDriver.STATIC_LAYER_TABLE){Path file=mod.resolve(row.confPath());Files.createDirectories(file.getParent());Files.writeString(file,"rtx.vertexColorStrength = 1\n");}
        for(String file:List.of("biomes/plains.conf","zones/cave.conf")){Path path=mod.resolve("confs/"+file);Files.createDirectories(path.getParent());Files.writeString(path,"rtx.vertexColorStrength = 1\n");}
        class Api implements ConfLayerDriver.ConfigLayerApi {
            long sequence;Set<Long> live=new HashSet<>();
            public long acquireConfigLayer(String file,float priority,float threshold){assert Files.isRegularFile(Path.of(file));long id=++sequence;live.add(id);return id;}
            public void setConfigLayerBlending(long handle,float strength,float threshold){assert live.contains(handle);}
            public void releaseConfigLayer(long handle){assert live.remove(handle):"Config handle released twice";}
        }
        var api=new Api();var driver=new ConfLayerDriver();assert driver.acquireAll(api,game)&&api.live.size()==11;
        Path world=game.resolve("save");
        for(String file:List.of("biomes/plains.conf","zones/cave.conf")){Path path=world.resolve("rtx-remix/confs/"+file);Files.createDirectories(path.getParent());Files.writeString(path,"rtx.vertexColorStrength = .5\n");}
        driver.onWorldJoin(world);assert api.live.size()==13;assert driver.reload()&&api.live.size()==13;
        driver.onWorldLeave();assert api.live.size()==11;driver.releaseAll();assert api.live.isEmpty();
        System.out.println("PASS: official scene layers, precedence, reload/release, zone falloff and NBT schema");
    }
}
