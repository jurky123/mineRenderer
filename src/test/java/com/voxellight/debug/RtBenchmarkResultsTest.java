package com.voxellight.debug;

import com.voxellight.rt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RtBenchmarkResultsTest {
    private List<RtBenchmarkResults.Block> blocks(double... times){
        var plan=RtBenchmarkPlan.create(true,true,true,true).blocks();
        var out=new ArrayList<RtBenchmarkResults.Block>();
        for(int i=0;i<8;i++){
            var p=plan.get(i);var c=p.config();
            var state=new RtBenchmarkState(320,180,1,true,false,true,true,true,true,c.visibility(),c.queue(),c.omm(),c.ser(),true,true,12,50+i,20,1000);
            out.add(new RtBenchmarkResults.Block(p,100+i*100,199+i*100,state,true,List.of(),Map.of("vulkan_rt_batch_fixed",new RtBenchmarkResults.Timing(100,times[i],times[i])),new double[]{1,.6,.4,.2,.1,.05},.1,256,0,0));
        }
        return out;
    }
    private String verdict(double... times){return RtBenchmarkResults.compare("visibility",blocks(times)).verdict();}
    @Test void repeatableGainAndRegression(){
        assertEquals("candidate_faster",verdict(10,8,8,10,10,8,8,10));
        assertEquals("candidate_slower",verdict(10,12,12,10,10,12,12,10));
    }
    @Test void SmallDifferencesAndBaselineNoiseDoNotBecomeWins(){
        assertEquals("within_variation",verdict(10,9.9,9.9,10,10,9.9,9.9,10));
        assertEquals("within_variation",verdict(8,9,9,12,8,9,9,12));
    }
    @Test void ConflictingRoundsAreNotReliableGain(){assertEquals("inconsistent",verdict(10,5,5,10,10,12,12,10));}
    @Test void IncompleteAndReplayFailures(){
        var b=blocks(10,8,8,10,10,8,8,10);
        assertEquals("incomplete",RtBenchmarkResults.compare("visibility",b.subList(0,4)).verdict());
        var old=b.getFirst();b.set(0,new RtBenchmarkResults.Block(old.plan(),old.firstFrame(),old.lastFrame(),old.state(),true,List.of(),old.timings(),old.aliveFraction(),.1,256,1,0));
        assertEquals("correctness_failed",RtBenchmarkResults.compare("visibility",b).verdict());
    }
    @Test void WorkloadDriftAndMissingCountersRejectComparison(){
        var b=blocks(10,8,8,10,10,8,8,10);b.get(2).aliveFraction()[2]=.6;
        assertEquals("not_comparable",RtBenchmarkResults.compare("visibility",b).verdict());
        b=blocks(10,8,8,10,10,8,8,10);var old=b.get(2);var s=old.state();
        var changed=new RtBenchmarkState(640,180,1,true,false,true,true,true,true,s.visibility(),s.queue(),false,false,true,true,12,52,20,1000);
        b.set(2,new RtBenchmarkResults.Block(old.plan(),old.firstFrame(),old.lastFrame(),changed,true,List.of(),old.timings(),new double[0],.1,256,0,0));
        assertEquals("not_comparable",RtBenchmarkResults.compare("visibility",b).verdict());
    }
    @Test void RequestedControlsMustActuallyExecute(){
        var b=blocks(10,8,8,10,10,8,8,10);var old=b.get(1);
        b.set(1,new RtBenchmarkResults.Block(old.plan(),old.firstFrame(),old.lastFrame(),b.getFirst().state(),true,List.of(),old.timings(),old.aliveFraction(),.1,256,0,0));
        assertEquals("not_comparable",RtBenchmarkResults.compare("visibility",b).verdict());
    }
    @Test void TimingsIgnoreUnavailableQueriesAndComputeMedian(){
        assertEquals(2.5,RtBenchmarkResults.timing(List.of(0L,-1L,1_000_000L,2_000_000L,3_000_000L,4_000_000L)).medianMs());
        assertNull(RtBenchmarkResults.timing(List.of(0L)).medianMs());
    }
    @Test void PlanUsesIndependentABBAControlsAndSkipsUnsupportedFeatures(){
        var minimal=RtBenchmarkPlan.create(false,false,false,false);assertEquals(8,minimal.blocks().size());assertEquals(4,minimal.skipped().size());
        var full=RtBenchmarkPlan.create(true,true,true,true);assertEquals(40,full.blocks().size());
        for(int i=0;i<40;i++){var p=full.blocks().get(i);assertEquals(i%8/4,p.round());assertEquals(i%4,p.position());assertEquals(i%4==1||i%4==2,p.candidate());}
        assertThrows(UnsupportedOperationException.class,()->full.blocks().clear());
    }
    @Test void DelayedFramesBelongOnlyToTheirSubmissionWindow(){
        assertFalse(RtBenchmarkResults.ownsFrame(20,Long.MAX_VALUE,Long.MAX_VALUE));
        assertFalse(RtBenchmarkResults.ownsFrame(99,100,199));
        assertTrue(RtBenchmarkResults.ownsFrame(100,100,199));
        assertTrue(RtBenchmarkResults.ownsFrame(199,100,199));
        assertFalse(RtBenchmarkResults.ownsFrame(200,100,199));
    }
    @Test void TemporaryControlsCanBeRestoredExactly(){
        var original=RtBenchmarkPlan.Config.current();
        try{for(var block:RtBenchmarkPlan.create(true,true,true,true).blocks()){block.config().apply();assertEquals(block.config(),RtBenchmarkPlan.Config.current());}}
        finally{original.apply();}assertEquals(original,RtBenchmarkPlan.Config.current());
    }
}
