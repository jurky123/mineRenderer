package com.voxellight.world;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class PathTraceSceneTest {
 @Test void airFluidAndFoliageAreNotSolidSecondaryCubes(){
  assertEquals(0,PathTraceScene.packed(new SectionSnapshot.Material(0,0,0),0xffffff));
  assertEquals(0,PathTraceScene.packed(new SectionSnapshot.Material(1,SectionSnapshot.NON_AIR|SectionSnapshot.FLUID,0),0xffffff));
  assertEquals(0,PathTraceScene.packed(new SectionSnapshot.Material(2,SectionSnapshot.NON_AIR,0),0xffffff));
  assertEquals(0x01030201,PathTraceScene.packed(new SectionSnapshot.Material(3,3,0),0x010203));
  assertEquals(0x10030201,PathTraceScene.packed(new SectionSnapshot.Material(3,2,15),0x010203));
 }
 @Test void missingSectionsStayUnknownAndNegativeSectionCoordinatesMapCorrectly(){
  var center=new SectionKey(-3,-2,1);var request=new WorldSceneBridge.Request(new SectionKey(-5,-4,-1),1,1,1,1);
  short[] index=new short[4096];index[SectionKey.blockIndex(15,15,15)]=1;
  var section=new SectionSnapshot(request,index,new int[]{0,1},new byte[]{0,3},new byte[]{0,15});
  var scene=PathTraceScene.encode(List.of(section),center,id->0x112233);
  assertTrue(scene.isDirect());assertEquals(PathTraceScene.BYTES,scene.remaining());
  assertEquals(0,scene.getInt(0));assertEquals(0x10332211,scene.getInt(PathTraceScene.index(15,15,15)*4));
  assertEquals(PathTraceScene.UNKNOWN,scene.getInt(PathTraceScene.index(16,0,0)*4));
  assertEquals(PathTraceScene.UNKNOWN,scene.getInt(PathTraceScene.BYTES-4));
 }
}
