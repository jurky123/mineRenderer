/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.nvidia.rtxremix.port.texture.PortWater;

final class WaterChecks {
    static void run(){
        for(int section:new int[]{-1874999,-100,-2,-1,0,1,2,100,1874998}){
            float a=PortWater.coordinate(section*16,16),b=PortWater.coordinate((section+1)*16,0);
            float difference=a-b;
            assert Math.abs(difference-Math.round(difference))<1e-6:"Wave phase at section boundary";
            assert Math.abs((PortWater.coordinate(section*16,10.1f)-PortWater.coordinate(section*16,9.9f))-.02f)<1e-6:"No UV wrap inside a quad";
        }
        assert PortWater.uAxis(0,1,0)==0&&PortWater.vAxis(0,1,0)==2;
        assert PortWater.uAxis(1,0,0)==2&&PortWater.vAxis(1,0,0)==1;
        assert PortWater.uAxis(0,0,1)==0&&PortWater.vAxis(0,0,1)==1;
        assert Math.abs(PortWater.coordinate(-12345678.25,1.75f)-PortWater.coordinate(-12345680,3.5f))<1e-6:"Feature and terrain world-space phase";
        for(float color:new float[]{PortWater.RED,PortWater.GREEN,PortWater.BLUE}){
            assert PortWater.transmission(color,0)==1;
            assert PortWater.transmission(color,1)>PortWater.transmission(color,5);
            assert PortWater.transmission(color,5)>PortWater.transmission(color,10);
            assert PortWater.transmission(color,1)<Math.pow(.97,.1):"Fix near-clear old ten-metre transmission";
        }
        assert PortWater.RED<PortWater.BLUE&&PortWater.BLUE<PortWater.GREEN:"Depth turns subdued cyan-green";
        for(float color:new float[]{PortWater.RED,PortWater.GREEN,PortWater.BLUE}){
            assert PortWater.transmission(color,.25f)>.94f:"Very shallow water preserves bottom color";
            assert PortWater.transmission(color,1)>.79f:"One metre retains useful bottom illumination";
            assert PortWater.transmission(color,10)<.25f:"Deep water must attenuate every color channel";
        }
        assert PortWater.transmission(PortWater.RED,5)>.32f:"Avoid excessively dark red extinction at moderate depth";
        assert PortWater.transmission(PortWater.GREEN,5)>.48f:"Keep moderate-depth bottom sunlight visible";
        assert Math.abs(PortWater.RED/PortWater.GREEN-.8f/.94f)<1e-6:"Density tuning preserves the prior red/green ratio";
        assert Math.abs(PortWater.BLUE/PortWater.GREEN-.93f/.94f)<1e-6:"Density tuning preserves the prior blue/green ratio";
        double f0=Math.pow((PortWater.IOR-1)/(PortWater.IOR+1),2);
        assert f0>.02&&f0<.021:"Water dielectric Fresnel retained";
        System.out.println("PASS: water world-space seams, local UV derivatives, face projection and depth absorption");
    }
}
