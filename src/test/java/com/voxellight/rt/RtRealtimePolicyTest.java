package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtRealtimePolicyTest {
 @Test void independentVariantsAndRestore(){
  var original=RtBenchmarkPlan.Config.current();
  try{var plan=RtBenchmarkPlan.realtime(true);assertEquals(24,plan.blocks().size());for(var b:plan.blocks()){assertEquals(RtExecutionOptions.Direct.RIS,b.config().direct());assertEquals(RtExecutionOptions.SceneUpdate.OPTIMIZED,b.config().sceneUpdate());assertEquals(!b.candidate(),b.config().realtimePolicy()==RtExecutionOptions.Realtime.FULL);b.config().apply();assertEquals(b.config(),RtBenchmarkPlan.Config.current());}}
  finally{original.apply();}assertEquals(original,RtBenchmarkPlan.Config.current());
 }
 @Test void referenceForcesBypassForEveryRequestedPolicy(){for(var mode:RtExecutionOptions.Realtime.values())assertEquals(0,mode.flags(false));assertEquals(3,RtExecutionOptions.Realtime.CACHE_SPARSE.flags(true));}
 @Test void oldExecutionSuitesHoldFullPaths(){for(var b:RtBenchmarkPlan.create(true,true,true,true).blocks())assertEquals(RtExecutionOptions.Realtime.FULL,b.config().realtimePolicy());}
 @Test void revisionsInvalidateCacheWhenChangingAlgorithm(){var old=RtExecutionOptions.realtime();try{RtExecutionOptions.realtime(RtExecutionOptions.Realtime.FULL);long revision=RtExecutionOptions.revision();RtExecutionOptions.realtime(RtExecutionOptions.Realtime.CACHE_SPARSE);assertTrue(RtExecutionOptions.revision()>revision);revision=RtExecutionOptions.revision();RtExecutionOptions.realtime(RtExecutionOptions.Realtime.CACHE_SPARSE);assertEquals(revision,RtExecutionOptions.revision());}finally{RtExecutionOptions.realtime(old);}}
}
