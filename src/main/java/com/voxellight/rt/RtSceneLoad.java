package com.voxellight.rt;
import java.nio.*;
/** Deterministic bounded RT-only deformation workload, preserving material and topology. */
public final class RtSceneLoad {
 private RtSceneLoad(){}
 public static byte[] geometry(byte[] source,long tick,int section){
  if(source.length<120)throw new IllegalArgumentException("triangle required");
  byte[] bytes=new byte[128*120];var b=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
  for(int triangle=0;triangle<128;triangle++){
   System.arraycopy(source,0,bytes,triangle*120,120);
   for(int vertex=0;vertex<3;vertex++){
    int p=triangle*120+vertex*40;
    b.putFloat(p,b.getFloat(p)+(triangle%16)*2);
    b.putFloat(p+4,b.getFloat(p+4)+(triangle/16)*2+(float)Math.sin((tick%120)*Math.PI/60+section*.1+vertex*.2)*.125f);
   }
  }return bytes;
 }
}
