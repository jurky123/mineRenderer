package com.voxellight.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtDiffuseKernelTest {
    @Test void packagedKernelHasFiniteCoefficientsAndIndependentUploadViews(){
        var a=RtDiffuseKernel.data();var b=RtDiffuseKernel.data();assertEquals(26004,a.remaining());
        assertTrue(a.isDirect());while(a.hasRemaining()){float value=a.getFloat();assertTrue(Float.isFinite(value)&&value>=0);}
        assertEquals(26004,b.remaining());assertTrue(b.getFloat()>0);
        // Normal outgoing incidence has no Oren-Nayar azimuth term, at every F0 node.
        for(int f=0;f<33;f++)for(int k=0;k<3;k++)assertEquals(0f,b.getFloat(33*8+(f*65+64)*12+k*4));
    }
}
