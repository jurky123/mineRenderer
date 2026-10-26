/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.port.texture.*;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.lang.reflect.Proxy;
import java.nio.*;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
public final class PortFrameChecks {
    public static void run(){
        Matrix4f correction=new Matrix4f();PortViewModel.setViewCorrection(correction,new Matrix4f().translation(1,2,-3),1.2f);
        var point=correction.transformPosition(new Vector3f());
        assert point.distance(new Vector3f(.12f,.24f,-.3f))<1e-6:"Official camera-relative viewmodel scaling";
        var resources=(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class[]{ResourceManager.class},(p,m,a)->Map.of());
        try(var image=new NativeImage(16,16,true)){
            var pixels=new SpritePixels(Identifier.parse("test:particle/smoke"),image,16,16,LabPBRConverter.buildVariantCache(resources));
            var atlas=Identifier.parse("test:atlas");var region=new PortTextures.Region(pixels,0,.5f,.5f,1);
            var layer=new SingleQuadParticle.Layer(true,atlas,null);
            var collector=new PortParticles.Collector(layer,(id,u,v)->{assert id.equals(atlas);return region;});
            var state=new QuadParticleRenderState();state.add(layer,1,2,3,0,0,0,1,2,.25f,.5f,.5f,.75f,0x80604020,15728880);
            state.buildLayer(layer,collector);collector.finish();
            assert collector.groups.size()==1;
            var entry=collector.groups.entrySet().iterator().next();assert entry.getKey().kind()==PortTextures.Kind.BLEND;
            byte[] vertices=entry.getValue().copy();assert vertices.length==256;
            var buffer=ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
            for(int i=0;i<4;i++){
                int off=i*64;assert buffer.getInt(off+32)==0x80604020:"ARGB/alpha preserved without lightmap bake";
                assert buffer.getFloat(off+24)>=.5f&&buffer.getFloat(off+24)<=1;
                assert buffer.getFloat(off+28)>=0&&buffer.getFloat(off+28)<=.5f;
                assert Math.abs(buffer.getFloat(off+20)-1)<1e-6;
            }
            assert Math.abs(buffer.getFloat(0)-3)<1e-6&&Math.abs(buffer.getFloat(4))<1e-6&&buffer.getFloat(8)==3;
        }
        System.out.println("PASS: official viewmodel correction; actual 26.2 extracted particle positions, normals, UV and alpha");
    }
}
