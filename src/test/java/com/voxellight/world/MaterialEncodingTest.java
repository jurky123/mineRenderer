package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialEncodingTest {
    @Test void metadataKeepsFlagsSeparateFromModelEmission() {
        for (int flags=0;flags<16;flags++) for(int emission=0;emission<16;emission++) {
            int packed=MaterialEncoding.packQuadEmissionFlags(emission,flags);
            assertEquals(emission,packed&15);assertEquals(flags,packed>>>4);
        }
        assertThrows(IllegalArgumentException.class,()->MaterialEncoding.packQuadEmissionFlags(16,1));
    }
    @Test void linearMaterialStorageAndPreviewRoundTripAuthoredColors() {
        for(int i=0;i<=255;i++) {
            float encoded=i/255f;
            assertEquals(encoded,MaterialEncoding.encodeSrgb(MaterialEncoding.decodeSrgb(encoded)),2e-6f);
        }
        assertEquals(.214041f,MaterialEncoding.decodeSrgb(.5f),1e-6f);
    }
    @Test void supportedPixelsRequireDepthAgreementWithoutFarDistanceLeakTolerance() {
        for(float depth:new float[]{.9f,.1f,.001f,.000001f}) {
            float near=depth;for(int i=0;i<8;i++)near=Math.nextUp(near);
            assertTrue(MaterialEncoding.depthMatches(depth,near));
            assertFalse(MaterialEncoding.depthMatches(depth,Math.nextUp(near)));
            assertFalse(MaterialEncoding.depthMatches(depth,depth*1.01f));
        }
        assertFalse(MaterialEncoding.depthMatches(0,0));assertFalse(MaterialEncoding.depthMatches(Float.NaN,.5f));
    }
    @Test void targetBudgetUsesActualFormatsAndRejectsInvalidSizes() {
        assertEquals(88_473_600,MaterialEncoding.targetBytes(2560,1440));
        assertTrue(MaterialEncoding.targetBytes(3840,2160)<=MaterialEncoding.TARGET_LIMIT);
        assertTrue(MaterialEncoding.targetBytes(7680,4320)>MaterialEncoding.TARGET_LIMIT);
        assertThrows(IllegalArgumentException.class,()->MaterialEncoding.targetBytes(0,1440));
    }
}
