/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.light;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.*;
import net.minecraft.core.*;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
/** CPU-only section discovery; all native calls stay on the render thread. */
public final class PortBlockLights {
    private static final List<BlockLightRuleManager.Rule> rules=createRules();
    private static List<BlockLightRuleManager.Rule> createRules(){var all=new ArrayList<>(BlockLightDefaults.create().values());all.addAll(LampLightRules.create());return List.copyOf(all);}
    private static final Map<BlockState,List<BlockLightRuleManager.Rule>> matches=Collections.synchronizedMap(new IdentityHashMap<>());
    private record Published(LightEntry entry,PortLight light){}
    private static final Map<Long,List<Published>> sections=new HashMap<>();
    public static int count;
    private PortBlockLights(){}
    public static List<LightEntry> resolve(BlockState state,BlockPos pos){
        var matching=matches.computeIfAbsent(state,s->rules.stream().filter(r->r.matcher().test(s)).toList());
        List<LightEntry> result=new ArrayList<>();for(var rule:matching)result.addAll(rule.resolver().resolve(rule.id(),state,pos,null));return result;
    }
    public static List<LightEntry> capture(RenderSectionRegion region,SectionPos section){
        List<LightEntry> result=new ArrayList<>();var pos=new BlockPos.MutableBlockPos();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            pos.set(section.minBlockX()+x,section.minBlockY()+y,section.minBlockZ()+z);var state=region.getBlockState(pos);
            if(state.getLightEmission()>0)for(var entry:resolve(state,pos)){
                // Fully opaque adjacent blocks hide a panel. Partial blocks must retain it.
                boolean lamp=com.nvidia.rtxremix.port.texture.LampAppearance.rule(entry.key().ruleId())!=null;
                if(!lamp||!region.getBlockState(pos.relative(Direction.values()[entry.key().lightIndex()])).isSolidRender())result.add(entry);
            }
        }
        return List.copyOf(result);
    }
    public static void replace(long section,List<LightEntry> entries){
        var old=sections.get(section);if(old!=null&&old.stream().map(Published::entry).toList().equals(entries))return;
        remove(section);if(entries.isEmpty())return;
        List<Published> next=new ArrayList<>();for(var entry:entries){var key=entry.key();long hash=Long.rotateLeft(key.pos().asLong(),17)^((long)key.ruleId().hashCode()<<8)^key.lightIndex()^0x26230001L;next.add(new Published(entry,new PortLight(hash==0?1:hash)));}sections.put(section,List.copyOf(next));
    }
    public static void render(){
        var api=PortRenderer.api();count=0;
        // A Light block can occupy a section with no renderable mesh, so the terrain
        // asset map alone cannot own its unload. Never retain that light after its chunk.
        var world=net.minecraft.client.Minecraft.getInstance().level;
        for(long section:List.copyOf(sections.keySet()))if(!world.getChunkSource().hasChunk(SectionPos.x(section),SectionPos.z(section)))remove(section);
        for(var lights:sections.values())for(var published:lights){
            var e=published.entry();var info=PortLightInfo.build(e);
            published.light().publish(api,info,PortLightInfo.parameters(e,info));count++;
        }
    }
    public static void remove(long section){var previous=sections.remove(section);if(previous!=null)for(var light:previous)light.light().close(PortRenderer.api());}
    public static void clear(){for(long section:List.copyOf(sections.keySet()))remove(section);count=0;}
}
