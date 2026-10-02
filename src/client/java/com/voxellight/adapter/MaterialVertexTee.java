package com.voxellight.adapter;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Consumer;

/** Native writes always run first; a failed private capture cannot truncate native geometry. */
final class MaterialVertexTee implements VertexConsumer {
    private final VertexConsumer nativeConsumer,material;
    private boolean failed;
    MaterialVertexTee(VertexConsumer nativeConsumer,VertexConsumer material){this.nativeConsumer=nativeConsumer;this.material=material;}
    private void copy(Consumer<VertexConsumer> write){if(!failed)try{write.accept(material);}catch(RuntimeException failure){failed=true;}}
    boolean failed(){return failed;}
    @Override public VertexConsumer addVertex(float x,float y,float z){nativeConsumer.addVertex(x,y,z);copy(c->c.addVertex(x,y,z));return this;}
    @Override public VertexConsumer setColor(int r,int g,int b,int a){nativeConsumer.setColor(r,g,b,a);copy(c->c.setColor(r,g,b,a));return this;}
    @Override public VertexConsumer setColor(int color){nativeConsumer.setColor(color);copy(c->c.setColor(color));return this;}
    @Override public VertexConsumer setUv(float u,float v){nativeConsumer.setUv(u,v);copy(c->c.setUv(u,v));return this;}
    @Override public VertexConsumer setUv1(int u,int v){nativeConsumer.setUv1(u,v);copy(c->c.setUv1(u,v));return this;}
    @Override public VertexConsumer setUv2(int u,int v){nativeConsumer.setUv2(u,v);copy(c->c.setUv2(u,v));return this;}
    @Override public VertexConsumer setNormal(float x,float y,float z){nativeConsumer.setNormal(x,y,z);copy(c->c.setNormal(x,y,z));return this;}
    @Override public VertexConsumer setLineWidth(float width){nativeConsumer.setLineWidth(width);copy(c->c.setLineWidth(width));return this;}
    @Override public void addVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz){
        nativeConsumer.addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz);
        copy(c->c.addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz));
    }
}
