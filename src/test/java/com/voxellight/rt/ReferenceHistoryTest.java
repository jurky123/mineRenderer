package com.voxellight.rt;
import com.voxellight.world.VisualQuality;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReferenceHistoryTest {
    @Test void unchangedPerFrameQualityDoesNotResetConvergence(){
        var history=new ReferenceHistory();float[] pose=new float[16];
        assertTrue(history.consume(pose,true));
        for(int frame=0;frame<300;frame++){history.quality(VisualQuality.BALANCED);assertFalse(history.consume(pose,true));}
        history.quality(VisualQuality.HIGH);assertTrue(history.consume(pose,true));assertFalse(history.consume(pose,true));
    }
    @Test void motionResetsOnceAndTinyDriftCannotAccumulateUnchecked(){
        var history=new ReferenceHistory();float[] pose=new float[16];history.consume(pose,true);
        pose[12]=.00005f;assertFalse(history.consume(pose,true));pose[12]=.00015f;assertTrue(history.consume(pose,true));assertFalse(history.consume(pose,true));
        history.invalidate();assertTrue(history.consume(pose,true));assertFalse(history.consume(pose,true));
    }
    @Test void enteringReferenceCapturesANewPose(){var history=new ReferenceHistory();float[] pose=new float[16];history.consume(pose,false);assertTrue(history.consume(pose,true));}
}
