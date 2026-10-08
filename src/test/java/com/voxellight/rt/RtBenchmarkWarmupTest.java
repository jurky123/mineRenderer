package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.voxellight.rt.RtBenchmarkWarmup.Status.*;
class RtBenchmarkWarmupTest {
    @Test void gpuWarmupRequiresActualSamplesAndRejectsClockDrift(){
        var samples=new java.util.ArrayList<Long>();assertFalse(RtBenchmarkWarmup.gpuStable(samples));
        for(int i=0;i<30;i++)samples.add(i<15?10_000_000L:10_500_000L);assertTrue(RtBenchmarkWarmup.gpuStable(samples));
        for(int i=15;i<30;i++)samples.set(i,13_000_000L);assertFalse(RtBenchmarkWarmup.gpuStable(samples));
        for(int i=0;i<30;i++)samples.add(13_000_000L);assertTrue(RtBenchmarkWarmup.gpuStable(samples));
        samples.set(samples.size()-1,0L);assertFalse(RtBenchmarkWarmup.gpuStable(samples));
    }
    private static long seconds(long value){return value*1_000_000_000L;}
    @Test void slowSerInitializationDoesNotConsumeRenderedWarmup(){
        var w=new RtBenchmarkWarmup(0);
        assertEquals(INITIALIZING,w.observe(0,false,0));
        assertEquals(WARMUP,w.observe(seconds(52),true,7));
        assertEquals(0,w.readySeconds(seconds(52)));
        assertEquals(WARMUP,w.observe(seconds(55),true,7));
        assertEquals(READY,w.observe(seconds(56),true,7));
    }
    @Test void unavailableRendererTimesOutWithInitializationReason(){var w=new RtBenchmarkWarmup(0);assertEquals(INITIALIZING,w.observe(seconds(179),false,0));assertEquals(INITIALIZATION_TIMEOUT,w.observe(seconds(180),false,0));}
    @Test void continuousTerrainChurnTimesOutOnlyAfterReadiness(){
        var w=new RtBenchmarkWarmup(0);w.observe(seconds(52),true,0);
        for(int i=1;i<30;i++)assertEquals(WARMUP,w.observe(seconds(52+i),true,i));
        assertEquals(STABILITY_TIMEOUT,w.observe(seconds(82),true,30));
    }
    @Test void restoredRendererMustWarmUpAgain(){
        var w=new RtBenchmarkWarmup(0);w.observe(0,true,7);w.observe(seconds(3),false,0);
        assertEquals(WARMUP,w.observe(seconds(10),true,7));assertEquals(WARMUP,w.observe(seconds(13),true,7));assertEquals(READY,w.observe(seconds(14),true,7));
    }
    @Test void unstableTerrainMustSettleBeforeSampling(){var w=new RtBenchmarkWarmup(0);w.observe(0,true,7);assertEquals(WARMUP,w.observe(seconds(4),true,8));assertEquals(READY,w.observe(seconds(5),true,8));}
    @Test void lazyFirstExecutionDoesNotConsumeVarianceBudget(){
        var w=new RtBenchmarkWarmup(0);w.observe(0,true,7);var samples=new java.util.ArrayList<Long>();
        assertEquals(RtBenchmarkWarmup.GpuStatus.WAITING_FIRST_SAMPLE,w.gpuStatus(seconds(37),samples));
        assertEquals(RtBenchmarkWarmup.GpuStatus.FIRST_SAMPLE_TIMEOUT,w.gpuStatus(seconds(180),samples));
        samples.add(10_000_000L);assertEquals(RtBenchmarkWarmup.GpuStatus.COLLECTING,w.gpuStatus(seconds(181),samples));
        assertEquals(RtBenchmarkWarmup.GpuStatus.COLLECTION_TIMEOUT,w.gpuStatus(seconds(361),samples));
    }
    @Test void varianceDeadlineStartsAtFullWindowAndStillRejectsDrift(){
        var w=new RtBenchmarkWarmup(0);w.observe(0,true,7);var samples=new java.util.ArrayList<Long>();
        for(int i=0;i<30;i++)samples.add(i<15?10_000_000L:15_000_000L);
        assertEquals(RtBenchmarkWarmup.GpuStatus.SETTLING,w.gpuStatus(seconds(37),samples));
        assertEquals(RtBenchmarkWarmup.GpuStatus.SETTLING,w.gpuStatus(seconds(66),samples));
        assertEquals(RtBenchmarkWarmup.GpuStatus.STABILITY_TIMEOUT,w.gpuStatus(seconds(67),samples));
        java.util.Collections.fill(samples,10_000_000L);assertEquals(RtBenchmarkWarmup.GpuStatus.READY,w.gpuStatus(seconds(68),samples));
        w.observe(seconds(69),false,0);w.observe(seconds(70),true,7);samples.clear();
        assertEquals(RtBenchmarkWarmup.GpuStatus.WAITING_FIRST_SAMPLE,w.gpuStatus(seconds(71),samples));
    }
}
