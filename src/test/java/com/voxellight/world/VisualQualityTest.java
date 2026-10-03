package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualQualityTest {
    @Test void allPresetsFitShaderLoopBoundsAndPreserveMediumEnergy() {
        int oldVolume=0,oldReflection=0;
        for(var quality:VisualQuality.values()) {
            int steps=quality.volumeSteps();
            assertTrue(steps>oldVolume && steps<=32);
            assertTrue(quality.reflectionSteps()>oldReflection && quality.reflectionSteps()<=32);
            float t=1,scatter=0;
            for(int i=0;i<steps;i++) {
                float transmission=VolumetricLight.stepTransmission(.001f,0,96f/steps,steps);
                scatter+=t*(1-transmission);t*=transmission;
            }
            assertEquals(Math.exp(-.096),t,1e-6);
            assertEquals(1,t+scatter,1e-6);
            oldVolume=steps;oldReflection=quality.reflectionSteps();
        }
    }
}
