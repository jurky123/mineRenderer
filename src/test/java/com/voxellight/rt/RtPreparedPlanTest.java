package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtPreparedPlanTest {
 @Test void storageComparisonKeepsExactQualityAndTwoAbbaRounds(){
  var blocks=RtBenchmarkPlan.prepared().blocks();assertEquals(8,blocks.size());
  var base=blocks.getFirst().config();assertEquals(RtExecutionOptions.Shadow.EXACT,base.shadow());
  assertEquals(RtExecutionOptions.Transport.FULL,base.transport());
  assertEquals(RtExecutionOptions.Integrator.WAVEFRONT,base.integrator());
  for(var block:blocks){
   assertEquals(block.position()==1||block.position()==2,block.candidate());
   if(!block.candidate()){assertEquals(base,block.config());continue;}
   var c=block.config();assertEquals(RtExecutionOptions.Shader.CLEAN,base.shader());assertEquals(RtExecutionOptions.Shader.GGX_PREPARED,c.shader());
   assertEquals(base,new RtBenchmarkPlan.Config(c.visibility(),c.queue(),c.omm(),c.ser(),c.direct(),c.sceneUpdate(),c.realtimePolicy(),base.shader(),c.shadow(),c.world(),c.integrator(),c.primary(),c.cache(),c.sampling(),c.kernel(),c.transport(),c.guides(),c.roughness()));
  }
 }
}
