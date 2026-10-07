package com.voxellight.rt;

import com.voxellight.debug.RtBenchmarkResults;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RtHotShaderTest {
    @Test void stageMatrixSpecializesProductionAndReferenceButKeepsRuntimeControl(){
        var original=RtBenchmarkPlan.Config.current();try{
            for(var policy:RtExecutionOptions.Realtime.values())for(var direct:RtExecutionOptions.Direct.values())for(boolean query:new boolean[]{false,true})for(boolean ser:new boolean[]{false,true}){
                RtExecutionOptions.realtime(policy);RtExecutionOptions.direct(direct);RtExecutionOptions.shader(RtExecutionOptions.Shader.CLEAN);
                String suffix=(direct==RtExecutionOptions.Direct.LEGACY?"_legacy":"")+(query?"_query":"")+(ser?"_ser":"");
                assertEquals("material_primary_"+(policy==RtExecutionOptions.Realtime.FULL?"full":"realtime")+suffix,RtExecutionOptions.stage("material_primary",true,query,ser));
                assertEquals("material_indirect_full"+suffix,RtExecutionOptions.stage("material_indirect",false,query,ser));
                assertEquals("material_resolve_full",RtExecutionOptions.stage("material_resolve",false,query,ser));
                RtExecutionOptions.shader(RtExecutionOptions.Shader.RUNTIME);
                assertEquals("material_primary"+(query?"_query":"")+(ser?"_ser":""),RtExecutionOptions.stage("material_primary",true,query,ser));
                assertEquals("material_resolve",RtExecutionOptions.stage("material_resolve",true,query,ser));
            }
        }finally{original.apply();}
    }
    @Test void footprintABChangesOnlyShaderFamilyAndRestoresAllControls(){
        var original=RtBenchmarkPlan.Config.current();try{
            var plan=RtBenchmarkPlan.shader(true);assertEquals(8,plan.blocks().size());
            for(var block:plan.blocks()){
                var c=block.config();assertEquals(RtExecutionOptions.Realtime.FULL,c.realtimePolicy());assertEquals(RtExecutionOptions.Queue.FIXED,c.queue());assertEquals(RtExecutionOptions.Direct.RIS,c.direct());
                assertEquals(block.candidate()?RtExecutionOptions.Shader.CLEAN:RtExecutionOptions.Shader.RUNTIME,c.shader());
                c.apply();assertEquals(c,RtBenchmarkPlan.Config.current());
            }
        }finally{original.apply();}assertEquals(original,RtBenchmarkPlan.Config.current());
    }
    @Test void queueSweepHoldsAlgorithmsAndSelectsOnlyRepeatableWinners(){
        var plan=RtBenchmarkPlan.queues(true,true);assertEquals(48,plan.blocks().size());assertEquals(56,RtBenchmarkPlan.hot(true,true).blocks().size());assertEquals(8,RtBenchmarkPlan.hot(true,false).blocks().size());
        for(int i=0;i<48;i+=8){var blocks=plan.blocks().subList(i,i+8);var policy=blocks.getFirst().config().realtimePolicy();for(var block:blocks){assertEquals(policy,block.config().realtimePolicy());if(!block.candidate())assertEquals(RtExecutionOptions.Queue.FIXED,block.config().queue());}}
        var selected=RtBenchmarkPlan.selectQueues(List.of(
            new RtBenchmarkResults.Comparison("realtime_queue_full_compact","candidate_slower",-8.,2.,List.of(),List.of()),
            new RtBenchmarkResults.Comparison("realtime_queue_full_hybrid","within_variation",2.7,3.,List.of(),List.of()),
            new RtBenchmarkResults.Comparison("realtime_queue_sparse_compact","candidate_faster",12.,2.,List.of(),List.of()),
            new RtBenchmarkResults.Comparison("realtime_queue_sparse_hybrid","candidate_faster",15.,2.,List.of(),List.of()),
            new RtBenchmarkResults.Comparison("realtime_queue_cache_sparse_compact","inconsistent",10.,2.,List.of(),List.of())));
        assertEquals(RtExecutionOptions.Queue.FIXED,selected.get(RtExecutionOptions.Realtime.FULL));assertEquals(RtExecutionOptions.Queue.HYBRID,selected.get(RtExecutionOptions.Realtime.SPARSE));assertEquals(RtExecutionOptions.Queue.FIXED,selected.get(RtExecutionOptions.Realtime.CACHE_SPARSE));
        var finalPlan=RtBenchmarkPlan.realtime(true,selected,false);assertEquals(16,finalPlan.blocks().size());for(var block:finalPlan.blocks())assertEquals(selected.get(block.config().realtimePolicy()),block.config().queue());
    }
    @Test void standaloneRealtimeNeverAssumesCompactAndSnapshotsSurviveInputMutation(){
        var original=RtBenchmarkPlan.Config.current();try{RtExecutionOptions.queue(RtExecutionOptions.Queue.AUTO);for(var block:RtBenchmarkPlan.realtime(true).blocks())assertEquals(RtExecutionOptions.Queue.FIXED,block.config().queue());RtExecutionOptions.queue(RtExecutionOptions.Queue.COMPACT);for(var block:RtBenchmarkPlan.realtime(true,false).blocks())assertEquals(RtExecutionOptions.Queue.FIXED,block.config().queue());}finally{original.apply();}
        var inputs=new RtBenchmarkInputs();float[] live={1,2,3};var frozen=inputs.freeze("sun",live);live[0]=8;frozen[1]=9;
        assertArrayEquals(new float[]{1,2,3},inputs.freeze("sun",new float[]{4,5,6}));inputs.snapshot().get("sun")[0]=7;assertArrayEquals(new float[]{1,2,3},inputs.snapshot().get("sun"));
        assertThrows(IllegalArgumentException.class,()->inputs.freeze("sun",new float[]{1}));
    }
    @Test void productionAliveSamplingStopsAndResetsWithoutDiagnosticCounters(){
        var c=new RtQueueCalibration();long[] curve={25680,23200,13200,534,40,10};
        assertFalse(c.sampleAlive(1,214,120,1));c.alive(1,214,120,1,7,new long[]{0,0,0,0,0,0});
        for(int i=1;i<=8;i++){assertTrue(c.sampleAlive(i*8,214,120,1));c.alive(i*8,214,120,1,7,curve);}
        assertFalse(c.sampleAlive(72,214,120,1));assertEquals(4,c.hybridMask(214,120,1));assertTrue(c.status().contains("frozen=true"));c.alive(73,214,120,1,7,new long[]{25680,12000,500,0,0,0});assertEquals(4,c.hybridMask(214,120,1));
        assertTrue(c.sampleAlive(80,428,240,1));assertEquals(0,c.hybridMask(428,240,1));
    }
}
