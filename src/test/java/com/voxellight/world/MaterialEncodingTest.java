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
        assertEquals(73_728_000,MaterialEncoding.targetBytes(2560,1440));
        assertTrue(MaterialEncoding.targetBytes(3840,2160)<=MaterialEncoding.TARGET_LIMIT);
        assertTrue(MaterialEncoding.targetBytes(7680,4320)>MaterialEncoding.TARGET_LIMIT);
        assertThrows(IllegalArgumentException.class,()->MaterialEncoding.targetBytes(0,1440));
    }
    @Test void packedNormalsStayWithinHalfADegreeIncludingSignedAxes() {
        var random=new java.util.Random(27);
        for(int i=0;i<5000;i++) {
            double x=random.nextDouble()*2-1,y=random.nextDouble()*2-1,z=random.nextDouble()*2-1;
            double length=Math.sqrt(x*x+y*y+z*z);if(length<.01)continue;
            x/=length;y/=length;z/=length;
            double px=unorm(x*.5+.5)*2-1,py=unorm(y*.5+.5)*2-1,pz=unorm(z*.5+.5)*2-1;
            double cosine=(x*px+y*py+z*pz)/Math.sqrt(px*px+py*py+pz*pz);
            assertTrue(cosine>=Math.cos(Math.toRadians(.5)),"Packed normal angular error");
        }
        assertEquals(-1,unorm(0)*2-1);assertEquals(1,unorm(1)*2-1);
    }
    @Test void packedLightAndSurfaceMarkersKeepTheirSemantics() {
        for(int light=0;light<=15;light++)assertEquals(light/15.0,unorm(light/15.0),1e-7);
        assertEquals(0,unorm(0)*3); // cleared: uncaptured
        assertEquals(1,unorm(1.0/3)*3); // supported reference terrain / entity
        assertTrue(unorm(.75/3)*3>.5); // dynamic history exclusion stays supported
        for(int fade=0;fade<=100;fade++) {
            double decoded=unorm((2+fade/100.0)/3)*3;
            assertTrue(decoded>=2 && decoded<=3); // native terrain tag never lost
            assertEquals(fade/100.0,decoded-2,3.0/510+1e-7);
        }
    }
    private static double unorm(double value){return Math.round(value*255)/255.0;}
}
