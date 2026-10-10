package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtQueueLatePlanTest {
 @Test void masksPreserveLegacyAndOnlyCompactLateBounces(){assertEquals(60,RtExecutionOptions.Queue.LATE.continuationMask(0));assertEquals(62,RtExecutionOptions.Queue.COMPACT.continuationMask(0));assertEquals(0,RtExecutionOptions.Queue.FIXED.continuationMask(0));assertEquals(12,RtExecutionOptions.Queue.HYBRID.continuationMask(12));assertTrue(RtBenchmarkPlan.queuesLate(false).blocks().isEmpty());}
 @Test void storageComparisonKeepsExactQualityAndTwoAbbaRounds(){
  var blocks=RtBenchmarkPlan.queuesLate(true).blocks();assertEquals(8,blocks.size());
  var base=blocks.getFirst().config();assertEquals(RtExecutionOptions.Shadow.EXACT,base.shadow());
  assertEquals(RtExecutionOptions.Transport.FULL,base.transport());
  assertEquals(RtExecutionOptions.Integrator.WAVEFRONT,base.integrator());
  for(var block:blocks){
   assertEquals(block.position()==1||block.position()==2,block.candidate());
   if(!block.candidate()){assertEquals(base,block.config());continue;}
   var c=block.config();assertEquals(RtExecutionOptions.Queue.FIXED,base.queue());assertEquals(RtExecutionOptions.Queue.LATE,c.queue());
   assertEquals(base,new RtBenchmarkPlan.Config(c.visibility(),base.queue(),c.omm(),c.ser(),c.direct(),c.sceneUpdate(),c.realtimePolicy(),c.shader(),c.shadow(),c.world(),c.integrator(),c.primary(),c.cache(),c.sampling(),c.kernel(),c.transport(),c.guides(),c.roughness()));
  }
 }
}
