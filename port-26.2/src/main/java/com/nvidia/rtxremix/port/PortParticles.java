/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.nio.*;
import java.util.*;

/** Native physical particle buckets using 26.2's exact extracted billboard poses and colors. */
public final class PortParticles implements AutoCloseable {
    private static final Identifier BLOCKS=Identifier.withDefaultNamespace("textures/atlas/blocks.png");
    private final List<PortRenderer.Asset> assets=new ArrayList<>();
    private final InstanceInfoBlendEXT particleBlend=InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(10);
    private final InstanceInfoBlendEXT terrainBlend=InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
    public static int quads,draws,peakQuads;
    public PortParticles(){particleBlend.allocNative(0);terrainBlend.allocNative(0);}
    public void render(ParticlesRenderState state,Vec3 camera) {
        clear();quads=draws=0;
        for(var group:state.particles)if(group instanceof QuadParticleRenderState quadState){
            for(var layer:quadState.layers()){
                boolean terrain=BLOCKS.equals(layer.textureAtlasLocation());
                Collector consumer=new Collector(layer);quadState.buildLayer(layer,consumer);consumer.finish();
                List<MeshBatch> batches=new ArrayList<>();
                for(var entry:consumer.groups.entrySet())batches.add(new MeshBatch(entry.getKey(),entry.getValue().copy(),true));
                if(batches.isEmpty())continue;
                float[] transform={1,0,0,(float)camera.x,0,1,0,(float)camera.y,0,0,1,(float)camera.z};
                assets.add(PortRenderer.createAsset(batches,transform,0x26270000L^layer.hashCode(),terrain?0:InstanceCategoryBit.PARTICLE,
                        terrain?terrainBlend.nativePtr:particleBlend.nativePtr));draws++;
            }
        }
        peakQuads=Math.max(peakQuads,quads);
    }
    public void clear(){for(var asset:assets)PortRenderer.removeAsset(asset);assets.clear();}
    @Override public void close(){clear();particleBlend.freeNative();terrainBlend.freeNative();}
    /** Dense quad emission; UV fragments retain vanilla's partial-sprite coordinates. */
    @FunctionalInterface interface Regions {PortTextures.Region resolve(Identifier atlas,float u,float v);}
    static final class Collector implements VertexConsumer {
        final SingleQuadParticle.Layer layer;
        final Regions regions;
        final Map<PortTextures.Key,VertexBytes> groups=new LinkedHashMap<>();
        final ByteBuffer quad=ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        int count,color=-1;float x,y,z,u,v;boolean pending;
        Collector(SingleQuadParticle.Layer layer){this(layer,PortTextures::region);}
        Collector(SingleQuadParticle.Layer layer,Regions regions){this.layer=layer;this.regions=regions;}
        void commit(){
            if(!pending)return;pending=false;
            quad.putFloat(x).putFloat(y).putFloat(z).putFloat(0).putFloat(0).putFloat(0).putFloat(u).putFloat(v).putInt(color);
            for(int pad=0;pad<7;pad++)quad.putInt(0);
            if(++count!=4)return;
            float cu=0,cv=0;for(int i=0;i<4;i++){cu+=quad.getFloat(i*64+24);cv+=quad.getFloat(i*64+28);}
            var region=regions.resolve(layer.textureAtlasLocation(),cu*.25f,cv*.25f);
            if(region==null)throw new IllegalStateException("Particle sprite absent from captured atlas: "+layer.textureAtlasLocation());
            Vector3f a=new Vector3f(quad.getFloat(64)-quad.getFloat(0),quad.getFloat(68)-quad.getFloat(4),quad.getFloat(72)-quad.getFloat(8));
            Vector3f b=new Vector3f(quad.getFloat(128)-quad.getFloat(0),quad.getFloat(132)-quad.getFloat(4),quad.getFloat(136)-quad.getFloat(8));
            var normal=a.cross(b);if(normal.lengthSquared()>0)normal.normalize();else normal.set(0,0,-1);
            for(int i=0;i<4;i++){
                int off=i*64;quad.putFloat(off+12,normal.x).putFloat(off+16,normal.y).putFloat(off+20,normal.z);
                quad.putFloat(off+24,(quad.getFloat(off+24)-region.u0())/(region.u1()-region.u0()));
                quad.putFloat(off+28,(quad.getFloat(off+28)-region.v0())/(region.v1()-region.v0()));
            }
            var key=PortTextures.key(region.pixels(),layer.translucent()?PortTextures.Kind.BLEND:PortTextures.Kind.CUTOUT);
            groups.computeIfAbsent(key,k->new VertexBytes()).append(256).put(quad.array());quads++;count=0;quad.clear();
        }
        void finish(){commit();if(count!=0)throw new IllegalStateException("Incomplete extracted particle quad");}
        @Override public VertexConsumer addVertex(float x,float y,float z){commit();this.x=x;this.y=y;this.z=z;u=v=0;color=-1;pending=true;return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){color=a<<24|r<<16|g<<8|b;return this;}
        @Override public VertexConsumer setColor(int c){color=c;return this;}
        @Override public VertexConsumer setUv(float u,float v){this.u=u;this.v=v;return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
        @Override public VertexConsumer setLineWidth(float width){return this;}
    }
}
