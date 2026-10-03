package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereTest {
    @Test void horizontalRayMatchesBeerLambertAndZeroSkylightRejectsHaze() {
        assertEquals(1-Math.exp(-24*Atmosphere.DEFAULT_DENSITY),Atmosphere.amount(24,0,0,1,Atmosphere.DEFAULT_DENSITY),.000001);
        assertEquals(0,Atmosphere.amount(24,0,0,0,Atmosphere.DEFAULT_DENSITY));
        assertEquals(0,Atmosphere.amount(0,0,0,1,Atmosphere.DEFAULT_DENSITY));
        assertEquals(0,Atmosphere.amount(24,0,0,1,0));
    }
    @Test void quadratureMatchesADenseHeightIntegralWithinReferenceTolerance() {
        for(float vertical:new float[]{-24,0,24}) {
            double integral=0;
            for(int i=0;i<4096;i++)integral+=Math.exp(-(12+vertical*(i+.5)/4096)/48);
            double expected=1-Math.exp(-32*Atmosphere.DEFAULT_DENSITY*integral/4096);
            assertEquals(expected,Atmosphere.amount(32,vertical,12,1,Atmosphere.DEFAULT_DENSITY),.001);
        }
    }
    @Test void amountRemainsBoundedAndRainAndLowerAltitudeIncreaseHaze() {
        float near=Atmosphere.amount(12,0,0,1,.018f),far=Atmosphere.amount(24,0,0,1,.018f);
        assertTrue(far>near);
        assertTrue(Atmosphere.amount(24,0,96,1,.018f)<far);
        assertTrue(Atmosphere.amount(24,0,0,1,Atmosphere.weatherDensity(.018f,0))>far);
        for(float height:new float[]{-10000,0,10000}) {
            float value=Atmosphere.amount(10000,0,height,1,.08f);
            assertTrue(Float.isFinite(value) && value>=0 && value<1);
        }
        assertThrows(IllegalArgumentException.class,()->Atmosphere.density(Float.NaN));
        assertThrows(IllegalArgumentException.class,()->Atmosphere.density(.1f));
    }
    @Test void directionalGlowPeaksOnlyWhenLookingTowardTheLight() {
        assertEquals(1,Atmosphere.forward(1));
        assertEquals(0,Atmosphere.forward(-1));
        assertTrue(Atmosphere.forward(.5f)<Atmosphere.forward(.9f));
    }
}
