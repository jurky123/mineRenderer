package com.voxellight.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HeldLightIntensityTest {
    @Test void fullBlockLanternsRetainAreaEmission(){assertFalse(HeldLightIntensity.flame("jack_o_lantern"));assertFalse(HeldLightIntensity.flame("sea_lantern"));assertTrue(HeldLightIntensity.flame("soul_lantern"));assertTrue(HeldLightIntensity.flame("wall_torch"));}
    @Test void heldFacesAndFlameHaveSeparateRadiometricScale(){assertEquals(8,HeldLightIntensity.intensity(15,false));assertEquals(20,HeldLightIntensity.intensity(15,true));assertEquals(0,HeldLightIntensity.intensity(0,false));}
    @Test void nativeLevelsHaveBoundedSceneLinearIntensity(){assertEquals(0,HeldLightIntensity.intensity(0));assertEquals(20,HeldLightIntensity.intensity(15));assertEquals(20,HeldLightIntensity.intensity(100));assertEquals(0,HeldLightIntensity.intensity(-1));assertTrue(HeldLightIntensity.intensity(14)>10);}
}
