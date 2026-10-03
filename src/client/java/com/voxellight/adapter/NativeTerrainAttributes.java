package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.PrimitiveTopology;
import com.voxellight.world.MaterialEncoding;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Vector3f;

/** Eight inline bytes, compiled with native terrain. Native position/color/UV/light offsets stay unchanged. */
public final class NativeTerrainAttributes {
    public static final VertexFormat FORMAT=VertexFormat.builder(0)
            .addAttribute("Position",GpuFormat.RGB32_FLOAT).addAttribute("Color",GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0",GpuFormat.RG32_FLOAT).addAttribute("UV2",GpuFormat.RG16_SINT)
            .addAttribute("UV1",GpuFormat.RG16_SINT).addAttribute("Normal",GpuFormat.RGBA8_SNORM).build();
    public record Attributes(int tintMetadata,Vector3f normal,int blockEmission) { }
    private static final ThreadLocal<Attributes> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<java.util.Iterator<Attributes>> VERTICES=new ThreadLocal<>();
    public static Attributes nextVertex(){var vertices=VERTICES.get();return vertices==null?current():vertices.hasNext()?vertices.next():null;}
    public static void withVertices(Attributes[] values,Runnable output){
        var old=VERTICES.get();VERTICES.set(java.util.Arrays.asList(values).iterator());
        try{output.run();}finally{if(old==null)VERTICES.remove();else VERTICES.set(old);}
    }
    private NativeTerrainAttributes() { }
    /** Verify the transformed writer once before graphics startup; catches layout/fast-path mismatch. */
    public static void verifyWriter(){
        if(DefaultVertexFormat.BLOCK!=FORMAT)throw new IllegalStateException("Native material vertex format mixin did not apply");
        try(var memory=new ByteBufferBuilder(256,256)){
            var builder=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK);
            var attributes=new Attributes(0xa580ff40,new Vector3f(-.70710677f,.70710677f,0),14);
            with(attributes,()->{
                for(int i=0;i<4;i++)builder.addVertex(i,2,3,0xff123456,.25f,.5f,0,0x00f000a0,0,1,0);
            });
            try(var mesh=builder.build()){
                var data=mesh.vertexBuffer();
                if(mesh.drawState().format()!=FORMAT || data.getShort(28)!=(short)0xff40 || data.getShort(30)!=(short)0xa580
                        || Byte.toUnsignedInt(data.get(12))!=0x12 || Byte.toUnsignedInt(data.get(13))!=0x34
                        || Byte.toUnsignedInt(data.get(14))!=0x56 || Byte.toUnsignedInt(data.get(15))!=255
                        || data.getShort(24)!=160 || data.getShort(26)!=240 || data.get(32)>=0 || data.get(33)<=0 || data.get(35)!=30 || data.getFloat(4)!=2)
                    throw new IllegalStateException("Native material terrain writer/layout mismatch");
            }
            // Raw native emitters must be valid native geometry without claiming material metadata.
            var raw=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK);
            for(int i=0;i<4;i++){var vertex=raw.addVertex(i,0,0).setColor(-1).setUv(0,0).setUv2(0,0);if(i%2==0)vertex.setNormal(0,1,0);}
            try(var mesh=raw.build()){
                for(int i=0;i<4;i++)if(mesh.vertexBuffer().get(i*36+35)!=0)throw new IllegalStateException("Raw emitter inherited material metadata");
            }
        }
        // Force version-specific compiler transformation while startup can report a clear failure.
        try{Class.forName("net.minecraft.client.renderer.chunk.SectionCompiler");}
        catch(ClassNotFoundException e){throw new IllegalStateException(e);}
    }
    public static Attributes current(){return CURRENT.get();}
    public static void with(Attributes value,Runnable output){
        var old=CURRENT.get();var vertices=VERTICES.get();VERTICES.remove();CURRENT.set(value);
        try{output.run();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);if(vertices!=null)VERTICES.set(vertices);}
    }
    public static Attributes attributes(BakedQuad quad,int tint,int emission,boolean forceSolid){
        var info=quad.materialInfo();
        int flags=(!forceSolid && info.layer()==ChunkSectionLayer.CUTOUT?MaterialEncoding.CUTOUT:0)
                |(info.isTinted()?MaterialEncoding.TINTED:0)|(!info.shade()?MaterialEncoding.UNSHADED:0)
                |(info.sprite()!=null && info.sprite().contents().isAnimated()?MaterialEncoding.ANIMATED:0);
        int metadata=MaterialEncoding.packQuadEmissionFlags(Math.clamp(info.lightEmission(),0,15),flags);
        return new Attributes((tint & 0xffffff)|(metadata<<24),MaterialQuads.normal(quad),Math.clamp(emission,0,15));
    }
}
