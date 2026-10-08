package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtTransportPlanTest {
 @Test void experimentsKeepTheSameLightingAndWorkloadControls(){var plan=RtBenchmarkPlan.transport();assertEquals(16,plan.blocks().size());for(var b:plan.blocks()){var c=b.config();assertEquals(RtExecutionOptions.Visibility.TRACE,c.visibility());assertEquals(RtExecutionOptions.Queue.FIXED,c.queue());assertEquals(RtExecutionOptions.Realtime.FULL,c.realtimePolicy());assertEquals(RtExecutionOptions.Shadow.EXACT,c.shadow());assertEquals(RtExecutionOptions.Direct.RIS,c.direct());assertFalse(c.omm());assertFalse(c.ser());assertEquals(b.position()==1||b.position()==2,b.candidate());if(!b.candidate())assertEquals(RtExecutionOptions.Transport.FULL,c.transport());}}
 @Test void restoreIncludesTransportMode(){var saved=RtBenchmarkPlan.Config.current();try{RtExecutionOptions.transport(RtExecutionOptions.Transport.TWO_PASS);var current=RtBenchmarkPlan.Config.current();assertEquals(RtExecutionOptions.Transport.TWO_PASS,current.transport());RtBenchmarkPlan.transport().blocks().get(0).config().apply();assertEquals(RtExecutionOptions.Transport.FULL,RtExecutionOptions.transport());current.apply();assertEquals(current,RtBenchmarkPlan.Config.current());}finally{saved.apply();}}
}
