package com.voxellight.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HeldLightIntensityTest {
    @Test void nativeLevelsHaveBoundedSceneLinearIntensity(){assertEquals(0,HeldLightIntensity.intensity(0));assertEquals(20,HeldLightIntensity.intensity(15));assertEquals(20,HeldLightIntensity.intensity(100));assertEquals(0,HeldLightIntensity.intensity(-1));assertTrue(HeldLightIntensity.intensity(14)>10);}
}
