package com.voxellight.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtCacheCostPlanTest {
    @Test void kernelComparisonChangesOnlyKernelAndRepeatsAbba(){
        var plan=RtBenchmarkPlan.cost(true);assertEquals(16,plan.blocks().size());
        var a=plan.blocks().get(0).config();var b=plan.blocks().get(1).config();
        assertEquals(RtExecutionOptions.Kernel.QUADRATURE,a.kernel());
        assertEquals(RtExecutionOptions.Kernel.PREINTEGRATED,b.kernel());
        assertEquals(new RtBenchmarkPlan.Config(a.visibility(),a.queue(),a.omm(),a.ser(),a.direct(),a.sceneUpdate(),a.realtimePolicy(),a.shader(),a.shadow(),a.world(),a.integrator(),a.primary(),a.cache(),a.sampling(),b.kernel()),b);
        for(int round=0;round<2;round++)for(int i=0;i<4;i++)assertEquals(i==1||i==2,plan.blocks().get(round*4+i).candidate());
        assertEquals(RtExecutionOptions.Realtime.FULL,plan.blocks().get(8).config().realtimePolicy());
        assertEquals(RtExecutionOptions.Primary.MONOLITHIC,plan.blocks().get(8).config().primary());
        assertEquals(RtExecutionOptions.Cache.PRIMARY,plan.blocks().get(9).config().cache());
    }
    @Test void temporaryKernelIsCapturedAndRestored(){
        var saved=RtBenchmarkPlan.Config.current();
        try {RtExecutionOptions.kernel(RtExecutionOptions.Kernel.QUADRATURE);var before=RtBenchmarkPlan.Config.current();
            RtBenchmarkPlan.cost(false).blocks().get(1).config().apply();
            assertEquals(RtExecutionOptions.Kernel.PREINTEGRATED,RtExecutionOptions.kernel());
            before.apply();assertEquals(before,RtBenchmarkPlan.Config.current());
        } finally {saved.apply();}
    }
}
