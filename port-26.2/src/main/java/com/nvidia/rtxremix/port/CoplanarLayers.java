/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.port.texture.PortTextures;
import com.nvidia.rtxremix.port.texture.*;
import java.nio.*;
import java.util.*;

/** CPU-side version of upstream's opaque promotion and coextensive decal classification.
 * A translucent sprite over an opaque face is a decal, not a second intersecting surface. */
public final class CoplanarLayers {
    private record Face(int x0,int y0,int z0,int x1,int y1,int z1,int nx,int ny,int nz){}
    private record Group(PortTextures.Key material,int categories){}
    private record Source(MeshBatch batch,int offset){}
    private CoplanarLayers(){}
    private static int q(float x){return Math.round(x*4096);}
    private static Face face(ByteBuffer b,int start){
        float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int i=0;i<4;i++)for(int a=0;a<3;a++){float v=b.getFloat(start+i*64+a*4);lo[a]=Math.min(lo[a],v);hi[a]=Math.max(hi[a],v);}
        return new Face(q(lo[0]),q(lo[1]),q(lo[2]),q(hi[0]),q(hi[1]),q(hi[2]),q(b.getFloat(start+12)),q(b.getFloat(start+16)),q(b.getFloat(start+20)));
    }
    private static boolean ordinary(PortTextures.Kind kind){return kind==PortTextures.Kind.SOLID||kind==PortTextures.Kind.CUTOUT||kind==PortTextures.Kind.BLEND;}
    public static List<MeshBatch> classify(List<MeshBatch> input){
        Map<Face,Source> opaque=new HashMap<>();
        for(var batch:input)if(batch.quads()&&ordinary(batch.material().kind())&&batch.material().kind()!=PortTextures.Kind.BLEND&&!batch.material().pixels().hasTransparency){
            var b=ByteBuffer.wrap(batch.vertices()).order(ByteOrder.nativeOrder());for(int i=0;i<b.capacity();i+=256)opaque.put(face(b,i),new Source(batch,i));
        }
        Map<Face,SpritePixels> grass=new HashMap<>();
        for(var batch:input)if(batch.quads()&&batch.material().pixels().hasTransparency&&ordinary(batch.material().kind())){
            var b=ByteBuffer.wrap(batch.vertices()).order(ByteOrder.nativeOrder());
            for(int i=0;i<b.capacity();i+=256){var f=face(b,i);var base=opaque.get(f);if(base==null)continue;
                var source=ByteBuffer.wrap(base.batch().vertices()).order(ByteOrder.nativeOrder());
                int bt=uniformTint(source,base.offset()),ot=uniformTint(b,i);if(bt==0||ot==0)continue;
                var merged=GrassComposites.get(base.batch().material().pixels(),batch.material().pixels(),bt,ot);if(merged!=null)grass.put(f,merged);
            }
        }
        Map<Group,VertexBytes> groups=new LinkedHashMap<>();List<MeshBatch> other=new ArrayList<>();
        for(var batch:input){
            if(!batch.quads()||!ordinary(batch.material().kind())){other.add(batch);continue;}
            var b=ByteBuffer.wrap(batch.vertices()).order(ByteOrder.nativeOrder());
            for(int i=0;i<b.capacity();i+=256){
                var kind=batch.material().kind();int categories=batch.categories();
                var composite=grass.get(face(b,i));if(composite!=null&&batch.material().pixels().hasTransparency)continue;
                if(batch.material().pixels().hasTransparency&&opaque.containsKey(face(b,i))){kind=PortTextures.Kind.DECAL;categories|=InstanceCategoryBit.DECAL_STATIC;}
                else if(kind==PortTextures.Kind.CUTOUT&&!batch.material().pixels().hasTransparency)kind=PortTextures.Kind.SOLID;
                var group=new Group(PortTextures.key(composite==null?batch.material().pixels():composite,kind),categories);
                var out=groups.computeIfAbsent(group,k->new VertexBytes()).append(256);int start=out.position();out.put(batch.vertices(),i,256);
                if(composite!=null)for(int v=0;v<4;v++)out.putInt(start+v*64+32,-1);
            }
        }
        for(var e:groups.entrySet())other.add(new MeshBatch(e.getKey().material(),e.getValue().copy(),true,e.getKey().categories()));
        return List.copyOf(other);
    }
    private static int uniformTint(ByteBuffer b,int start){int tint=b.getInt(start+32);for(int i=1;i<4;i++)if(b.getInt(start+i*64+32)!=tint)return 0;return tint;}
}
