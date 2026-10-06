package com.voxellight.adapter;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;
class RtDynamicGeometryTest {
 @Test void nativeQuadRetainsWorldPoseTextureSlotTintAndNormals(){
  int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();assertEquals(28,stride);
  var input=ByteBuffer.allocate(stride*4).order(ByteOrder.nativeOrder());
  for(int v=0;v<4;v++){int o=v*stride;input.putFloat(o,v).putFloat(o+4,2).putFloat(o+8,3).putInt(o+12,0xff804020).putFloat(o+16,.25f).putFloat(o+20,.5f);}
  var result=ByteBuffer.wrap(RtDynamicScene.triangles(input.array(),17,100,50,-20)).order(ByteOrder.nativeOrder());assertEquals(240,result.capacity());
  int[] indices={0,1,2,2,3,0};for(int i=0;i<6;i++){int o=i*40;assertEquals(100+indices[i],result.getFloat(o));assertEquals(52,result.getFloat(o+4));assertEquals(-17,result.getFloat(o+8));assertEquals(.25f,result.getFloat(o+12));assertEquals(.5f,result.getFloat(o+16));assertEquals(0,result.getFloat(o+24));assertEquals(0xff804020,result.getInt(o+32));assertEquals(17,result.getInt(o+36)>>>20);assertEquals(129,result.getInt(o+36)&255);}
 }
}
