package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbientOcclusionTest {
    @Test void halfResolutionBudgetSupports4kAndRejectsInvalidOrOverflowDimensions() {
        assertEquals(14_745_600,AmbientOcclusion.targetBytes(2560,1440));
        assertEquals(33_177_600,AmbientOcclusion.targetBytes(3840,2160));
        assertTrue(AmbientOcclusion.targetBytes(3840,2160)<AmbientOcclusion.TARGET_LIMIT);
        assertTrue(AmbientOcclusion.targetBytes(7680,4320)>AmbientOcclusion.TARGET_LIMIT);
        assertThrows(IllegalArgumentException.class,()->AmbientOcclusion.targetBytes(0,10));
        assertThrows(ArithmeticException.class,()->AmbientOcclusion.targetBytes(Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
    @Test void guidePixelsMatchTheSame2x2RepresentativeAtOddAndTinySizes() {
        for(int size:new int[]{1,2,3,5,7,1919,1920,2561}) {
            assertEquals(size/2+size%2,AmbientOcclusion.halfSize(size));
            for(int h=0;h<AmbientOcclusion.halfSize(size);h++) {
                int pixel=AmbientOcclusion.guidePixel(h,size);
                assertTrue(pixel>=0 && pixel<size);
                assertEquals(h,pixel/2);
                if(h<AmbientOcclusion.halfSize(size)-1)assertEquals(1,pixel%2);
            }
            assertEquals(size-1,AmbientOcclusion.guidePixel(AmbientOcclusion.halfSize(size)-1,size));
        }
        assertThrows(IllegalArgumentException.class,()->AmbientOcclusion.guidePixel(-1,10));
        assertThrows(IllegalArgumentException.class,()->AmbientOcclusion.guidePixel(5,10));
    }
    @Test void unoccludedPlanesRemainNeutralAndAContactWallOccludesHalfTheFrontFacingSlice() {
        for(double n:new double[]{-1.2,-.6,0,.6,1.2})assertEquals(1,AmbientOcclusion.sliceVisibility(n,n-Math.PI/2,n+Math.PI/2),1e-9);
        assertEquals(.5,AmbientOcclusion.sliceVisibility(0,-Math.PI/2,0),1e-9);
        assertEquals(0,AmbientOcclusion.sliceVisibility(0,0,0),1e-9);
        assertEquals(1,AmbientOcclusion.sliceVisibility(0,-Math.PI,Math.PI),1e-9);
    }
    @Test void analyticSliceVisibilityMatchesIndependentNumericalCosineWeightedIntegration() {
        for(double n:new double[]{-1.2,-.6,0,.6,1.2})for(double fraction:new double[]{.1,.4,.8,1}) {
            double low=n-Math.PI/2,high=n+Math.PI/2;
            double negative=low*fraction,positive=high*fraction;
            double expected=quadrature(n,negative,positive)/quadrature(n,low,high);
            assertEquals(expected,AmbientOcclusion.sliceVisibility(n,negative,positive),2e-7);
            assertEquals(AmbientOcclusion.sliceVisibility(n,negative,positive),AmbientOcclusion.sliceVisibility(-n,-positive,-negative),1e-9);
        }
    }
    private static double quadrature(double normal,double low,double high) {
        int count=20000;double step=(high-low)/count,sum=0;
        for(int i=0;i<count;i++){double angle=low+(i+.5)*step;sum+=Math.max(0,Math.cos(angle-normal))*Math.abs(Math.sin(angle))*step;}
        return sum;
    }
}
