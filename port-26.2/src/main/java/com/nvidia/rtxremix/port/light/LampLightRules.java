/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.light;

import com.nvidia.rtxremix.port.texture.LampAppearance.Lamp;
import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

/** Calibrated hybrid lamps: visible mesh emission plus outward-facing analytic panels.
 * These are not a claim of energy equivalence to mesh-only path tracing. */
final class LampLightRules {
    static List<BlockLightRuleManager.Rule> create(){
        var result=new ArrayList<BlockLightRuleManager.Rule>();
        Block[] blocks={Blocks.GLOWSTONE,Blocks.LANTERN,Blocks.SOUL_LANTERN,Blocks.SEA_LANTERN,
            Blocks.SHROOMLIGHT,Blocks.OCHRE_FROGLIGHT,Blocks.VERDANT_FROGLIGHT,Blocks.PEARLESCENT_FROGLIGHT,Blocks.REDSTONE_LAMP};
        Lamp[] lamps=Lamp.values();
        for(int i=0;i<blocks.length;i++){
            Block block=blocks[i];Lamp lamp=lamps[i];
            Identifier id=Identifier.fromNamespaceAndPath("rtxremix","port_lamps/"+lamp.name().toLowerCase(Locale.ROOT));
            result.add(BlockLightRuleManager.rule(id,s->s.is(block)&&s.getLightEmission()>0,(rule,state,pos,sampler)->{
                boolean lantern=lamp==Lamp.LANTERN||lamp==Lamp.SOUL_LANTERN;
                boolean hanging=lantern&&state.getValue(BlockStateProperties.HANGING);
                float x0=lantern?5/16f:0,x1=lantern?11/16f:1;
                float y0=lantern?(hanging?1/16f:0):0,y1=lantern?(hanging?8/16f:7/16f):1;
                return box(rule,state.getLightEmission(),block,pos,x0,y0,x0,x1,y1,x1,
                    (lantern?220:75)*state.getLightEmission()/15f);
            }));
        }
        return List.copyOf(result);
    }
    /** Full panel dimensions; the offset prevents self-shadowing by the source mesh. */
    static List<LightEntry> box(Identifier id,int level,Block block,BlockPos pos,
            float x0,float y0,float z0,float x1,float y1,float z1,float radiance){
        List<LightEntry> out=new ArrayList<>(6);float eps=1/1024f;
        float cx=(x0+x1)*.5f,cy=(y0+y1)*.5f,cz=(z0+z1)*.5f;
        for(Direction d:Direction.values()){
            float nx=d.getStepX(),ny=d.getStepY(),nz=d.getStepZ();
            float px=cx+nx*((x1-x0)*.5f+eps),py=cy+ny*((y1-y0)*.5f+eps),pz=cz+nz*((z1-z0)*.5f+eps);
            // Choose orthonormal axes with cross(U,V)==outward normal.
            float ux=ny==0?-nz:1,uy=0,uz=ny==0?nx:0;
            float vx=ny*uz,vy=nz*ux-nx*uz,vz=-ny*ux;
            float width=Math.abs(ux)*(x1-x0)+Math.abs(uz)*(z1-z0);
            float height=Math.abs(vx)*(x1-x0)+Math.abs(vy)*(y1-y0)+Math.abs(vz)*(z1-z0);
            out.add(LightEntry.rect(new BlockLightKey(pos.immutable(),id,d.ordinal()),level,block,
                pos.getX()+px,pos.getY()+py,pos.getZ()+pz,ux,uy,uz,width,vx,vy,vz,height,nx,ny,nz,
                LightEntry.ColorOverride.NONE,radiance,.15f));
        }
        return List.copyOf(out);
    }
}
