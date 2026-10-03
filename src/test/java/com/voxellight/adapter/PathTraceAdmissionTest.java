package com.voxellight.adapter;
import com.voxellight.world.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PathTraceAdmissionTest {
 private static PathTracePass.Key key(long scene,double x,Matrix4f matrix,int sun){return new PathTracePass.Key(1,scene,x,2,3,matrix,640,338,sun,0,ShadowLight.Source.SUN);}
 @Test void tinyCameraNoiseDoesNotResetButMovementAndEditsDo(){
  var a=key(2,1,new Matrix4f(),1);
  assertTrue(a.matches(key(2,1.0001,new Matrix4f().m00(1.000001f),1)));
  assertFalse(a.matches(key(2,1.01,new Matrix4f(),1)));
  assertFalse(a.matches(key(2,1,new Matrix4f().rotateY(.01f),1)));
  assertFalse(a.matches(key(3,1,new Matrix4f(),1)));
 }
 @Test void movementCanReprojectWithoutAccumulatingUnalignedPixels(){
  var a=key(2,1,new Matrix4f(),1);var b=key(2,2,new Matrix4f().rotateY(.1f),1);
  assertFalse(a.matches(b));assertTrue(a.canReproject(b));
  assertFalse(a.canReproject(key(3,2,new Matrix4f(),1)));
  assertFalse(a.canReproject(key(2,20,new Matrix4f(),1)));
 }
 @Test void celestialRefreshReseedsAccumulationWithoutRemovingValidSurfaceLighting(){
  var a=key(2,1,new Matrix4f(),1);var b=key(2,1,new Matrix4f(),2);
  assertFalse(a.matches(b));assertTrue(a.surfaceMatches(b));
 }
 @Test void materialFingerprintIgnoresTaskVersionsAndPaletteOrder(){
  short[] first=new short[4096],second=new short[4096];java.util.Arrays.fill(second,(short)1);
  var r=new WorldSceneBridge.Request(new SectionKey(1,2,3),1,1,1,WorldSceneBridge.LOAD);
  var a=new SectionSnapshot(r,first,new int[]{5,0},new byte[]{3,0},new byte[]{2,0});
  var b=new SectionSnapshot(new WorldSceneBridge.Request(r.key(),1,1,999,WorldSceneBridge.LIGHT),second,new int[]{0,5},new byte[]{0,3},new byte[]{0,2});
  assertEquals(a.materialFingerprint(),b.materialFingerprint());
  first[0]=1;var edited=new SectionSnapshot(r,first,new int[]{5,0},new byte[]{3,0},new byte[]{2,0});assertNotEquals(a.materialFingerprint(),edited.materialFingerprint());
 }
}
