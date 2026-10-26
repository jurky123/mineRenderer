/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.util.*;

/** Tee vanilla's prepared feature vertices; never evaluate a model or animation a second time. */
public final class FeatureCapture {
    public interface RenderTypeAccess { Object remix$setup(); }
    public interface RenderSetupAccess { Map<String,?> remix$textures(); }
    public interface TextureBindingAccess { Identifier remix$location(); }
    private static final List<Tee> tees=new ArrayList<>();
    private record BatchKey(PortTextures.Key material,int categories){}
    private static final Map<BatchKey,VertexBytes> quads=new LinkedHashMap<>(),triangles=new LinkedHashMap<>();
    private static final Set<String> unsupported=new HashSet<>();
    private static boolean active;
    public static int lastVertices,lastBatches;
    private FeatureCapture() {}
    public record Definition(Identifier texture,PortTextures.Kind kind){}
    public static Definition definition(RenderType type){
        String shader=type.pipeline().getFragmentShader().getPath();
        if(type.isOutline()||shader.contains("shadow")||shader.contains("glint"))return null;
        Map<String,?> textures=((RenderSetupAccess)((RenderTypeAccess)type).remix$setup()).remix$textures();
        Object binding=textures.get("Sampler0");if(binding==null&&!textures.isEmpty())binding=textures.values().iterator().next();if(binding==null)return null;
        boolean cutout=type.pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT");
        var kind=shader.equals("core/text")?PortTextures.Kind.TEXT:type.hasBlending()?PortTextures.Kind.BLEND:cutout?PortTextures.Kind.CUTOUT:PortTextures.Kind.SOLID;
        return new Definition(((TextureBindingAccess)binding).remix$location(),kind);
    }
    public static void flushPending(){for(Tee tee:tees)tee.finish();}
    public static void begin(){tees.clear();quads.clear();triangles.clear();active=true;lastVertices=lastBatches=0;}
    public static VertexConsumer wrap(RenderType type,VertexConsumer vanilla) {
        if(!active || type.isOutline()) return vanilla;
        String shader=type.pipeline().getFragmentShader().getPath();
        // Raster blob shadows and enchantment glint are separate compositing effects, not physical geometry.
        if(shader.contains("shadow")||shader.contains("glint")) return vanilla;
        PrimitiveTopology topology=type.primitiveTopology();
        if(topology!=PrimitiveTopology.QUADS && topology!=PrimitiveTopology.TRIANGLES) {
            if(unsupported.add(type.toString())) org.slf4j.LoggerFactory.getLogger("Remix26Port").warn("Feature topology pending: {}",type);
            return vanilla;
        }
        Map<String,?> textures=((RenderSetupAccess)((RenderTypeAccess)type).remix$setup()).remix$textures();
        Object binding=textures.get("Sampler0");
        if(binding==null && !textures.isEmpty()) binding=textures.values().iterator().next();
        if(binding==null) return vanilla;
        Identifier texture=((TextureBindingAccess)binding).remix$location();
        var tee=new Tee(vanilla,type,texture,topology==PrimitiveTopology.QUADS);tees.add(tee);return tee;
    }
    public static List<MeshBatch> finish() {
        active=false;for(Tee tee:tees) tee.finish();tees.clear();
        List<MeshBatch> result=new ArrayList<>();
        for(var e:quads.entrySet()) result.add(new MeshBatch(e.getKey().material(),e.getValue().copy(),true,e.getKey().categories()));
        for(var e:triangles.entrySet()) result.add(new MeshBatch(e.getKey().material(),e.getValue().copy(),false,e.getKey().categories()));
        lastBatches=result.size();for(var b:result) lastVertices+=b.vertices().length/64;
        return result;
    }
    public static void clear(){active=false;tees.clear();quads.clear();triangles.clear();}
    private static final class Tee implements VertexConsumer {
        private final VertexConsumer target;
        private final Identifier texture;
        private final boolean quad;
        private final PortTextures.Kind kind;
        private final int categories;
        private final ByteBuffer primitive=ByteBuffer.allocate(4*64).order(ByteOrder.nativeOrder());
        private boolean vertex;
        private int count;
        private float x,y,z,u,v,nx,ny,nz;
        private int color=-1;
        Tee(VertexConsumer target,RenderType type,Identifier texture,boolean quad) {
            this.target=target;this.texture=texture;this.quad=quad;
            categories=PortEntities.categories();
            boolean cutout=type.pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT");
            boolean text=type.pipeline().getFragmentShader().getPath().equals("core/text");
            kind=text?PortTextures.Kind.TEXT:type.hasBlending()?PortTextures.Kind.BLEND:cutout?PortTextures.Kind.CUTOUT:PortTextures.Kind.SOLID;
        }
        private void commit() {
            if(!vertex) return;vertex=false;
            if(PortEntities.suppressed())return;
            if(PortViewModel.capturing()){
                var position=new org.joml.Vector3f(x,y,z);PortViewModel.transform(position);x=position.x;y=position.y;z=position.z;
                var normal=new org.joml.Vector3f(nx,ny,nz);PortViewModel.transformNormal(normal);nx=normal.x;ny=normal.y;nz=normal.z;
            }
            primitive.putFloat(x).putFloat(y).putFloat(z).putFloat(nx).putFloat(ny).putFloat(nz).putFloat(u).putFloat(v).putInt(color);
            for(int i=0;i<7;i++) primitive.putInt(0);
            if(++count!=(quad?4:3)) return;
            // Font/custom features often omit NORMAL; derive it once from the primitive.
            if(primitive.getFloat(12)==0 && primitive.getFloat(16)==0 && primitive.getFloat(20)==0) {
                float ax=primitive.getFloat(64)-primitive.getFloat(0),ay=primitive.getFloat(68)-primitive.getFloat(4),az=primitive.getFloat(72)-primitive.getFloat(8);
                float bx=primitive.getFloat(128)-primitive.getFloat(0),by=primitive.getFloat(132)-primitive.getFloat(4),bz=primitive.getFloat(136)-primitive.getFloat(8);
                float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
                float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                if(len>0) for(int i=0;i<count;i++) primitive.putFloat(i*64+12,nx/len).putFloat(i*64+16,ny/len).putFloat(i*64+20,nz/len);
            }
            float cu=0,cv=0;for(int i=0;i<count;i++){cu+=primitive.getFloat(i*64+24);cv+=primitive.getFloat(i*64+28);}
            var region=PortTextures.region(texture,cu/count,cv/count);
            var pixels=region==null?PortTextures.standalone(texture):region.pixels();
            var key=new BatchKey(PortTextures.key(pixels,kind),categories);
            if(region!=null) for(int i=0;i<count;i++) {
                primitive.putFloat(i*64+24,(primitive.getFloat(i*64+24)-region.u0())/(region.u1()-region.u0()));
                primitive.putFloat(i*64+28,(primitive.getFloat(i*64+28)-region.v0())/(region.v1()-region.v0()));
            }
            if(key.material().kind()==PortTextures.Kind.WATER&&!PortViewModel.capturing()){
                var camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
                int ua=com.nvidia.rtxremix.port.texture.PortWater.uAxis(primitive.getFloat(12),primitive.getFloat(16),primitive.getFloat(20));
                int va=com.nvidia.rtxremix.port.texture.PortWater.vAxis(primitive.getFloat(12),primitive.getFloat(16),primitive.getFloat(20));
                double ub=ua==0?camera.x:ua==1?camera.y:camera.z;
                double vb=va==0?camera.x:va==1?camera.y:camera.z;
                for(int i=0;i<count;i++){
                    primitive.putFloat(i*64+24,com.nvidia.rtxremix.port.texture.PortWater.coordinate(ub,primitive.getFloat(i*64+ua*4)));
                    primitive.putFloat(i*64+28,com.nvidia.rtxremix.port.texture.PortWater.coordinate(vb,primitive.getFloat(i*64+va*4)));
                }
            }
            var groups=quad?quads:triangles;
            var out=groups.computeIfAbsent(key,k->new VertexBytes()).append(count*64);
            out.put(primitive.array(),0,count*64);primitive.clear();count=0;
        }
        void finish(){commit();if(count!=0) throw new IllegalStateException("Incomplete feature primitive: "+texture+" vertices="+count);}
        public VertexConsumer addVertex(float x,float y,float z){commit();this.x=x;this.y=y;this.z=z;u=v=nx=ny=nz=0;color=-1;vertex=true;target.addVertex(x,y,z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){color=a<<24|r<<16|g<<8|b;target.setColor(r,g,b,a);return this;}
        public VertexConsumer setColor(int argb){color=argb;target.setColor(argb);return this;}
        public VertexConsumer setUv(float u,float v){this.u=u;this.v=v;target.setUv(u,v);return this;}
        public VertexConsumer setUv1(int u,int v){target.setUv1(u,v);return this;}
        public VertexConsumer setUv2(int u,int v){target.setUv2(u,v);return this;}
        public VertexConsumer setNormal(float x,float y,float z){nx=x;ny=y;nz=z;target.setNormal(x,y,z);return this;}
        public VertexConsumer setLineWidth(float width){target.setLineWidth(width);return this;}
    }
}
