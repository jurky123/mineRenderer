package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightingEnvironmentTest {
    @Test void nativeSkyUsesLinearColorWithoutApplyingTimeOrWeatherTwice(){
        var sun=ShadowLight.fixed();
        var a=LightingEnvironment.vanillaSky(sun,true,0x804020,0,1);
        var b=LightingEnvironment.vanillaSky(sun,true,0x804020,(float)Math.PI,0);
        assertEquals(.2158605f,a.skyR(),1e-6);assertEquals(.05126946f,a.skyG(),1e-6);assertEquals(.01444384f,a.skyB(),1e-6);
        assertEquals(a.skyR(),b.skyR());assertEquals(a.skyG(),b.skyG());assertEquals(a.skyB(),b.skyB());assertEquals(1,a.skyStrength());
        assertEquals(LightingEnvironment.polished(sun,true,0,1).directStrength(),a.directStrength());
        assertEquals(0,LightingEnvironment.vanillaSky(sun,true,0,0,1).skyR());
        assertEquals(1,LightingEnvironment.vanillaSky(sun,true,0xffffff,0,1).skyR());
        assertEquals(LightingEnvironment.polished(ShadowLight.none(),false,0,1),LightingEnvironment.vanillaSky(ShadowLight.none(),false,0,0,1));
    }
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
