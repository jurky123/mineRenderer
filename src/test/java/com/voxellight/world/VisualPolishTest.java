package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualPolishTest {
    @Test void toneCurveIsMonotonicAndBoundedAndExposureIsManual() {
        for(float ev:new float[]{-2,0,VisualPolish.DEFAULT_EV,2}) {
            float previous=0;
            for(int i=0;i<=10000;i++) {
                float mapped=VisualPolish.filmic(i*.002f,ev);
                assertTrue(Float.isFinite(mapped) && mapped>=previous-.000001f && mapped>=0 && mapped<=1);
                previous=mapped;
            }
        }
        assertEquals(0,VisualPolish.filmic(0,0),.00001);
        assertEquals(1,VisualPolish.filmic(6,0),.00001);
        assertTrue(VisualPolish.filmic(1,1)>VisualPolish.filmic(1,0));
        assertEquals(2,VisualPolish.exposure(1));
        assertThrows(IllegalArgumentException.class,()->VisualPolish.exposure(Float.NaN));
        assertThrows(IllegalArgumentException.class,()->VisualPolish.exposure(3));
    }
    @Test void coverageIsContinuousAndVanishesInsideGuaranteedMaterialWindow() {
        assertEquals(1,VisualPolish.coverage(24));
        assertEquals(.5f,VisualPolish.coverage(28),.00001);
        assertEquals(0,VisualPolish.coverage(32));
        assertEquals(0,VisualPolish.coverage(100));
        assertTrue(VisualPolish.FADE_END<=2*16,"5-cube window guarantees at least two sections to every face at any camera phase");
        assertEquals(VisualPolish.coverage(28-.001f),VisualPolish.coverage(28+.001f),.001);
    }
    @Test void bloomTargetsRoundOddSizesAndStayBoundedAt4k() {
        assertEquals(641,VisualPolish.quarterSize(2561));
        assertEquals(2560L/4*(1440/4)*16,VisualPolish.bloomBytes(2560,1440));
        assertTrue(VisualPolish.bloomBytes(3840,2160)<VisualPolish.BLOOM_LIMIT);
        assertTrue(VisualPolish.bloomBytes(7680,4320)>VisualPolish.BLOOM_LIMIT);
        assertThrows(IllegalArgumentException.class,()->VisualPolish.quarterSize(0));
    }
    @Test void polishedSkyIsContinuousThroughHorizonAndWeatherPreservesDimensions() {
        float horizon=(float)(Math.PI/2);
        var a=LightingEnvironment.polished(ShadowLight.none(),true,horizon-.0001f,1);
        var b=LightingEnvironment.polished(ShadowLight.none(),true,horizon+.0001f,1);
        assertEquals(a.skyStrength(),b.skyStrength(),.001);
        assertEquals(a.horizonR(),b.horizonR(),.001);
        var noon=LightingEnvironment.polished(ShadowLight.world(0,(float)Math.PI,1,0),true,0,1);
        assertTrue(noon.skyB()>noon.skyR());
        assertTrue(noon.horizonR()>noon.skyR());
        var rain=LightingEnvironment.polished(ShadowLight.world(0,(float)Math.PI,0,0),true,0,0);
        assertTrue(rain.skyStrength()<noon.skyStrength());
        assertEquals(rain.skyR(),rain.skyB());
        assertEquals(LightingEnvironment.sample(ShadowLight.none(),false,0,1),LightingEnvironment.polished(ShadowLight.none(),false,0,1));
        var night=LightingEnvironment.polished(ShadowLight.world((float)Math.PI,0,1,4),true,(float)Math.PI,1);
        assertEquals(0,night.directStrength());
        assertTrue(night.skyStrength()>0 && night.skyStrength()<noon.skyStrength());
    }
}
