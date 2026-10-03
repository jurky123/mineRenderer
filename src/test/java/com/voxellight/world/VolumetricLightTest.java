package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VolumetricLightTest {
    @Test void constantMediumIntegrationMatchesBeerLambertAndPreservesSourceEnergy() {
        for(float density:new float[]{0,.001f,.003f}) {
            float t=1,scatter=0;
            for(int i=0;i<VolumetricLight.STEPS;i++) {
                float step=VolumetricLight.stepTransmission(density,0,96f/VolumetricLight.STEPS);
                scatter+=t*(1-step);t*=step;
            }
            assertEquals(Math.exp(-density*96),t,1e-6);
            assertEquals(1,t+scatter,1e-6,"Constant source integration cannot invent energy");
        }
    }
    @Test void blockedDirectLightDoesNotScatterAndOpacityRemainsBounded() {
        float t=1,scatter=0;
        for(int i=0;i<VolumetricLight.STEPS;i++) {
            float step=VolumetricLight.stepTransmission(.08f,-48,6);
            scatter+=t*(1-step)*0;t*=step;
        }
        assertEquals(0,scatter);
        assertEquals(Math.exp(-2),t,1e-6);
        assertEquals(1,VolumetricLight.stepTransmission(0,10,6));
        assertTrue(VolumetricLight.stepTransmission(.001f,96,6)>VolumetricLight.stepTransmission(.001f,0,6));
    }
    @Test void forwardPhaseIsFiniteAndPeaksTowardTheLight() {
        assertTrue(VolumetricLight.phase(1)>VolumetricLight.phase(0));
        assertTrue(VolumetricLight.phase(0)>VolumetricLight.phase(-1));
        assertEquals(VolumetricLight.phase(1),VolumetricLight.phase(2));
        for(int i=-100;i<=100;i++)assertTrue(Float.isFinite(VolumetricLight.phase(i/100f)));
    }
    @Test void quarterTargetHandlesOddResizesWithinThePublishedBudget() {
        assertEquals(8,VolumetricLight.targetBytes(1,1));
        assertEquals(214L*120*8,VolumetricLight.targetBytes(853,479));
        assertEquals(640L*360*8,VolumetricLight.targetBytes(2560,1440));
        assertTrue(VolumetricLight.targetBytes(3840,2160)<=VolumetricLight.TARGET_LIMIT);
        assertTrue(VolumetricLight.targetBytes(7680,4320)>VolumetricLight.TARGET_LIMIT);
    }
    @Test void depthRejectionPreventsFarHazeFromBleedingOntoNearSilhouettes() {
        assertEquals(1,VolumetricLight.guideWeight(10,10));
        assertTrue(VolumetricLight.guideWeight(10,80)<1e-5);
        assertTrue(VolumetricLight.guideWeight(80,81)>VolumetricLight.guideWeight(10,11));
    }
}
