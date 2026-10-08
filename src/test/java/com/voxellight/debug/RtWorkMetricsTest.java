package com.voxellight.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RtWorkMetricsTest {
    @TempDir Path directory;
    @Test void delayedCountersRetainSubmissionWorkloadAndOwnTheirSnapshot() throws Exception {
        var metrics=new RtWorkMetrics();long[] active={100,80,60,40,20,10};
        metrics.record(17,20,5,1,4,active,180,24);active[0]=999;
        var path=directory.resolve("rays.csv");metrics.export(path);
        assertTrue(Files.readString(path).contains("17,20,5,1,4,100,80,60,40,20,10,180,24"));
        metrics.clear();metrics.export(path);assertEquals(1,Files.readAllLines(path).size());
        assertThrows(IllegalArgumentException.class,()->metrics.record(1,1,1,1,0,new long[5],0,0));
    }
    @Test void realtimeCountersRetainTheirSnapshotAndExport()throws Exception{
        var metrics=new RtWorkMetrics();var counters=new long[16];counters[0]=100;counters[1]=25;counters[2]=75;
        metrics.record(1,10,10,1,1,new long[6],0,0,0,0,0,new long[6][4],counters);counters[1]=99;
        assertEquals(25,metrics.sample(1).realtime()[1]);var path=directory.resolve("runtime.csv");metrics.export(path);var lines=Files.readAllLines(path);
        assertTrue(lines.getFirst().contains("rt_full_paths"));assertEquals(lines.getFirst().split(",").length,lines.get(1).split(",").length);assertTrue(lines.get(1).contains(",100,25,75,0,0,0,0,0,0,0,0,0,0,0,0,0"));assertEquals(56,metrics.sample(1).realtime().length);
    }

    @Test void realtimeCoverageDistinguishesInactiveCacheFromMeasuredZeroHitRate(){
        var metrics=new RtWorkMetrics();assertNull(metrics.realtimeSummary().get("cacheHitFraction"));assertNull(metrics.realtimeSummary().get("fullPathDensity"));
        long[] counters=new long[16];counters[0]=100;counters[1]=99;counters[2]=1;
        metrics.record(8,10,10,1,1,new long[6],0,0,0,0,0,new long[6][4],counters);
        assertEquals(.99,metrics.realtimeSummary().get("fullPathDensity"));assertEquals(.01,metrics.realtimeSummary().get("reuseFraction"));assertEquals(false,metrics.realtimeSummary().get("cacheQueried"));
        counters[4]=20;counters[5]=0;metrics.record(8,10,10,1,1,new long[6],0,0,0,0,0,new long[6][4],counters);
        assertEquals(0.,metrics.realtimeSummary().get("cacheHitFraction"));assertEquals(true,metrics.realtimeSummary().get("cacheQueried"));assertEquals(false,metrics.realtimeSummary().get("cacheTerminatedPaths"));
        counters[5]=10;metrics.record(16,10,10,1,1,new long[6],0,0,0,0,0,new long[6][4],counters);
        assertEquals(.25,metrics.realtimeSummary().get("cacheHitFraction"));assertEquals(false,metrics.realtimeSummary().get("cacheTerminatedPaths"));
    }
    @Test void structuralDiagnosticsAreExportedAndDoNotAliasLegacyCounters()throws Exception{
        var metrics=new RtWorkMetrics();long[] counters=new long[44];counters[28]=7;counters[39]=5;counters[41]=12;
        metrics.record(16,2,2,1,0,new long[6],0,0,0,0,0,new long[6][4],counters);
        var reasons=(java.util.Map<?,?>)metrics.realtimeSummary().get("reasonCounters");assertEquals(7L,reasons.get("train_duplicate"));assertEquals(5L,reasons.get("confidence_16_plus"));assertEquals(12L,reasons.get("primary_fast_guide"));
        var path=directory.resolve("diagnostics.csv");metrics.export(path);var lines=Files.readAllLines(path);assertEquals(lines.getFirst().split(",").length,lines.get(1).split(",").length);
    }
    @Test void roughDiffuseReuseDoesNotClaimWholePathTermination(){
        var metrics=new RtWorkMetrics();long[] counters=new long[56];counters[0]=100;counters[44]=80;counters[45]=20;counters[4]=50;counters[5]=25;counters[49]=10;counters[50]=15;counters[51]=25;counters[52]=5;
        metrics.record(8,10,10,1,1,new long[6],0,0,0,0,0,new long[6][4],counters);
        var summary=metrics.realtimeSummary();assertEquals(.8,summary.get("diffuseEligibleFraction"));assertEquals(.5,summary.get("cacheQueriesPerEligible"));assertEquals(true,summary.get("diffuseCacheUsed"));assertEquals(false,summary.get("cacheTerminatedPaths"));assertEquals(25L,summary.get("exactSpecularContinuations"));assertEquals(5L,summary.get("diffuseHistoryReused"));
        counters[53]=3;metrics.record(8,10,10,1,1,new long[6],0,0,0,0,0,new long[6][4],counters);assertEquals(true,metrics.realtimeSummary().get("cacheTerminatedPaths"));
    }
    @Test void extendedReasonsAreNamedAndCopied(){
        var metrics=new RtWorkMetrics();long[] counters=new long[28];counters[17]=42;counters[25]=13;
        metrics.record(8,1,1,1,0,new long[6],0,0,0,0,0,new long[6][4],counters);counters[17]=99;
        var reasons=(java.util.Map<?,?>)metrics.realtimeSummary().get("reasonCounters");assertEquals(42L,reasons.get("cache_immature"));assertEquals(13L,reasons.get("sparse_low_confidence"));
        assertThrows(IllegalArgumentException.class,()->metrics.record(8,1,1,1,0,new long[6],0,0,0,0,0,new long[6][4],new long[27]));
    }
}
