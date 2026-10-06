package com.voxellight.rt;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtLightRuntimeTest {
    @Test void aliasesPreservePositiveSupportAndMatchActualSelectionFrequencies(){
        var alias=RtLightRuntime.alias(new double[]{1,3,16});assertEquals(1,alias.pmf()[0]+alias.pmf()[1]+alias.pmf()[2],1e-6);
        var random=new Random(19);int[] count=new int[3];for(int i=0;i<200000;i++)count[alias.sample(random.nextDouble())]++;
        for(int i=0;i<3;i++)assertEquals(alias.pmf()[i],count[i]/200000.,.004);
        assertThrows(IllegalArgumentException.class,()->RtLightRuntime.alias(new double[]{0,1}));assertThrows(IllegalArgumentException.class,()->RtLightRuntime.alias(new double[]{Double.NaN}));
    }
    @Test void hierarchicalCellAndGlobalMixturePdfNormalizeAndRetainDistantLights(){
        var lights=new ArrayList<RtEmitterTable.Triangle>();for(int i=0;i<20;i++)lights.add(new RtEmitterTable.Triangle(i,0,new float[]{i*32,0,3,1,2,3,0,0,0},0,i+1,1));
        var data=RtLightRuntime.build(lights,0,0,0,new float[]{1,1,1,1,1,1,1,1});assertEquals(20,data.getInt(4));assertEquals(20,data.getInt(8));
        int global=data.getInt(48),grid=data.getInt(56),slots=data.getInt(52);assertEquals(16,slots);
        for(int cell=0;cell<RtLightRuntime.WIDTH*RtLightRuntime.HEIGHT*RtLightRuntime.DEPTH;cell++){
            double sum=0;for(int section=0;section<20;section++){double local=0;for(int i=0;i<slots;i++)if(data.getInt(grid+(cell*slots+i)*16+8)==section)local=data.getFloat(grid+(cell*slots+i)*16+12);
                double pdf=.2*data.getFloat(global+section*16+12)+.8*local;assertTrue(pdf>0);sum+=pdf;}assertEquals(1,sum,2e-6);
        }
        int records=data.getInt(36);for(int i=0;i<20;i++){assertEquals(i,data.getInt(records+i*64+48));assertEquals(1,data.getFloat(records+i*64+12));}
    }
    @Test void sectionLocalHierarchyKeepsAreaAndPointIdsAndExactConditionalMasses(){
        var points=List.of(new RtEmitterTable.Triangle(9,0,new float[]{1,1,1,2,3,4,0,0,0},0,3,1),new RtEmitterTable.Triangle(2,0,new float[]{0,0,1,1,0,1,0,1,1},.5f,1,0));
        var data=RtLightRuntime.build(points,0,0,0,new float[]{1,1,1,1,1,1,1,1});assertEquals(1,data.getInt(8));int r=data.getInt(36);
        assertEquals(2,data.getInt(r+48));assertEquals(.25,data.getFloat(r+12),1e-6);assertEquals(9,data.getInt(r+64+48));assertEquals(.75,data.getFloat(r+64+12),1e-6);
        var packed=RtEmitterTable.proposals(points,0,0,0);var restored=RtLightRuntime.sources(packed.stochastic(),packed.flames());assertEquals(List.of(2,9),restored.stream().map(RtEmitterTable.Triangle::index).toList());
        assertArrayEquals(points.getFirst().vertices(),restored.get(1).vertices());
    }
    @Test void directOnlyBenchmarkChangesOnlyTheEstimatorAndRestoresIt(){
        var original=RtBenchmarkPlan.Config.current();try{var plan=RtBenchmarkPlan.direct(true);assertEquals(8,plan.blocks().size());for(var b:plan.blocks()){
            assertEquals(RtExecutionOptions.Visibility.QUERY,b.config().visibility());assertEquals(RtExecutionOptions.Queue.FIXED,b.config().queue());assertFalse(b.config().omm());assertFalse(b.config().ser());
            assertEquals(b.candidate()?RtExecutionOptions.Direct.RIS:RtExecutionOptions.Direct.LEGACY,b.config().direct());b.config().apply();assertEquals(b.config(),RtBenchmarkPlan.Config.current());
        }}finally{original.apply();}assertEquals(original,RtBenchmarkPlan.Config.current());
    }
    @Test void adaptiveFixedPointSumFitsEvenWhenAllMaximumCapacityEventsShareOneBucket(){long paths=(1L<<30)/400;assertTrue(paths*6*2*RtLightRuntime.ADAPTIVE_SCALE<(1L<<32));}
    @Test void blasOnlyBenchmarkKeepsDirectTransportFixedAndRestoresScenePolicy(){
        var original=RtBenchmarkPlan.Config.current();try{var plan=RtBenchmarkPlan.blas(true);assertEquals(8,plan.blocks().size());for(var b:plan.blocks()){assertEquals(RtExecutionOptions.Direct.RIS,b.config().direct());assertEquals(RtExecutionOptions.Visibility.QUERY,b.config().visibility());assertEquals(RtExecutionOptions.Queue.FIXED,b.config().queue());assertEquals(b.candidate()?RtExecutionOptions.SceneUpdate.OPTIMIZED:RtExecutionOptions.SceneUpdate.LEGACY,b.config().sceneUpdate());b.config().apply();assertEquals(b.config(),RtBenchmarkPlan.Config.current());}}finally{original.apply();}assertEquals(original,RtBenchmarkPlan.Config.current());
    }
    @Test void emptyRuntimeHasAValidHeaderAndNoAliases(){var data=RtLightRuntime.build(List.of(),0,0,0,new float[]{1,1,1,1,1,1,1,1});assertEquals(RtLightRuntime.HEADER,data.remaining());assertEquals(0,data.getInt(4));assertEquals(0,data.getInt(52));}
}
