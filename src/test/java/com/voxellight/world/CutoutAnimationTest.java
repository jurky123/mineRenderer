package com.voxellight.world;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CutoutAnimationTest {
    private ByteBuffer staticQuad(){var data=ByteBuffer.allocate(36*4);for(int i=0;i<4;i++){data.put(i*36+35,(byte)16);data.put(i*36+31,(byte)0x10);}return data;}
    @Test void staticLeavesCacheButOneAnimatedVertexRefreshesTheWholeCutoutLayer(){
        var data=staticQuad();assertFalse(CutoutAnimation.needsRefresh(data,36));
        data.put(2*36+31,(byte)0x90);assertTrue(CutoutAnimation.needsRefresh(data,36));
    }
    @Test void unknownEmittersLayoutsAndIncompleteVerticesRemainConservative(){
        var data=staticQuad();data.put(35,(byte)0);assertTrue(CutoutAnimation.needsRefresh(data,36));
        assertTrue(CutoutAnimation.needsRefresh(data,28));data.limit(143);assertTrue(CutoutAnimation.needsRefresh(data,36));
    }
    @Test void inspectionPreservesBufferRangeAndIgnoresBytesOutsideTheView(){
        var data=staticQuad();data.position(36);data.put(35,(byte)0);
        assertFalse(CutoutAnimation.needsRefresh(data,36));assertEquals(36,data.position());assertEquals(144,data.limit());
    }
}
