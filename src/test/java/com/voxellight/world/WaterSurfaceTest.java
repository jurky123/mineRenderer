package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterSurfaceTest {
    @Test void phaseStaysBoundedAtLongUptimesAndWrapsContinuouslyForEveryHarmonic() {
        for(float speed:new float[]{0,.1f,1,1.37f,3}) {
            float p=WaterSurface.phase(Long.MAX_VALUE,speed);
            assertTrue(Float.isFinite(p) && p>=0 && p<Math.PI*2);
        }
        for(int harmonic:new int[]{1,2,3}) {
            float before=WaterSurface.phase(11_999_999_000L,1),after=WaterSurface.phase(12_000_001_000L,1);
            assertEquals(Math.cos(harmonic*before),Math.cos(harmonic*after),1e-5);
            assertEquals(Math.sin(harmonic*before),Math.sin(harmonic*after),1e-5);
        }
    }
    @Test void zeroSpeedFreezesPhaseAndInvalidControlsAreRejected() {
        assertEquals(0,WaterSurface.phase(100_000_000_000L,0));
        assertThrows(IllegalArgumentException.class,()->WaterSurface.speed(Float.NaN));
        assertThrows(IllegalArgumentException.class,()->WaterSurface.speed(3.1f));
        assertThrows(IllegalArgumentException.class,()->WaterSurface.strength(-.1f));
        assertThrows(IllegalArgumentException.class,()->WaterSurface.strength(Float.POSITIVE_INFINITY));
        assertEquals(.3f,WaterSurface.strength(.3f));
    }
    @Test void integerWaveSpatialFrequenciesRemainContinuousAtCameraModuloBoundaries() {
        for(int[] wave:new int[][]{{2,1},{-1,3},{5,2}}) {
            double before=(63.999*wave[0]+3*wave[1])*Math.PI*2/64;
            double after=(-.001*wave[0]+3*wave[1])*Math.PI*2/64;
            assertEquals(Math.cos(before),Math.cos(after),1e-10);
            assertEquals(Math.sin(before),Math.sin(after),1e-10);
        }
    }
}
