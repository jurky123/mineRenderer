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
}
