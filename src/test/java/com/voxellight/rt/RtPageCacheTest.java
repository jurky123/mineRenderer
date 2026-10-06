package com.voxellight.rt;
import com.voxellight.world.SectionKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtPageCacheTest {
 @Test void pagesRoundTripAndEditsCannotRestoreOldGeometry(){var cache=new RtPageCache(1024);var key=new SectionKey(1,2,3);byte[] data=new byte[32000];new java.util.Random(4).nextBytes(data);cache.put(key,7,12,data);assertNull(cache.get(key,7),"Oversize page is not admitted");data=new byte[32000];cache.put(key,7,12,data);assertArrayEquals(data,cache.get(key,7).triangles());assertEquals(12,cache.get(key,7).version());assertNull(cache.get(key,8),"Edited page must miss rather than restore stale triangles");}
 @Test void compressedBackingHasBoundedLruAndWorldReset(){var cache=new RtPageCache(500);byte[] data=new byte[200];new java.util.Random(1).nextBytes(data);var a=new SectionKey(0,0,0);var b=new SectionKey(1,0,0);var c=new SectionKey(2,0,0);cache.put(a,0,1,data);cache.put(b,0,2,data);assertNotNull(cache.get(a,0));cache.put(c,0,3,data);assertNull(cache.get(b,0));assertNotNull(cache.get(a,0));assertNotNull(cache.get(c,0));cache.clear();assertNull(cache.get(a,0));}
 @Test void benchmarkSnapshotKeepsExactResidentBytesAcrossRendererReset(){
  var cache=new RtPageCache(4096);var key=new SectionKey(1,2,3);byte[] data=new byte[120];data[10]=42;
  cache.put(key,7,12,data);assertNull(cache.snapshot(key,13));
  var snapshot=cache.snapshot(key,12);assertArrayEquals(data,snapshot.triangles());
  cache.clear();assertNull(cache.snapshot(key,12));assertArrayEquals(data,snapshot.triangles());
 }
 @Test void benchmarkVersionSelectionDoesNotWeakenLiveRevisionValidation(){
  var cache=new RtPageCache(4096);var key=new SectionKey(1,2,3);cache.put(key,7,12,new byte[120]);
  assertNotNull(cache.snapshot(key,12));assertNull(cache.get(key,8));assertNull(cache.snapshot(key,12));
 }
}
