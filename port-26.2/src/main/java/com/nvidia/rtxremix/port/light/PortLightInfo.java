/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.light;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.texture.LampAppearance;

/** Shape-preserving native descriptions. No native calls, so this is CPU-testable. */
public final class PortLightInfo {
    private PortLightInfo(){}
    static float intensity(LightEntry e){return (e.hasIntensity()?e.intensity():e.lightLevel()*5)*e.authoredIntensityMultiplier();}
    static float[] color(LightEntry e){
        var lamp=LampAppearance.rule(e.key().ruleId());
        return lamp!=null?LampAppearance.tint(lamp):new float[]{e.hasColor()?e.colorR():1,e.hasColor()?e.colorG():1,e.hasColor()?e.colorB():1};
    }
    public static LightInfo build(LightEntry e){
        float[] rgb=color(e);float intensity=intensity(e);
        var info=LightInfo.builder().radiance(rgb[0]*intensity,rgb[1]*intensity,rgb[2]*intensity);
        var shaping=new LightInfoLightShaping();
        shaping.direction=new Float3D(e.shapingDirectionX(),e.shapingDirectionY(),e.shapingDirectionZ());
        shaping.coneAngleDegrees=e.coneAngleDegrees();shaping.coneSoftness=e.coneSoftness();shaping.focusExponent=e.focusExponent();
        switch(e.shape()){
            case SPHERE->{var s=LightInfoSphereEXT.builder().position(e.x(),e.y(),e.z()).radius(e.radius()).volumetricRadianceScale(e.volumetricRadianceScale());if(e.hasShaping())s.shaping(shaping);info.sphereExt(s.build());}
            case CYLINDER->info.cylinderExt(LightInfoCylinderEXT.builder().position(e.x(),e.y(),e.z()).radius(e.radius()).axis(e.axisX(),e.axisY(),e.axisZ()).axisLength(e.axisLength()).volumetricRadianceScale(e.volumetricRadianceScale()).build());
            case RECT->{var s=LightInfoRectEXT.builder().position(e.x(),e.y(),e.z()).xAxis(e.xAxisX(),e.xAxisY(),e.xAxisZ()).xSize(e.xSize()).yAxis(e.yAxisX(),e.yAxisY(),e.yAxisZ()).ySize(e.ySize()).direction(e.directionX(),e.directionY(),e.directionZ()).volumetricRadianceScale(e.volumetricRadianceScale());if(e.hasShaping())s.shaping(shaping);info.rectExt(s.build());}
            case DISK->{var s=LightInfoDiskEXT.builder().position(e.x(),e.y(),e.z()).xAxis(e.xAxisX(),e.xAxisY(),e.xAxisZ()).xRadius(e.xSize()).yAxis(e.yAxisX(),e.yAxisY(),e.yAxisZ()).yRadius(e.ySize()).direction(e.directionX(),e.directionY(),e.directionZ()).volumetricRadianceScale(e.volumetricRadianceScale());if(e.hasShaping())s.shaping(shaping);info.diskExt(s.build());}
        }
        return info.build();
    }
    static float[] parameters(LightEntry e,LightInfo i){return new float[]{
        e.shape().ordinal(),e.x(),e.y(),e.z(),e.radius(),e.axisX(),e.axisY(),e.axisZ(),e.axisLength(),
        e.xAxisX(),e.xAxisY(),e.xAxisZ(),e.xSize(),e.yAxisX(),e.yAxisY(),e.yAxisZ(),e.ySize(),
        e.directionX(),e.directionY(),e.directionZ(),e.hasShaping()?1:0,e.shapingDirectionX(),e.shapingDirectionY(),e.shapingDirectionZ(),
        e.coneAngleDegrees(),e.coneSoftness(),e.focusExponent(),e.volumetricRadianceScale(),i.radiance.x,i.radiance.y,i.radiance.z};}
}
