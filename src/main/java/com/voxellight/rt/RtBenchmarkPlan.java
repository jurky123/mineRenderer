package com.voxellight.rt;

import java.util.*;

/** Two repeated ABBA rounds per independent execution control; all changes are temporary. */
public final class RtBenchmarkPlan {
    public record Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct,RtExecutionOptions.SceneUpdate sceneUpdate,RtExecutionOptions.Realtime realtimePolicy){
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct,RtExecutionOptions.SceneUpdate sceneUpdate){this(visibility,queue,omm,ser,direct,sceneUpdate,RtExecutionOptions.Realtime.FULL);}
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct){this(visibility,queue,omm,ser,direct,RtExecutionOptions.SceneUpdate.OPTIMIZED);}
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser){this(visibility,queue,omm,ser,RtExecutionOptions.Direct.LEGACY);}
        public static Config current(){return new Config(RtExecutionOptions.visibility(),RtExecutionOptions.queue(),RtExecutionOptions.omm(),RtExecutionOptions.ser(),RtExecutionOptions.direct(),RtExecutionOptions.sceneUpdate(),RtExecutionOptions.realtime());}
        public void apply(){RtExecutionOptions.visibility(visibility);RtExecutionOptions.queue(queue);RtExecutionOptions.omm(omm);RtExecutionOptions.ser(ser);RtExecutionOptions.direct(direct);RtExecutionOptions.sceneUpdate(sceneUpdate);RtExecutionOptions.realtime(realtimePolicy);}
    }
    public record Block(String comparison,int round,int position,boolean candidate,Config config){}
    public record Plan(List<Block> blocks,Map<String,String> skipped){}
    private RtBenchmarkPlan(){}
    public static Plan blas(boolean query){var blocks=new ArrayList<Block>();blasPair(blocks,query);return new Plan(List.copyOf(blocks),Map.of());}
    private static void blasPair(List<Block> blocks,boolean query){var visibility=query?RtExecutionOptions.Visibility.QUERY:RtExecutionOptions.Visibility.LEGACY;pair(blocks,"blas",new Config(visibility,RtExecutionOptions.Queue.FIXED,false,false,RtExecutionOptions.Direct.RIS,RtExecutionOptions.SceneUpdate.LEGACY),new Config(visibility,RtExecutionOptions.Queue.FIXED,false,false,RtExecutionOptions.Direct.RIS,RtExecutionOptions.SceneUpdate.OPTIMIZED));}
    public static Plan direct(boolean query){var blocks=new ArrayList<Block>();directPair(blocks,query);return new Plan(List.copyOf(blocks),Map.of());}
    private static void directPair(List<Block> blocks,boolean query){var visibility=query?RtExecutionOptions.Visibility.QUERY:RtExecutionOptions.Visibility.LEGACY;pair(blocks,"direct_lighting",new Config(visibility,RtExecutionOptions.Queue.FIXED,false,false,RtExecutionOptions.Direct.LEGACY),new Config(visibility,RtExecutionOptions.Queue.FIXED,false,false,RtExecutionOptions.Direct.RIS));}
    public static Plan create(boolean query,boolean compact,boolean omm,boolean ser){
        var blocks=new ArrayList<Block>();var skipped=new LinkedHashMap<String,String>();
        var trace=new Config(RtExecutionOptions.Visibility.TRACE,RtExecutionOptions.Queue.FIXED,false,false);
        pair(blocks,"visibility",new Config(RtExecutionOptions.Visibility.LEGACY,RtExecutionOptions.Queue.FIXED,false,false),trace);
        if(query)pair(blocks,"ray_query",trace,new Config(RtExecutionOptions.Visibility.QUERY,RtExecutionOptions.Queue.FIXED,false,false));else skipped.put("ray_query","device ray query unavailable");
        if(query)pair(blocks,"query_vs_legacy",new Config(RtExecutionOptions.Visibility.LEGACY,RtExecutionOptions.Queue.FIXED,false,false),new Config(RtExecutionOptions.Visibility.QUERY,RtExecutionOptions.Queue.FIXED,false,false));else skipped.put("query_vs_legacy","device ray query unavailable");
        if(compact)pair(blocks,"queue",trace,new Config(RtExecutionOptions.Visibility.TRACE,RtExecutionOptions.Queue.COMPACT,false,false));else skipped.put("queue","indirect tracing/queue capacity unavailable");
        if(compact)pair(blocks,"hybrid_queue",trace,new Config(RtExecutionOptions.Visibility.TRACE,RtExecutionOptions.Queue.HYBRID,false,false));else skipped.put("hybrid_queue","indirect tracing/queue capacity unavailable");
        if(omm)pair(blocks,"omm",trace,new Config(RtExecutionOptions.Visibility.TRACE,RtExecutionOptions.Queue.FIXED,true,false));else skipped.put("omm","device OMM/static opacity coverage unavailable");
        if(ser)pair(blocks,"ser",trace,new Config(RtExecutionOptions.Visibility.TRACE,RtExecutionOptions.Queue.FIXED,false,true));else skipped.put("ser","device invocation reorder unavailable");
        directPair(blocks,query);blasPair(blocks,query);return new Plan(List.copyOf(blocks),Collections.unmodifiableMap(skipped));
    }
    public static Plan realtime(boolean query){var blocks=new ArrayList<Block>();var v=query?RtExecutionOptions.Visibility.QUERY:RtExecutionOptions.Visibility.TRACE;
        for(var mode:new RtExecutionOptions.Realtime[]{RtExecutionOptions.Realtime.CACHE,RtExecutionOptions.Realtime.SPARSE,RtExecutionOptions.Realtime.CACHE_SPARSE})pair(blocks,"realtime_"+mode.name().toLowerCase(java.util.Locale.ROOT),new Config(v,RtExecutionOptions.Queue.COMPACT,false,false,RtExecutionOptions.Direct.RIS,RtExecutionOptions.SceneUpdate.OPTIMIZED,RtExecutionOptions.Realtime.FULL),new Config(v,RtExecutionOptions.Queue.COMPACT,false,false,RtExecutionOptions.Direct.RIS,RtExecutionOptions.SceneUpdate.OPTIMIZED,mode));return new Plan(List.copyOf(blocks),Map.of());}
    private static void pair(List<Block> blocks,String name,Config base,Config candidate){for(int round=0;round<2;round++)for(int position=0;position<4;position++){boolean b=position==1||position==2;blocks.add(new Block(name,round,position,b,b?candidate:base));}}
}
