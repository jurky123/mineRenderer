/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.nvidia.rtxremix.port.sky.SkyMath;
import com.nvidia.rtxremix.minecraft.util.ClosestKHeap;
import org.joml.Matrix4f;
import java.util.*;
final class SceneBoundaryChecks {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    static void run(){
        var day=SkyMath.evaluateSkyLightState(0,0,0);var night=SkyMath.evaluateSkyLightState(.5f,0,0);var storm=SkyMath.evaluateSkyLightState(0,1,1);
        check(day.sunDirectRadiance()[0]>20&&day.moonDirectRadiance()[0]==0,"Noon direct source and horizon support");
        check(night.sunDirectRadiance()[0]==0&&night.moonDirectRadiance()[2]>0,"Night must not retain a noon sun");
        check(storm.sunDirectRadiance()[0]<day.sunDirectRadiance()[0]*.002f,"Official thunder extinction");
        for(int i=0;i<3;i++)check(day.sunPosition()[i]==-day.moonPosition()[i],"Opposite celestial direction");
        for(boolean zeroToOne:new boolean[]{true,false}){
            var projection=new Matrix4f().perspective((float)Math.toRadians(70),1.5f,1000,.05f,zeroToOne);
            for(float depth:new float[]{0,.1f,.5f,.9f,.99f,.9999f,1})check(Math.abs(PortDepth.projectedDepth(depth,.05f,1000,projection,zeroToOne)-(1-depth))<1e-6,"Projected Remix depth -> reversed 26.2 depth");
        }
        var registry=new RetainedEntityPartRegistry<Object,Object>();Object owner1=new String("same"),owner2=new String("same"),value1=new Object(),value2=new Object();List<Object> removed=new ArrayList<>();
        registry.beginFrame();registry.retain(owner1,0,1,1,v->true,()->value1,removed::add);registry.retain(owner2,0,1,1,v->true,()->value2,removed::add);registry.endFrame(removed::add);
        check(registry.size()==2,"Equal numeric owners must not share native state");
        registry.beginFrame();check(registry.retain(owner1,0,1,1,v->true,()->{throw new AssertionError("Recreated unchanged part");},removed::add)==value1,"Retain original instance");registry.endFrame(removed::add);
        check(removed.equals(List.of(value2)),"Retire invisible/despawned owner");registry.clear(removed::add);check(registry.size()==0&&removed.size()==2,"World clears every retained reference");
        var heap=new ClosestKHeap<Integer>(3);for(int value:new int[]{5,1,7,2,3})heap.offer(value,value);var kept=new HashSet<Integer>();for(int i=0;i<heap.size();i++)kept.add(heap.get(i));check(kept.equals(Set.of(1,2,3))&&heap.overflowCount()==2,"Offscreen budget selects nearest, independent of insertion order");heap.clear();
        System.out.println("PASS: official sky day/night/weather, reversed depth, retained owner/part lifecycle, closest-first budget");
    }
}
