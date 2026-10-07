package com.voxellight.rt;

import com.voxellight.debug.PassMetrics;
import com.voxellight.debug.RtBenchmarkResults;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RtFrameExecutionTest {
    @Test void frameJitterUsesBoundedTwoDimensionalHaltonWithFixedPeriod(){
        assertEquals(0,RtJitter.x(0));assertEquals(-1f/6,RtJitter.y(0),1e-7);
        assertEquals(-.25f,RtJitter.x(1));assertEquals(1f/6,RtJitter.y(1),1e-7);
        double x=0,y=0;for(int frame=0;frame<1024;frame++){
            assertTrue(RtJitter.x(frame)>=-.5&&RtJitter.x(frame)<.5);
            assertTrue(RtJitter.y(frame)>=-.5&&RtJitter.y(frame)<.5);
            assertEquals(RtJitter.x(frame),RtJitter.x(frame+1024));x+=RtJitter.x(frame);y+=RtJitter.y(frame);
        }assertEquals(0,x/1024,.002);assertEquals(0,y/1024,.002);
    }
    @Test void frameSuiteChangesOnlyTheComparedControlAndRestoresEveryNewControl(){
        var plan=RtBenchmarkPlan.frame(true);assertEquals(24,plan.blocks().size());
        var original=RtBenchmarkPlan.Config.current();
        try{for(var block:plan.blocks()){
            assertEquals(block.position()==1||block.position()==2,block.candidate());
            block.config().apply();assertEquals(block.config(),RtBenchmarkPlan.Config.current());
        }}finally{original.apply();}assertEquals(original,RtBenchmarkPlan.Config.current());
        var world=plan.blocks().subList(0,8);
        assertEquals(RtExecutionOptions.World.COMPOSITE,world.get(0).config().world());
        assertEquals(RtExecutionOptions.World.EXCLUSIVE,world.get(1).config().world());
        assertEquals(world.get(0).config().shadow(),world.get(1).config().shadow());
        var shadow=plan.blocks().subList(8,16);
        assertEquals(RtExecutionOptions.Shadow.EXACT,shadow.get(0).config().shadow());
        assertEquals(RtExecutionOptions.Shadow.FAST,shadow.get(1).config().shadow());
        assertEquals(plan.blocks().get(16).config().integrator(),RtExecutionOptions.Integrator.WAVEFRONT);
        assertEquals(plan.blocks().get(17).config().integrator(),RtExecutionOptions.Integrator.ITERATIVE);
    }
    @Test void worldComparisonIncludesVanillaWorldInsteadOfComparingEqualTransportBatches(){
        var blocks=new ArrayList<RtBenchmarkResults.Block>();
        for(var p:RtBenchmarkPlan.frame(true).blocks().subList(0,8)){
            var c=p.config();var state=new RtBenchmarkState(320,180,1,true,false,true,true,true,true,c.visibility(),c.queue(),c.omm(),c.ser(),true,true,12,50,20,1000,c.direct(),c.sceneUpdate(),c.realtimePolicy(),c.shader(),c.shadow(),c.world(),c.integrator());
            blocks.add(new RtBenchmarkResults.Block(p,0,100,state,true,List.of(),Map.of("vulkan_rt_batch_fixed",new RtBenchmarkResults.Timing(100,5.,5.),"vulkan_world_total",new RtBenchmarkResults.Timing(100,p.candidate()?6.:10.,10.)),new double[]{1,.6,.4,.2,.1,.05},.1,256,0,0));
        }var result=RtBenchmarkResults.compare("world_takeover",blocks);assertEquals("candidate_faster",result.verdict());assertEquals(40,result.improvementPercent());
    }
    @Test void wallClockIsExportedSeparatelyAndCannotBecomeAGpuTimestamp(){
        var samples=List.of(new PassMetrics.Sample(1,"wall",1,1,2_000_000L,null,1,0,1,1),new PassMetrics.Sample(2,"wall",1,1,100_000_000L,null,2,0,1,1));
        assertTrue(RtBenchmarkResults.timings(samples,Set.of()).isEmpty());
        assertEquals(2,RtBenchmarkResults.cpuTimings(samples,Set.of(2L)).get("wall").medianMs());
    }
    @Test void exclusiveRequestedButVanillaExecutedMustFailReadiness(){
        var c=RtBenchmarkPlan.frame(true).blocks().get(1).config();
        var actual=new RtBenchmarkState(320,180,1,true,false,true,true,true,true,c.visibility(),c.queue(),c.omm(),c.ser(),true,true,12,50,20,1000,c.direct(),c.sceneUpdate(),c.realtimePolicy(),c.shader(),c.shadow(),RtExecutionOptions.World.COMPOSITE,c.integrator());
        assertFalse(actual.matches(c));
    }
}
