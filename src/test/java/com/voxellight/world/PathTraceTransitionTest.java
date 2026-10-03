package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathTraceTransitionTest {
    @Test void transitionNeverReplacesObservationInOneStep() {
        var t=new PathTraceTransition();assertTrue(t.ready(0));
        t.begin(1_000);assertEquals(0,t.blend(1_000));
        assertEquals(.5f,t.blend(1_000+PathTraceTransition.DURATION_NS/2),1e-6);
        assertFalse(t.ready(1_000+PathTraceTransition.DURATION_NS/2));
        assertTrue(t.ready(1_000+PathTraceTransition.DURATION_NS));
    }
    @Test void freezeAndResumeRetainDisplayedBlend() {
        var t=new PathTraceTransition();t.begin(0);t.freeze(true,50_000_000);
        float held=t.blend(50_000_000);assertEquals(held,t.blend(5_000_000_000L));
        t.freeze(false,5_000_000_000L);assertEquals(held,t.blend(5_000_000_000L));
        assertTrue(t.ready(5_100_000_000L));
        t.reset();assertEquals(1,t.blend(5_100_000_000L));
    }
}
