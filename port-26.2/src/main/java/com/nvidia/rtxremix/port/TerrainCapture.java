/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.vertex.*;
import com.nvidia.rtxremix.port.texture.PortTextures;
import com.nvidia.rtxremix.port.texture.PortWater;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.resources.Identifier;
import net.minecraft.core.SectionPos;
import java.nio.*;
import java.util.*;

/** Worker-side CPU snapshots, grouped by actual sprite and material category. */
public final class TerrainCapture {
    public record Section(long key,int x,int y,int z,long generation,List<MeshBatch> batches,List<com.nvidia.rtxremix.port.light.LightEntry> lights,int bytes) {}
    private static final Identifier BLOCKS=Identifier.withDefaultNamespace("textures/atlas/blocks.png");
    private static final Map<Long,Section> pending=new LinkedHashMap<>();
    private static long generation;
    private static int bytes;
    private static final ThreadLocal<EnumMap<ChunkSectionLayer,it.unimi.dsi.fastutil.ints.IntArrayList>> tints=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> cutoutLeaves=new ThreadLocal<>();
    private static final int MAX_PENDING_BYTES=128*1024*1024;
    private TerrainCapture() {}
    public static void begin(boolean cutout){cutoutLeaves.set(cutout);tints.set(new EnumMap<>(ChunkSectionLayer.class));}
    public static boolean cutoutLeaves(){return Boolean.TRUE.equals(cutoutLeaves.get());}
    public static boolean compiling(){return tints.get()!=null;}
    public static void unlit(ChunkSectionLayer layer,int argb,int count) {
        var map=tints.get();if(map==null) return;
        var colors=map.computeIfAbsent(layer,k->new it.unimi.dsi.fastutil.ints.IntArrayList());
        for(int i=0;i<count;i++) colors.add(argb);
    }
    public static synchronized long generation(){return generation;}
    public static synchronized void clear(){generation++;pending.clear();bytes=0;}
    public static void capture(SectionPos pos,SectionCompiler.Results result,long buildGeneration,List<com.nvidia.rtxremix.port.light.LightEntry> lights) {
        Map<PortTextures.Key,VertexBytes> groups=new LinkedHashMap<>();
        for(var entry:result.renderedLayers.entrySet()) {
            MeshData mesh=entry.getValue();var format=mesh.drawState().format();
            var position=format.getElement("Position");var normal=format.getElement("Normal");
            var uv=format.getElement("UV0");var color=format.getElement("Color");
            if(position==null||uv==null) throw new IllegalStateException("Missing terrain position/UV");
            ByteBuffer in=mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());int stride=format.getVertexSize();
            var colors=tints.get().get(entry.getKey());
            if(colors==null||colors.size()!=mesh.drawState().vertexCount()) throw new IllegalStateException("Unlit tint/mesh vertex mismatch: "+entry.getKey());
            if(mesh.drawState().vertexCount()%4!=0) throw new IllegalStateException("Non-quad terrain");
            PortTextures.Kind kind=switch(entry.getKey()) {
                case SOLID -> PortTextures.Kind.SOLID;
                case CUTOUT -> PortTextures.Kind.CUTOUT;
                case TRANSLUCENT -> PortTextures.Kind.BLEND;
            };
            for(int q=0;q<mesh.drawState().vertexCount();q+=4) {
                int start=in.position()+q*stride;float u=0,v=0;
                for(int i=0;i<4;i++){u+=in.getFloat(start+i*stride+uv.offset());v+=in.getFloat(start+i*stride+uv.offset()+4);}
                var region=PortTextures.region(BLOCKS,u*0.25f,v*0.25f);
                if(region==null) throw new IllegalStateException("Terrain sprite lookup failed at "+u*0.25f+","+v*0.25f);
                var key=PortTextures.key(region.pixels(),kind);
                ByteBuffer out=groups.computeIfAbsent(key,k->new VertexBytes()).append(4*64);
                int a=start+position.offset(),b=a+stride,c=b+stride;
                float bx=in.getFloat(b)-in.getFloat(a),by=in.getFloat(b+4)-in.getFloat(a+4),bz=in.getFloat(b+8)-in.getFloat(a+8);
                float cx=in.getFloat(c)-in.getFloat(a),cy=in.getFloat(c+4)-in.getFloat(a+4),cz=in.getFloat(c+8)-in.getFloat(a+8);
                float nx=by*cz-bz*cy,ny=bz*cx-bx*cz,nz=bx*cy-by*cx;
                float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(len>0){nx/=len;ny/=len;nz/=len;}
                boolean water=key.kind()==PortTextures.Kind.WATER;
                int ua=0,va=0,ub=0,vb=0;
                if(water){
                    ua=PortWater.uAxis(nx,ny,nz);va=PortWater.vAxis(nx,ny,nz);
                    ub=(ua==0?pos.x():ua==1?pos.y():pos.z())*16;
                    vb=(va==0?pos.x():va==1?pos.y():pos.z())*16;
                }
                for(int i=0;i<4;i++) {
                    int base=start+i*stride;
                    for(int axis=0;axis<3;axis++) out.putFloat(in.getFloat(base+position.offset()+axis*4));
                    if(normal!=null) for(int axis=0;axis<3;axis++) out.putFloat(in.get(base+normal.offset()+axis)/127f);
                    else out.putFloat(nx).putFloat(ny).putFloat(nz);
                    if(water){
                        out.putFloat(PortWater.coordinate(ub,in.getFloat(base+position.offset()+ua*4)));
                        out.putFloat(PortWater.coordinate(vb,in.getFloat(base+position.offset()+va*4)));
                    }else{
                        out.putFloat((in.getFloat(base+uv.offset())-region.u0())/(region.u1()-region.u0()));
                        out.putFloat((in.getFloat(base+uv.offset()+4)-region.v0())/(region.v1()-region.v0()));
                    }
                    out.putInt(colors.getInt(q+i));
                    for(int pad=0;pad<7;pad++) out.putInt(0);
                }
            }
        }
        List<MeshBatch> batches=new ArrayList<>();int size=0;
        for(var e:groups.entrySet()){byte[] data=e.getValue().copy();size+=data.length;batches.add(new MeshBatch(e.getKey(),data,true));}
        Section section=new Section(pos.asLong(),pos.x(),pos.y(),pos.z(),buildGeneration,CoplanarLayers.classify(batches),lights,size+lights.size()*128);
        tints.remove();
        cutoutLeaves.remove();
        synchronized(TerrainCapture.class) {
            if(buildGeneration!=generation) return;
            Section old=pending.get(section.key());int next=bytes-(old==null?0:old.bytes())+section.bytes();
            if(next>MAX_PENDING_BYTES) throw new IllegalStateException("Terrain capture exceeds bounded queue");
            pending.put(section.key(),section);bytes=next;
        }
    }
    public static synchronized List<Section> drain(int limit) {
        List<Section> result=pending.values().stream().limit(limit).toList();
        for(Section section:result){pending.remove(section.key());bytes-=section.bytes();}
        return result;
    }
}
