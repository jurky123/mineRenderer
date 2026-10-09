package com.voxellight.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtExecutionOptionsTest {
    @Test void slicedCpuModesKeepGpuContextRevision(){
        var original=RtExecutionOptions.sceneUpdate();
        try{
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.OPTIMIZED);
            long revision=RtExecutionOptions.revision();
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.ASYNC_PREP);
            assertEquals(RtExecutionOptions.SceneUpdate.ASYNC_PREP,RtExecutionOptions.sceneUpdate());
            assertEquals(revision,RtExecutionOptions.revision());
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.OPTIMIZED);
            assertEquals(revision,RtExecutionOptions.revision());
        }finally{RtExecutionOptions.sceneUpdate(original);}
    }
    @Test void legacyScratchLayoutRequiresRecreationInBothDirections(){
        var original=RtExecutionOptions.sceneUpdate();
        try{
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.OPTIMIZED);
            long revision=RtExecutionOptions.revision();
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.LEGACY);
            assertEquals(revision+1,RtExecutionOptions.revision());
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.LEGACY);
            assertEquals(revision+1,RtExecutionOptions.revision());
            RtExecutionOptions.sceneUpdate(RtExecutionOptions.SceneUpdate.ASYNC_PREP);
            assertEquals(revision+2,RtExecutionOptions.revision());
        }finally{RtExecutionOptions.sceneUpdate(original);}
    }
}
