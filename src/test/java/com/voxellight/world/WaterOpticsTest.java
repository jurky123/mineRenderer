package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterOpticsTest {
    @Test void schlickReflectanceHasCorrectNormalAndGrazingLimits() {
        assertEquals(.02f,WaterOptics.fresnel(1),.000001);
        assertEquals(1,WaterOptics.fresnel(0),.000001);
        assertTrue(WaterOptics.fresnel(.1f)>WaterOptics.fresnel(.9f));
    }
    @Test void deeperWaterAbsorbsMoreAndRedIsAbsorbedFirst() {
        assertEquals(1,WaterOptics.transmission(.18f,0));
        assertTrue(WaterOptics.transmission(.18f,8)<WaterOptics.transmission(.18f,2));
        assertTrue(WaterOptics.transmission(.18f,8)<WaterOptics.transmission(.028f,8));
    }
    @Test void budgetCountsOneHdrAndOneDepthAndRejectsMissingOrChangedBackground() {
        assertEquals(2560L*1440*12,WaterOptics.targetBytes(2560,1440));
        assertTrue(WaterOptics.targetBytes(3840,2160)<WaterOptics.TARGET_LIMIT);
        assertTrue(WaterOptics.targetBytes(7680,4320)>WaterOptics.TARGET_LIMIT);
        assertFalse(WaterOptics.backgroundMatches(0,20));
        assertFalse(WaterOptics.backgroundMatches(20,18));
        assertTrue(WaterOptics.backgroundMatches(20,20.01f));
    }
    @Test void nativeMainMustBePresentAndTheNativeBodyRemainsTheFallback() {
        String vertex=WaterShaders.wrap("void main() { gl_Position=vec4(1); }",true,"","");
        assertTrue(vertex.contains("void voxellightNativeMain()"));
        assertTrue(vertex.contains("gl_Position=vec4(1)"));
        assertTrue(vertex.contains("voxellightNativeMain();"));
        assertThrows(IllegalArgumentException.class,()->WaterShaders.wrap("",true,"",""));
    }
}
