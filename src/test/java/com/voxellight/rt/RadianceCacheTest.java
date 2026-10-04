package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RadianceCacheTest {
 @Test void ringSlotsPreserveOverlappingWorldCoordinatesAcrossRecentering(){for(int c=0;c<3;c++){int spacing=RadianceCacheLayout.spacing(c);for(int x=-16;x<16;x++)assertEquals(RadianceCacheLayout.slot(c,x,-3,5),RadianceCacheLayout.slot(c,x+8,-3,5));assertEquals(-1,RadianceCacheLayout.coordinate(-.01,c));assertEquals(2,RadianceCacheLayout.coordinate(spacing*2+.1,c));}}
 @Test void blockEditHasLocalInfluenceWithoutResettingDistantCache(){assertTrue(RadianceCacheLayout.affected(8,8,8,0,0,0,0));assertFalse(RadianceCacheLayout.affected(96,0,0,0,0,0,0));assertTrue(RadianceCacheLayout.affected(40,0,0,2,0,0,0));}
 @Test void invalidationsAreCoalescedBoundedAndDisabledForRaster(){RtInvalidationQueue.enabled(false);RtInvalidationQueue.record(0,0,0,1,1,1);assertTrue(RtInvalidationQueue.drain().isEmpty());RtInvalidationQueue.enabled(true);for(int i=0;i<500;i++)RtInvalidationQueue.record(i,0,0,i+1,1,1);var regions=RtInvalidationQueue.drain();assertTrue(regions.size()<=256);assertEquals(0,regions.getFirst().minX());assertTrue(regions.stream().anyMatch(r->r.maxX()==500));assertTrue(RtInvalidationQueue.drain().isEmpty());RtInvalidationQueue.enabled(false);}
 @Test void lateSnapshotsAreRejectedLocallyEvenAfterInvalidationsAreDrained(){
  RtInvalidationQueue.enabled(true);long nearby=RtInvalidationQueue.revision(1,2,3),distant=RtInvalidationQueue.revision(20,2,3);
  RtInvalidationQueue.record(1,2,3,2,3,4);RtInvalidationQueue.drain();
  assertNotEquals(nearby,RtInvalidationQueue.revision(1,2,3));assertEquals(distant,RtInvalidationQueue.revision(20,2,3));
  RtInvalidationQueue.enabled(false);
 }
 @Test void dielectricEnergyAndColoredAbsorptionArePhysical(){assertEquals(.04,RtMaterial.dielectricF0(1.5f),1e-6);assertEquals(.02037,RtMaterial.dielectricF0(1.333f),1e-4);assertEquals(1,RtMaterial.transmittance(0,2));assertEquals(RtMaterial.transmittance(.5f,2)*RtMaterial.transmittance(.5f,3),RtMaterial.transmittance(.5f,5),1e-6);assertTrue(Float.isNaN(RtMaterial.snellCosine(.2f,1.5f,1)));assertEquals(1,RtMaterial.snellCosine(1,1,1.5f));}
}
