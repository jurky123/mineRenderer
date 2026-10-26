/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.lang.reflect.Proxy;
import java.util.Map;
public final class PortModuleChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        com.nvidia.rtxremix.port.logic.PortLogicChecks.run();PortFrameChecks.run();
        com.nvidia.rtxremix.port.cloud.PortCloudChecks.run();
        SceneBoundaryChecks.run();
        WaterChecks.run();
        MaterialChecks.run();
        SurfaceLayerChecks.run();
        com.nvidia.rtxremix.port.light.LampChecks.run();
        var blend=com.nvidia.rtxremix.InstanceInfoBlendEXT.textureTimesVertexColor();
        check(blend.textureColorArg2Source==2&&blend.textureAlphaArg2Source==2&&blend.textureColorOperation==3&&blend.textureAlphaOperation==3,"Native instance must consume vertex tint AND alpha");
        var gate=new RemixInputGate();String[] visible={"0"};
        gate.open(RemixInputGate.Menu.DEVELOPER,(k,v)->visible[0]=v);
        check(gate.isCaptureSessionOpen()&&visible[0].equals("2"),"Open must own input");
        check(gate.onKey(87,1,false,false)==RemixInputGate.KeyDecision.BLOCK,"Held movement blocked");
        gate.followVisibility((k,b)->{b[0]='0';b[1]=0;return 2;});
        check(gate.isCaptureSessionOpen(),"Stale resolved visibility must not release input");
        gate.followVisibility((k,b)->{b[0]='2';b[1]=0;return 2;});
        check(gate.followVisibility((k,b)->{b[0]='0';b[1]=0;return 2;})==RemixInputGate.VisibilityChange.CLOSED,"Native close releases capture");
        check(gate.onKey(88,1,true,false)==RemixInputGate.KeyDecision.TOGGLE_MENU,"Alt-X opens");
        check(gate.onKey(88,1,true,true)==RemixInputGate.KeyDecision.PASS,"Ctrl-Alt-X not consumed");
        var rm=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class[]{ResourceManager.class},(p,m,a)->Map.of());
        try(var image=new NativeImage(1,2,true);var normal=new NativeImage(1,1,true);var spec=new NativeImage(1,1,true)) {
            image.setPixel(0,0,0x80402010);image.setPixel(0,1,0x40c0a080);
            var pixels=new SpritePixels(Identifier.parse("test:block/channels"),image,1,1,LabPBRConverter.buildVariantCache(rm));
            byte[] first=pixels.frame(0);
            check((first[0]&255)==64&&(first[1]&255)==32&&(first[2]&255)==16&&(first[3]&255)==128,"ARGB->RGBA channel order");
            pixels.currentFrame=0;pixels.nextFrame=1;pixels.interpolate=true;pixels.subFrame=1;pixels.duration=2;
            byte[] interpolated=pixels.frame(0);
            check((interpolated[0]&255)==128&&(interpolated[1]&255)==96&&(interpolated[2]&255)==72&&(interpolated[3]&255)==128,"Animation interpolation and current alpha");
            spec.setPixel(0,0,0x7f80e680);byte[] rough=new byte[1],metal=new byte[1],sss=new byte[4],emissive=new byte[4];
            LabPBRConverter.blitRoughness(rough,1,spec,0,0,1,1);LabPBRConverter.blitMetallic(metal,1,spec,0,0,1,1);LabPBRConverter.blitSSS(sss,1,spec,0,0,1,1);
            check((rough[0]&255)==63&&(metal[0]&255)==255&&(sss[0]&255)==84,"LabPBR roughness/metal/SSS reference values");
            LabPBRConverter.blitEmissive(emissive,1,first,spec,0,0,1,1,0);
            check((emissive[0]&255)==32&&(emissive[1]&255)==16&&(emissive[2]&255)==8,"LabPBR emission support");
            normal.setPixel(0,0,0xff808080);byte[] n=new byte[4];LabPBRConverter.blitNormal(n,1,normal,0,0,1,1);
            check((n[0]&255)==128&&(n[1]&255)==128&&(n[2]&255)>=254,"LabPBR normal reconstruction");
        }
        var bytes=new VertexBytes();for(int i=0;i<10000;i++)bytes.append(4).putInt(i);
        check(bytes.size()==40000&&java.nio.ByteBuffer.wrap(bytes.copy()).order(java.nio.ByteOrder.nativeOrder()).getInt(39996)==9999,"Growing vertex buffer preserves data");
        long generation=TerrainCapture.generation();TerrainCapture.clear();check(TerrainCapture.generation()==generation+1,"World invalidation advances worker generation");
        System.out.println("PASS: input ownership, native close, stale visibility, RGBA, animation, LabPBR, vertex growth, world generation");
    }
}
