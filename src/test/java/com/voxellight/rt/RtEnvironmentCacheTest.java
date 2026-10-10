package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtEnvironmentCacheTest {
 @Test void everyActiveInputAndDestinationChangeInvalidates(){
  var c=new RtEnvironmentCache();var target=new Object();float[] w=new float[16],p=new float[16];w[5]=1;
  assertFalse(c.matches(target,256,w,p));c.updated(target,256,w,p);assertTrue(c.matches(target,256,w,p));
  for(int i=0;i<16;i++){var changed=w.clone();changed[i]+=1;assertFalse(c.matches(target,256,changed,p),"weather "+i);changed=p.clone();changed[i]+=1;assertFalse(c.matches(target,256,w,changed),"palette "+i);}
  assertFalse(c.matches(new Object(),256,w,p));assertFalse(c.matches(target,512,w,p));c.clear();assertFalse(c.matches(target,256,w,p));
 }
 @Test void cloudOffCanIgnoreOriginAndWindButEnablingCloudsInvalidates(){
  var c=new RtEnvironmentCache();var target=new Object();float[] w=new float[16],p=new float[16];c.updated(target,0,w,p);
  for(int i=0;i<4;i++)w[i]=100+i;assertTrue(c.matches(target,0,w,p));w[5]=1;assertFalse(c.matches(target,0,w,p));
 }
 @Test void malformedOrNonfiniteInputsNeverReuseAndSnapshotsAreOwned(){
  var c=new RtEnvironmentCache();var target=new Object();float[] w=new float[16],p=new float[16];c.updated(target,0,w,p);
  w[7]=1;assertFalse(c.matches(target,0,w,p));w[7]=0;p[0]=Float.NaN;assertFalse(c.matches(target,0,w,p));
  c.updated(target,0,w,p);p[0]=0;assertFalse(c.matches(target,0,w,p));assertFalse(c.matches(target,0,new float[15],p));
 }
}
