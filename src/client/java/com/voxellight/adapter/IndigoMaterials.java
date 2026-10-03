package com.voxellight.adapter;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.ARGB;
import com.voxellight.world.MaterialEncoding;
import org.joml.Vector3f;

public final class IndigoMaterials {
    private static final java.util.concurrent.atomic.LongAdder EMISSIONS=new java.util.concurrent.atomic.LongAdder();
    public static void recordEmission(){EMISSIONS.increment();}
    public static long emissions(){return EMISSIONS.sum();}
    public static class Quad extends net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl {
        public Quad(){data=new int[net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat.TOTAL_STRIDE];clear();}
        @Override protected void emitDirectly(){ }
    }
    public static void verifyWriter(){
        var quad=new Quad();quad.pos(0,0,0,0).pos(1,1,1,0).pos(2,1,1,1).pos(3,0,0,1);
        quad.nominalFace(net.minecraft.core.Direction.UP);quad.chunkLayer(ChunkSectionLayer.SOLID);quad.diffuseShade(true);quad.tintIndex(-1);
        for(int i=0;i<4;i++)quad.color(i,0xff80ff40+i).lightmap(i,0);
        var captured=capture(quad,-1,7);
        var carrier=(IndigoMaterialCarrier)quad;carrier.voxellight$setMaterial(captured);
        for(int i=0;i<4;i++)quad.color(i,0xff010101); // represent Indigo's subsequent lighting mutation
        try(var memory=new com.mojang.blaze3d.vertex.ByteBufferBuilder(512,512)){
            var builder=new com.mojang.blaze3d.vertex.BufferBuilder(memory,com.mojang.blaze3d.PrimitiveTopology.QUADS,com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK);
            quad.buffer(0,builder);
            try(var mesh=builder.build()){
                var data=mesh.vertexBuffer();
                for(int i=0;i<4;i++)if(data.get(i*36+35)!=23 || data.getShort(i*36+28)!=(short)(0xff40+i)
                        || Byte.toUnsignedInt(data.get(i*36+12))!=1 || data.get(i*36+32)>=0)
                    throw new IllegalStateException("Indigo terrain metadata did not survive emission");
            }
            if(carrier.voxellight$getMaterial()!=null)throw new IllegalStateException("Indigo metadata not consumed");
            carrier.voxellight$setMaterial(captured);quad.clear();
            if(carrier.voxellight$getMaterial()!=null)throw new IllegalStateException("Indigo reusable quad retained metadata");
        }
        try{Class.forName("net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl");}
        catch(ClassNotFoundException e){throw new IllegalStateException(e);}
    }
    private IndigoMaterials() { }
    /** Read before Indigo mutates colors/lightmaps for AO and face shading. */
    public static NativeTerrainAttributes.Attributes[] capture(QuadView quad,int tint,int emission){
        int flags=(quad.chunkLayer()==ChunkSectionLayer.CUTOUT?MaterialEncoding.CUTOUT:0)
                |(quad.tintIndex()>=0?MaterialEncoding.TINTED:0)|(!quad.diffuseShade()?MaterialEncoding.UNSHADED:0)
                |(quad.animated()?MaterialEncoding.ANIMATED:0);
        int quadEmission=quad.emissive()?15:0;
        for(int i=0;i<4;i++)quadEmission=Math.max(quadEmission,Math.clamp((quad.lightmap(i)&65535)/16,0,15));
        int metadata=MaterialEncoding.packQuadEmissionFlags(quadEmission,flags);
        var normal=new Vector3f(quad.faceNormal());
        if(quad.nominalFace()!=null && normal.dot(quad.nominalFace().getUnitVec3f())<0)normal.negate();
        var result=new NativeTerrainAttributes.Attributes[4];
        for(int i=0;i<4;i++)result[i]=new NativeTerrainAttributes.Attributes((ARGB.multiply(quad.color(i),tint)&0xffffff)|(metadata<<24),normal,Math.clamp(emission,0,15));
        return result;
    }
}
