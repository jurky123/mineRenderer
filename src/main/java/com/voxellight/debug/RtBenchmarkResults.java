package com.voxellight.debug;

import com.voxellight.rt.*;
import java.util.*;

/** Block-level comparisons: correlated GPU frames are not treated as independent confidence samples. */
public final class RtBenchmarkResults {
    public record Timing(int samples,Double medianMs,Double p95Ms){}
    public record Block(RtBenchmarkPlan.Block plan,long firstFrame,long lastFrame,RtBenchmarkState state,
            boolean valid,List<String> reasons,Map<String,Timing> timings,double[] aliveFraction,
            Double anyHitPerPrimary,long replaySamples,long mismatches,long skippedQueries){}
    public record Comparison(String name,String verdict,Double improvementPercent,Double repeatVariationPercent,List<Double> roundImprovementsPercent,List<String> reasons){}
    private RtBenchmarkResults(){}
    public static boolean ownsFrame(long frame,long first,long last){return first!=Long.MAX_VALUE&&frame>=first&&frame<=last;}
    public static Timing timing(List<Long> ns){
        var values=ns.stream().filter(n->n>0).sorted().toList();if(values.isEmpty())return new Timing(0,null,null);
        int n=values.size();double median=n%2==1?values.get(n/2):(values.get(n/2-1)/2.0+values.get(n/2)/2.0);
        return new Timing(n,median/1e6,values.get((n-1)*95/100)/1e6);
    }
    public static Map<String,Timing> timings(List<PassMetrics.Sample> samples,Set<Long> counterFrames){
        var grouped=new LinkedHashMap<String,List<Long>>();
        for(var sample:samples)if(sample.gpuNanos()!=null&&!counterFrames.contains(sample.frame()))grouped.computeIfAbsent(sample.mode(),ignored->new ArrayList<>()).add(sample.gpuNanos());
        var result=new LinkedHashMap<String,Timing>();grouped.forEach((name,values)->result.put(name,timing(values)));return result;
    }
    private static Timing batch(Block block){return block.timings.get("vulkan_rt_batch_"+(block.plan.config().queue()==RtExecutionOptions.Queue.HYBRID?"hybrid":block.plan.config().queue()==RtExecutionOptions.Queue.COMPACT?"compact":"fixed"));}
    public static Comparison compare(String name,List<Block> all){
        var blocks=all.stream().filter(b->b.plan.comparison().equals(name)).sorted(Comparator.comparingInt((Block b)->b.plan.round()).thenComparingInt(b->b.plan.position())).toList();
        var reasons=new ArrayList<String>();
        if(blocks.stream().anyMatch(b->b.mismatches>0))return new Comparison(name,"correctness_failed",null,null,List.of(),List.of("TraceRay / Ray Query replay mismatches"));
        if(blocks.size()!=8)return new Comparison(name,"incomplete",null,null,List.of(),List.of("requires two complete ABBA rounds"));
        for(int i=0;i<8;i++){var b=blocks.get(i).plan;if(b.round()!=i/4||b.position()!=i%4||b.candidate()!=(i%4==1||i%4==2))reasons.add("invalid ABBA order");}
        if((name.equals("ray_query")||name.equals("query_vs_legacy"))&&blocks.stream().anyMatch(b->b.plan.config().visibility()!=RtExecutionOptions.Visibility.LEGACY&&b.replaySamples==0))reasons.add("missing visibility correctness replay");
        var reference=blocks.getFirst().state;
        for(var block:blocks){
            reasons.addAll(block.reasons);
            if(block.state==null||!block.state.matches(block.plan.config()))reasons.add("actual controls do not match requested variant");
            if(!block.valid)reasons.add("invalid block "+block.plan.round()+"/"+block.plan.position());
            if(reference==null||block.state==null||!reference.sameWorkload(block.state))reasons.add("resolution/spp/mode/terrain working set changed");
            var time=batch(block);if(time==null||time.samples<30||time.medianMs==null)reasons.add("insufficient GPU batch timestamps");
        }
        // Sampled alive curves must exist; throughput timing alone does not prove matched ray work.
        if(blocks.stream().anyMatch(b->b.aliveFraction.length!=6))reasons.add("missing alive-path counters");
        else for(int i=1;i<6;i++){double min=1,max=0;for(var b:blocks){min=Math.min(min,b.aliveFraction[i]);max=Math.max(max,b.aliveFraction[i]);}if(max-min>.05){reasons.add("alive-path fraction drift > 5 percentage points");break;}}
        if(!reasons.isEmpty())return new Comparison(name,"not_comparable",null,null,List.of(),reasons.stream().distinct().toList());
        var gains=new ArrayList<Double>();double noise=0;
        for(int round=0;round<2;round++){
            int i=round*4;double a0=batch(blocks.get(i)).medianMs,a1=batch(blocks.get(i+3)).medianMs,b0=batch(blocks.get(i+1)).medianMs,b1=batch(blocks.get(i+2)).medianMs;
            double a=(a0+a1)/2,b=(b0+b1)/2;gains.add((a-b)/a*100);noise=Math.max(noise,Math.max(Math.abs(a0-a1)/a,Math.abs(b0-b1)/b)*100);
        }
        if(noise>10)return new Comparison(name,"not_comparable",null,noise,List.copyOf(gains),List.of("repeat variation > 10%; clocks/background workload may have changed"));
        double gain=(gains.get(0)+gains.get(1))/2,threshold=Math.max(3,noise);
        String verdict=gains.stream().allMatch(g->g>threshold)?"candidate_faster":gains.stream().allMatch(g->g< -threshold)?"candidate_slower":Math.abs(gain)<=threshold?"within_variation":"inconsistent";
        return new Comparison(name,verdict,gain,noise,List.copyOf(gains),List.of("descriptive ABBA result; not a statistical confidence interval"));
    }
}
