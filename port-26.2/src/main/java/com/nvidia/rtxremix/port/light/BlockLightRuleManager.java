/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.light;
import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.Predicate;
/** Minecraft binding for the unmodified authored default-rule formulas. */
final class BlockLightRuleManager {
    static final float RADIANCE_PER_LIGHT_LEVEL=5;
    interface ModelSampler{}
    interface Resolver {List<LightEntry> resolve(Identifier id,BlockState state,BlockPos pos,ModelSampler sampler);}
    record Rule(Identifier id,Predicate<BlockState> matcher,Resolver resolver){}
    record Vec3(float x,float y,float z){static Vec3 fromAxis(Direction.Axis axis){return switch(axis){case X->new Vec3(1,0,0);case Y->new Vec3(0,1,0);case Z->new Vec3(0,0,1);};}}
    static Rule rule(Identifier id,Predicate<BlockState> matcher,Resolver resolver){return new Rule(id,matcher,resolver);}
    static Identifier id(String namespace,String path){return Identifier.fromNamespaceAndPath(namespace,"rtx_remix/block_lights/"+path);}
    static LightEntry sphere(Identifier id,int index,BlockState state,BlockPos pos,float x,float y,float z,float radius,LightEntry.ColorOverride color,Float intensity){return LightEntry.sphere(new BlockLightKey(pos.immutable(),id,index),state.getLightEmission(),state.getBlock(),pos.getX()+x,pos.getY()+y,pos.getZ()+z,radius,color,intensity,LightEntry.defaultVolumetricRadianceScale());}
    static LightEntry cylinder(Identifier id,int index,BlockState state,BlockPos pos,float x,float y,float z,float radius,Vec3 axis,float length,LightEntry.ColorOverride color,Float intensity){return LightEntry.cylinder(new BlockLightKey(pos.immutable(),id,index),state.getLightEmission(),state.getBlock(),pos.getX()+x,pos.getY()+y,pos.getZ()+z,radius,axis.x(),axis.y(),axis.z(),length,color,intensity,LightEntry.defaultVolumetricRadianceScale());}
}
