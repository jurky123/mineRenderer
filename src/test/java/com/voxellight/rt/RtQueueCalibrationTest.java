package com.voxellight.rt;
import com.voxellight.debug.PassMetrics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtQueueCalibrationTest {
    @Test void hybridCompactsOnlyUsefulMiddleBouncesAndRejectsStaleDimensions(){
        long[] curve={25680,23200,13200,534,40,10};assertEquals(4,RtQueueCalibration.hybridMask(curve));
        assertEquals(0,RtQueueCalibration.hybridMask(new long[]{1000,500,300,200,100,0}));
        assertEquals(0,RtQueueCalibration.hybridMask(new long[]{10000,5000,6000,0,0,0}));
        var c=new RtQueueCalibration();c.alive(8,214,120,1,7,curve);curve[2]=0;
        assertEquals(4,c.hybridMask(214,120,1));assertEquals(0,c.hybridMask(428,240,1));assertEquals(0,c.hybridMask(214,120,2));
    }
    private PassMetrics.Sample sample(long frame,boolean compact,long ns,long scene){return new PassMetrics.Sample(frame,"vulkan_rt_batch_"+(compact?"compact":"fixed"),100,100,999,ns,frame,0,1,scene);}
    @Test void joinsDelayedResultsInEitherOrderAndLearnsRealCostWithinAliveBucket(){
        var c=new RtQueueCalibration();long[] alive={10000,7000,4000,2000,1000,500};
        for(int i=0;i<12;i++){boolean compact=i%2==1;if(compact){c.alive(i,100,100,1,4,alive);c.timing(sample(i,true,100,4));}else{c.timing(sample(i,false,200,4));c.alive(i,100,100,1,4,alive);}}
        assertTrue(c.compact(123,100,100,1,false));assertFalse(c.compact(123,200,100,1,false));
        assertTrue(c.status().contains("matchedGpuSamples=12"));
    }
    @Test void rejectsSceneMismatchesAndNeverInventsTimingsWhenProfilingOff(){
        var c=new RtQueueCalibration();assertFalse(c.compact(8,100,100,1,false));assertTrue(c.compact(8,100,100,1,true));
        for(int i=0;i<20;i++){c.alive(i,100,100,1,4,new long[]{100,50,25,10,5,0});c.timing(sample(i,i%2==1,1,5));}
        assertFalse(c.compact(8,100,100,1,false));assertTrue(c.status().contains("matchedGpuSamples=0"));
        c.alive(22,100,100,1,4,new long[]{100,101,0,0,0,0});c.timing(sample(22,true,1,4));assertTrue(c.status().contains("matchedGpuSamples=0"));
    }
}
