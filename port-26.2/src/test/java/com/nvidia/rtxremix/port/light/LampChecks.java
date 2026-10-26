/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.light;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
public final class LampChecks {
    public static void run(){
        var id=Identifier.parse("rtxremix:port_lamps/glowstone");
        var entries=LampLightRules.box(id,15,null,BlockPos.ZERO,0,0,0,1,1,1,75);
        assert entries.size()==6;
        for(var e:entries){
            float cx=e.xAxisY()*e.yAxisZ()-e.xAxisZ()*e.yAxisY();
            float cy=e.xAxisZ()*e.yAxisX()-e.xAxisX()*e.yAxisZ();
            float cz=e.xAxisX()*e.yAxisY()-e.xAxisY()*e.yAxisX();
            assert cx==e.directionX()&&cy==e.directionY()&&cz==e.directionZ():"Panel winding must face out";
            assert e.xSize()==1&&e.ySize()==1;
            assert (e.x()-.5f)*cx+(e.y()-.5f)*cy+(e.z()-.5f)*cz>.5f:"Light must sit outside its opaque source";
            var info=PortLightInfo.build(e);assert info.rectExt!=null&&info.sphereExt==null;
            assert info.rectExt.xSize==1&&info.rectExt.ySize==1&&info.rectExt.direction.x==cx;
        }
        var lantern=LampLightRules.box(Identifier.parse("rtxremix:port_lamps/lantern"),15,null,BlockPos.ZERO,5/16f,0,5/16f,11/16f,7/16f,11/16f,220);
        float area=0;for(var e:lantern)area+=e.xSize()*e.ySize();assert area==.9375f:"Lantern body area, not its whole voxel";
        var cylinder=LightEntry.cylinder(new BlockLightKey(BlockPos.ZERO,Identifier.parse("test:crystal"),0),10,null,0,1,2,.1f,1,0,0,.5f,null,50f,.3f);
        var nativeCylinder=PortLightInfo.build(cylinder);assert nativeCylinder.cylinderExt!=null&&nativeCylinder.sphereExt==null&&nativeCylinder.cylinderExt.axisLength==.5f;
        var sprite=Identifier.parse("minecraft:block/lantern");
        assert BuiltinEmission.gain(sprite)==1&&LampAppearance.surfaceIntensity(sprite)==35;
        assert LampAppearance.surfaceIntensity(Identifier.parse("test:block/lantern"))==100;
        LampAppearance.observe(sprite,new byte[]{(byte)255,(byte)128,0,(byte)255});
        var tint=LampAppearance.tint(LampAppearance.Lamp.LANTERN);assert tint[0]==1&&tint[1]>.21f&&tint[1]<.22f&&tint[2]==0;
        LampAppearance.clear();assert LampAppearance.tint(LampAppearance.Lamp.LANTERN)[1]==.65f:"Reload must drop old pack tint";
        assert LampAppearance.rule(id)==LampAppearance.Lamp.GLOWSTONE&&LampAppearance.rule(Identifier.parse("other:port_lamps/glowstone"))==null;
        System.out.println("PASS: lamp panel winding, self-shadow offset, native shape, body area, authored masks and reload tint");
    }
}
