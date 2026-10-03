package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightingEnvironmentTest {
    @Test void moonIsCoolerAndWeakerThanDaylightButNightKeepsAmbient() {
        var day = LightingEnvironment.sample(ShadowLight.world(0,(float)Math.PI,1,0),true,0,1);
        var night = LightingEnvironment.sample(ShadowLight.world((float)Math.PI,0,1,0),true,(float)Math.PI,1);
        assertTrue(day.directStrength() > night.directStrength());
        assertTrue(night.directStrength() > 0);
        assertTrue(night.directB() > night.directR());
        assertTrue(day.skyStrength() > night.skyStrength());
        assertTrue(night.skyStrength() > 0);
        var newMoon = LightingEnvironment.sample(ShadowLight.world((float)Math.PI,0,1,4),true,(float)Math.PI,1);
        assertEquals(0,newMoon.directStrength());
        assertTrue(newMoon.skyStrength() > 0);
    }
    @Test void weatherAttenuatesBothDirectAndSkyWithoutCreatingCelestialsInNether() {
        var clear = LightingEnvironment.sample(ShadowLight.world(0,(float)Math.PI,1,0),true,0,1);
        var rain = LightingEnvironment.sample(ShadowLight.world(0,(float)Math.PI,0,0),true,0,0);
        assertTrue(rain.directStrength() < clear.directStrength());
        assertTrue(rain.skyStrength() < clear.skyStrength());
        var nether = LightingEnvironment.sample(ShadowLight.none(),false,0,1);
        assertEquals(0,nether.directStrength());
        assertTrue(nether.skyStrength() < clear.skyStrength());
    }
    @Test void combinedBudgetAccountsForMaterialAndHdrBeforeAllocation() {
        assertEquals(2560L*1440*28,LightingEnvironment.targetBytes(2560,1440));
        assertTrue(LightingEnvironment.targetBytes(3840,2160) <= LightingEnvironment.TARGET_LIMIT);
        assertTrue(LightingEnvironment.targetBytes(7680,4320) > LightingEnvironment.TARGET_LIMIT);
        assertThrows(IllegalArgumentException.class,()->LightingEnvironment.targetBytes(0,100));
    }
}
