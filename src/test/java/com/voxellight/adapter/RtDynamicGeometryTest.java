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
 @Test void itemSpriteCropPreservesItsFullTextureFootprint(){
  int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();
  var input=ByteBuffer.allocate(stride*4).order(ByteOrder.nativeOrder());
  float[] u={.5f,.50390625f,.50390625f,.5f},v={.25f,.25f,.2578125f,.2578125f};
  for(int i=0;i<4;i++)input.putFloat(i*stride+16,u[i]).putFloat(i*stride+20,v[i]);
  var region=RtDynamicScene.region(input.array(),null);
  assertEquals(16f/4096,region.width());assertEquals(16f/2048,region.height());
  var output=ByteBuffer.wrap(RtDynamicScene.triangles(input.array(),0,0,0,0,region)).order(ByteOrder.nativeOrder());
  assertEquals(0,output.getFloat(12));assertEquals(1,output.getFloat(52));assertEquals(1,output.getFloat(96));
 }
 @Test void sprintWorldFovDoesNotStretchTheHudHandFootprint(){
  var world=new org.joml.Matrix4f().perspective((float)Math.toRadians(110),16f/9,.05f,1000);
  var hud=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,100);
  var scale=RtDynamicScene.handProjectionScale(world,70,16f/9);
  var actual=world.transform(new org.joml.Vector4f(.2f*scale.x,-.3f*scale.y,-1,1));
  var expected=hud.transform(new org.joml.Vector4f(.2f,-.3f,-1,1));
  assertEquals(expected.x/expected.w,actual.x/actual.w,1e-6);assertEquals(expected.y/expected.w,actual.y/actual.w,1e-6);
 }
 @Test void changingHeldItemsReusesReleasedSlotsWithoutOverwritingLiveTextures(){
  assertEquals(1,RtDynamicScene.freeSlot(java.util.List.of(0,2,3)));
 }
}
