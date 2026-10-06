package com.voxellight.rt;

import java.nio.*;
import java.util.function.IntPredicate;

/** Stable within-category triangle order; classification must conservatively include authored transmission. */
public record RtGeometryRanges(byte[] triangles,int[] counts){
    public static final int OPAQUE=0,CUTOUT=1,TRANSMISSION=2;
    public static RtGeometryRanges split(byte[] source,IntPredicate transmissive){
        if(source.length%120!=0)throw new IllegalArgumentException("Triangle stride");
        var input=ByteBuffer.wrap(source).order(ByteOrder.nativeOrder());int n=source.length/120;
        int[] category=new int[n],counts=new int[3];boolean[] transmission=new boolean[n];
        for(int i=0;i<n;i++){int flags=input.getInt(i*120+36);boolean trans=transmission[i]=transmissive.test(i*120);category[i]=(flags&1)!=0?CUTOUT:trans?TRANSMISSION:OPAQUE;counts[category[i]]++;}
        int[] cursor={0,counts[0]*120,(counts[0]+counts[1])*120};byte[] sorted=new byte[source.length];
        var output=ByteBuffer.wrap(sorted).order(ByteOrder.nativeOrder());
        for(int i=0;i<n;i++){int offset=cursor[category[i]];System.arraycopy(source,i*120,sorted,offset,120);cursor[category[i]]+=120;
            if(transmission[i])for(int v=0;v<3;v++)output.putInt(offset+v*40+36,output.getInt(offset+v*40+36)|4);
        }
        return new RtGeometryRanges(sorted,counts);
    }
    /** Exact non-indexed AS vertex input, without shader-only attributes. */
    public byte[] positions(){
        byte[] result=new byte[triangles.length/40*12];
        for(int vertex=0;vertex<triangles.length/40;vertex++)System.arraycopy(triangles,vertex*40,result,vertex*12,12);
        return result;
    }
    public int base(int category){return category==0?0:category==1?counts[0]:counts[0]+counts[1];}
}
