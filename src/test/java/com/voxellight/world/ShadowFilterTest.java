package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowFilterTest {
    @Test void budgetsKeepTheAcceptedReferenceAndBoundTheDefaultCost() {
        assertEquals(4,ShadowFilter.FAST.taps());
        assertEquals(16,ShadowFilter.BALANCED.taps());
        assertEquals(36,ShadowFilter.HIGH.taps());
        assertEquals(2,ShadowFilter.HIGH.radius());
    }
    @Test void phaseWeightedKernelMatchesIndependentBilinearBoxReference() {
        for(var filter:ShadowFilter.values())for(double x:new double[]{-2.01,-1.7,0,.12,.99,1.3})for(double y:new double[]{-.8,0,.35,.99,2.1}) {
            int bx=(int)Math.floor(x),by=(int)Math.floor(y);double fx=x-bx,fy=y-by;
            double expected=(1-fy)*((1-fx)*box(filter.radius(),bx,by)+fx*box(filter.radius(),bx+1,by))
                    +fy*((1-fx)*box(filter.radius(),bx,by+1)+fx*box(filter.radius(),bx+1,by+1));
            assertEquals(expected,kernel(filter.radius(),x,y),1e-12);
        }
    }
    @Test void scrollingAcrossTexelBoundariesDoesNotIntroduceVisibilityJumps() {
        for(var filter:ShadowFilter.values())for(int x=-3;x<=3;x++)for(double y:new double[]{-.7,.1,.9}) {
            double before=kernel(filter.radius(),x-1e-7,y),after=kernel(filter.radius(),x+1e-7,y);
            assertTrue(before>=-1e-12 && before<=1+1e-12 && after>=-1e-12 && after<=1+1e-12);
            assertEquals(before,after,2.1e-7);
        }
    }
    // Binary synthetic blocker field, including disconnected and diagonal edges.
    private static double blocked(int x,int y){return (x+y<0 || (x==2 && y>=0))?1:0;}
    private static double box(int radius,int x,int y) {
        double sum=0;for(int dy=-radius;dy<=radius;dy++)for(int dx=-radius;dx<=radius;dx++)sum+=blocked(x+dx,y+dy);
        return sum/((2*radius+1)*(2*radius+1));
    }
    private static double kernel(int radius,double x,double y) {
        int bx=(int)Math.floor(x),by=(int)Math.floor(y);double fx=x-bx,fy=y-by,sum=0;
        for(int dy=-radius;dy<=radius+1;dy++)for(int dx=-radius;dx<=radius+1;dx++) {
            double wx=dx==-radius?1-fx:dx==radius+1?fx:1,wy=dy==-radius?1-fy:dy==radius+1?fy:1;
            sum+=blocked(bx+dx,by+dy)*wx*wy;
        }
        return sum/((2*radius+1)*(2*radius+1));
    }
}
