/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.light;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;
/** Official ordinary held/dropped-item light rules; custom beacon content remains separate. */
public final class PortItemLights {
    private record Slot(Entity owner,int hand){}
    private record Spec(float intensity,float radius,float r,float g,float b,float volume){}
    private static final Map<Slot,PortLight> lights=new HashMap<>();
    private static final HeldLightPlacement placement=new HeldLightPlacement();
    private static final FirstPersonShadowModelPlacement shadowPlacement=new FirstPersonShadowModelPlacement();
    private static final List<FirstPersonShadowModelPlacement.LightConstraint> constraints=new ArrayList<>();
    private static Vec3 shadowOffset=Vec3.ZERO;
    public static Vec3 shadowOffset(){return shadowOffset;}
    private static long lastUpdate;
    public static int count;
    private PortItemLights(){}
    public static void render(){
        var mc=Minecraft.getInstance();var api=PortRenderer.api();var seen=new HashSet<Slot>();long now=System.nanoTime();float dt=lastUpdate==0?0:(now-lastUpdate)/1e9f;lastUpdate=now;
        constraints.clear();
        for(Entity entity:mc.level.entitiesForRendering()){
            if(entity instanceof ItemEntity item)publish(entity,2,spec(item.getItem()),entity.position().add(0,entity.getBbHeight()*.75,0),false,seen);
            else if(entity instanceof LivingEntity living){
                for(int hand=0;hand<2;hand++){
                    var stack=hand==0?living.getMainHandItem():living.getOffhandItem();var spec=spec(stack);
                    boolean flame=stack.getItem() instanceof BlockItem block&&(block.getBlock() instanceof net.minecraft.world.level.block.TorchBlock||block.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock);
                    if(flame&&living.isUnderWater())spec=null;
                    boolean local=living==mc.player&&mc.options.getCameraType().isFirstPerson();
                    Vec3 position;
                    var equipment=hand==0?EquipmentSlot.MAINHAND:EquipmentSlot.OFFHAND;
                    if(local){if(spec==null){placement.reset(equipment);continue;}position=placement.resolve(mc.level,mc.gameRenderer.mainCamera(),living,equipment,spec.radius()*.1f,dt);}
                    else{double yaw=Math.toRadians(living.yBodyRot),fx=-Math.sin(yaw),fz=Math.cos(yaw);double side=(living.getMainArm()==HumanoidArm.RIGHT)==(hand==0)?-1:1;position=living.position().add(fx*.35+fz*side*.38,.88,fz*.35-fx*side*.38);}
                    publish(living,hand,spec,position,local,seen);
                }
            }
        }
        for(var iterator=lights.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!seen.contains(entry.getKey())){entry.getValue().close(api);iterator.remove();}}count=lights.size();
        shadowOffset=mc.player!=null&&mc.options.getCameraType().isFirstPerson()?shadowPlacement.resolve(mc.level,mc.gameRenderer.mainCamera(),mc.player,constraints,dt):Vec3.ZERO;
    }
    private static Spec spec(ItemStack stack){
        if(stack.isEmpty()||!(stack.getItem() instanceof BlockItem block))return null;
        var state=block.getBlock().defaultBlockState();int level=state.getLightEmission();if(level<=0)return null;
        Spec best=null;float fluxR=0,fluxG=0,fluxB=0;
        for(var entry:PortBlockLights.resolve(state,BlockPos.ZERO)){
            float intensity=PortLightInfo.intensity(entry);
            float[] rgb=PortLightInfo.color(entry);
            if(entry.shape()==LightShape.RECT){float flux=entry.xSize()*entry.ySize()*intensity;fluxR+=flux*rgb[0];fluxG+=flux*rgb[1];fluxB+=flux*rgb[2];continue;}
            var candidate=new Spec(intensity,Math.max(.1f,entry.radius()),entry.hasColor()?entry.colorR():1,entry.hasColor()?entry.colorG():1,entry.hasColor()?entry.colorB():1,entry.volumetricRadianceScale());
            if(best==null||power(candidate)>power(best))best=candidate;
        }
        float peak=Math.max(fluxR,Math.max(fluxG,fluxB));
        if(peak>0)return new Spec(peak/(4*(float)Math.PI*.2f*.2f),.2f,fluxR/peak,fluxG/peak,fluxB/peak,.15f);
        return best==null?new Spec(level*5,.1f,1,1,1,.3f):best;
    }
    private static float power(Spec s){return s.radius()*s.radius()*s.intensity()*(s.r()*.2126f+s.g()*.7152f+s.b()*.0722f);}
    private static void publish(Entity owner,int hand,Spec spec,Vec3 position,boolean local,Set<Slot> seen){
        if(spec==null)return;
        var slot=new Slot(owner,hand);seen.add(slot);var api=PortRenderer.api();var light=lights.computeIfAbsent(slot,k->new PortLight(0x26250000L^owner.getUUID().getMostSignificantBits()^Long.rotateLeft(owner.getUUID().getLeastSignificantBits(),7)^hand));
        float scale=local?.1f:1,intensity=spec.intensity()/(scale*scale),radius=spec.radius()*scale,volume=local?0:spec.volume();
        if(local)constraints.add(new FirstPersonShadowModelPlacement.LightConstraint(position,radius));
        light.publish(api,LightInfo.builder().radiance(spec.r()*intensity,spec.g()*intensity,spec.b()*intensity).sphereExt(LightInfoSphereEXT.builder().position((float)position.x,(float)position.y,(float)position.z).radius(radius).volumetricRadianceScale(volume).build()).build(),(float)position.x,(float)position.y,(float)position.z,radius,intensity,spec.r(),spec.g(),spec.b(),volume);
    }
    public static void clear(){for(var light:lights.values())light.close(PortRenderer.api());lights.clear();placement.clear();shadowPlacement.clear();constraints.clear();shadowOffset=Vec3.ZERO;lastUpdate=0;count=0;}
}
