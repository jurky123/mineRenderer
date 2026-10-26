/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.port.logic.*;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Render-thread lifecycle adapter for the official authored configuration layers. */
public final class PortSceneConfig implements AutoCloseable {
    private final ConfLayerDriver layers=new ConfLayerDriver();
    private final ViewDistanceFade fade=new ViewDistanceFade();
    private final ZoneRegistry zones=new ZoneRegistry();
    private volatile ClientLevel world;
    private volatile long epoch;
    public PortSceneConfig(RemixAPI api) {
        if(!layers.acquireAll(api,Minecraft.getInstance().gameDirectory.toPath()))
            throw new IllegalStateException("Required Remix water profile could not be acquired");
    }
    public void update(float partialTick,FogType medium) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level) join(mc.level);
        boolean water=medium==FogType.WATER;
        PortRenderer.api().setCameraMediumMaterial(water?PortTextures.waterMediumMaterial():0);
        layers.update(world,mc.gameRenderer.mainCamera().position(),water,
                fade.layerStrength(mc.options.getEffectiveRenderDistance()),partialTick,zones);
    }
    private void join(ClientLevel next) {
        layers.onWorldLeave();zones.clear();world=next;long session=++epoch;
        var server=Minecraft.getInstance().getSingleplayerServer();
        if(server==null)return;
        layers.onWorldJoin(server.getWorldPath(LevelResource.ROOT));
        server.execute(()->{
            if(epoch!=session)return;
            var saved=ZonePersistentState.getIfPresent(server);
            if(saved!=null&&epoch==session)zones.putAll(saved.snapshot());
            MarkerBlockScanner.rescan(server,zones,()->epoch==session);
        });
    }
    public void scan(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if(world!=null)MarkerBlockScanner.scanChunk(zones,chunk);
    }
    public void define(String arguments,Vec3 origin) {
        var zone=ZoneDefinition.parse("rtx zone define "+arguments,origin.x,origin.y,origin.z);
        zones.put(zone);
        var server=Minecraft.getInstance().getSingleplayerServer();var session=world;
        if(server!=null)server.execute(()->{if(world==session)ZonePersistentState.getOrCreate(server).put(zone);});
    }
    public void remove(String name) {
        zones.remove(name);var server=Minecraft.getInstance().getSingleplayerServer();var session=world;
        if(server!=null)server.execute(()->{if(world==session)ZonePersistentState.getOrCreate(server).remove(name);});
    }
    public boolean reload() {
        boolean result=layers.reload();var current=world;
        if(current!=null)join(current);
        return result;
    }
    public void rescan() {
        var server=Minecraft.getInstance().getSingleplayerServer();var session=world;
        if(server!=null)MarkerBlockScanner.rescan(server,zones,()->world==session);
    }
    public java.util.List<ConfLayerDriver.LayerStatus> snapshot(){return layers.snapshot();}
    public String describe(){return ConfLayerDriver.formatStatusSummary(layers.snapshot())+"; zones="+zones.zones().size();}
    @Override public void close(){epoch++;world=null;zones.clear();PortRenderer.api().setCameraMediumMaterial(0);layers.onWorldLeave();layers.releaseAll();}
}
