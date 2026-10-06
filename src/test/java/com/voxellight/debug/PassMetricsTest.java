package com.voxellight.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PassMetricsTest {
    @Test
    void delayedGpuResultsCannotResurrectEvictedOrResetFrames() {
        var metrics = new PassMetrics(2);
        metrics.record(1, "COLOR", 1920, 1080, 10);
        metrics.record(2, "COLOR", 1920, 1080, 20);
        metrics.record(3, "DEPTH", 1920, 1080, 30);
        metrics.completeGpu(1, 100);
        metrics.completeGpu(2, 200);
        assertEquals(2, metrics.snapshot().size());
        assertEquals(2, metrics.snapshot().getFirst().frame());
        assertEquals(200L, metrics.snapshot().getFirst().gpuNanos());
        assertEquals(2,metrics.newestGpuSample().frame());
        assertNull(metrics.snapshot().getLast().gpuNanos());
        metrics.clear();
        metrics.completeGpu(3, 300);
        assertTrue(metrics.snapshot().isEmpty());
        assertNull(metrics.newestGpuSample());
    }

    @Test void scopesShareRenderFramesAndDelayedResultsKeepTheirWorkload(@TempDir Path directory)throws Exception{
        var metrics=new PassMetrics(3);
        metrics.recordScope(10,4,0,"batch",320,180,2,9,100);
        metrics.recordScope(11,4,10,"primary",320,180,2,9,20);
        metrics.completeGpu(11,30);metrics.completeGpu(10,110);
        assertEquals(4,metrics.snapshot().getLast().frame());assertEquals(10,metrics.snapshot().getLast().parentScopeId());
        assertEquals(11,metrics.newestGpuSample().scopeId());
        var csv=directory.resolve("scopes.csv");metrics.export(csv);
        assertTrue(Files.readAllLines(csv).getFirst().contains("scope_id,parent_scope_id,spp,scene_generation"));
        assertTrue(Files.readAllLines(csv).getLast().endsWith(",2,11,10,2,9"));
    }

    @Test
    void missingAndInvalidGpuMeasurementsAreNotReportedAsZero(@TempDir Path directory) throws Exception {
        var metrics = new PassMetrics(2);
        metrics.record(1, "DEPTH", 1280, 720, 10);
        metrics.record(2, "COLOR", 1280, 720, 20);
        metrics.completeGpu(1, -1);
        metrics.completeGpu(2, 0);
        var csv = directory.resolve("pass.csv");
        metrics.export(csv);
        var lines = Files.readAllLines(csv);
        assertEquals("1,DEPTH,1280,720,10,", lines.get(1));
        assertEquals("2,COLOR,1280,720,20,0", lines.get(2));
    }
}
