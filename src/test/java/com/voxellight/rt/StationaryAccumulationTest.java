package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StationaryAccumulationTest {
    @Test void convergesAndIncreasingTargetRetainsSamples(){
        var h=new StationaryAccumulation();double[] pose={1,2,3};h.target(4);
        for(int i=0;i<4;i++){h.begin(pose,1,10,8);assertTrue(h.needsSample());h.accepted();}
        assertFalse(h.needsSample());h.target(8);assertEquals(4,h.samples());assertTrue(h.needsSample());
        h.target(4);assertFalse(h.needsSample());
    }
    @Test void cameraSceneResizeAndManualResetRejectPreviousMean(){
        var h=new StationaryAccumulation();double[] pose={1,2,3};h.begin(pose,1,10,8);h.accepted();
        h.begin(pose.clone(),1,10,8);assertEquals(1,h.samples());pose[0]+=.001;h.begin(pose,1,10,8);assertEquals(0,h.samples());
        h.accepted();h.begin(pose,2,10,8);assertEquals(0,h.samples());assertEquals("scene",h.reason());
        h.accepted();h.begin(pose,2,11,8);assertEquals(0,h.samples());assertEquals("resize",h.reason());
        h.accepted();h.reset("manual");h.begin(pose,2,11,8);assertEquals(0,h.samples());
    }
    @Test void disabledModeDoesNotFreezeOrReuseHistory(){
        var h=new StationaryAccumulation();h.begin(new double[]{0},1,1,1);h.accepted();h.enabled(false);
        h.accepted();assertEquals(0,h.samples());assertTrue(h.needsSample());h.enabled(true);assertEquals(0,h.samples());
        assertThrows(IllegalArgumentException.class,()->h.target(0));assertThrows(IllegalArgumentException.class,()->h.target(4097));
    }
}
