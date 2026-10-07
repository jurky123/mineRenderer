package com.voxellight.rt;

import java.util.*;

/** Two repeated ABBA rounds per independent execution control; all changes are temporary. */
public final class RtBenchmarkPlan {
    public record Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct,RtExecutionOptions.SceneUpdate sceneUpdate,RtExecutionOptions.Realtime realtimePolicy,RtExecutionOptions.Shader shader){
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct,RtExecutionOptions.SceneUpdate sceneUpdate,RtExecutionOptions.Realtime realtimePolicy){this(visibility,queue,omm,ser,direct,sceneUpdate,realtimePolicy,RtExecutionOptions.Shader.CLEAN);}
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct,RtExecutionOptions.SceneUpdate sceneUpdate){this(visibility,queue,omm,ser,direct,sceneUpdate,RtExecutionOptions.Realtime.FULL);}
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,RtExecutionOptions.Direct direct){this(visibility,queue,omm,ser,direct,RtExecutionOptions.SceneUpdate.OPTIMIZED);}
        public Config(RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser){this(visibility,queue,omm,ser,RtExecutionOptions.Direct.LEGACY);}
        public static Config current(){return new Config(RtExecutionOptions.visibility(),RtExecutionOptions.queue(),RtExecutionOptions.omm(),RtExecutionOptions.ser(),RtExecutionOptions.direct(),RtExecutionOptions.sceneUpdate(),RtExecutionOptions.realtime(),RtExecutionOptions.shader());}
        public void apply(){RtExecutionOptions.visibility(visibility);RtExecutionOptions.queue(queue);RtExecutionOptions.omm(omm);RtExecutionOptions.ser(ser);RtExecutionOptions.direct(direct);RtExecutionOptions.sceneUpdate(sceneUpdate);RtExecutionOptions.realtime(realtimePolicy);RtExecutionOptions.shader(shader);}
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
    private static Config policy(boolean query,RtExecutionOptions.Realtime mode,RtExecutionOptions.Queue queue){return new Config(query?RtExecutionOptions.Visibility.QUERY:RtExecutionOptions.Visibility.TRACE,queue,false,false,RtExecutionOptions.Direct.RIS,RtExecutionOptions.SceneUpdate.OPTIMIZED,mode);}
    public static Plan shader(boolean query){var blocks=new ArrayList<Block>();var base=policy(query,RtExecutionOptions.Realtime.FULL,RtExecutionOptions.Queue.FIXED);
        pair(blocks,"shader_clean_full",new Config(base.visibility(),base.queue(),false,false,base.direct(),base.sceneUpdate(),base.realtimePolicy(),RtExecutionOptions.Shader.RUNTIME),base);return new Plan(List.copyOf(blocks),Map.of());}
    public static Plan queues(boolean query,boolean supported){var blocks=new ArrayList<Block>();if(!supported)return new Plan(List.of(),Map.of("queues","indirect tracing unavailable"));
        for(var mode:new RtExecutionOptions.Realtime[]{RtExecutionOptions.Realtime.FULL,RtExecutionOptions.Realtime.SPARSE,RtExecutionOptions.Realtime.CACHE_SPARSE})for(var queue:new RtExecutionOptions.Queue[]{RtExecutionOptions.Queue.COMPACT,RtExecutionOptions.Queue.HYBRID})pair(blocks,"realtime_queue_"+mode.name().toLowerCase(Locale.ROOT)+"_"+queue.name().toLowerCase(Locale.ROOT),policy(query,mode,RtExecutionOptions.Queue.FIXED),policy(query,mode,queue));return new Plan(List.copyOf(blocks),Map.of());}
    public static Plan hot(boolean query,boolean supported){var blocks=new ArrayList<Block>(shader(query).blocks());var queues=queues(query,supported);blocks.addAll(queues.blocks());return new Plan(List.copyOf(blocks),queues.skipped());}
    public static Plan realtime(boolean query){return realtime(query,true);}
    public static Plan realtime(boolean query,boolean compactSupported){var queues=new EnumMap<RtExecutionOptions.Realtime,RtExecutionOptions.Queue>(RtExecutionOptions.Realtime.class);
        var selected=(!compactSupported||RtExecutionOptions.queue()==RtExecutionOptions.Queue.AUTO)?RtExecutionOptions.Queue.FIXED:RtExecutionOptions.queue();for(var mode:RtExecutionOptions.Realtime.values())queues.put(mode,selected);
        return realtime(query,queues,true);}
    public static Plan realtime(boolean query,Map<RtExecutionOptions.Realtime,RtExecutionOptions.Queue> queues,boolean includeCacheOnly){var blocks=new ArrayList<Block>();
        for(var mode:RtExecutionOptions.Realtime.values())if(mode!=RtExecutionOptions.Realtime.FULL&&(includeCacheOnly||mode!=RtExecutionOptions.Realtime.CACHE))pair(blocks,"realtime_"+mode.name().toLowerCase(Locale.ROOT),policy(query,RtExecutionOptions.Realtime.FULL,queues.getOrDefault(RtExecutionOptions.Realtime.FULL,RtExecutionOptions.Queue.FIXED)),policy(query,mode,queues.getOrDefault(mode,RtExecutionOptions.Queue.FIXED)));return new Plan(List.copyOf(blocks),Map.of());}
    /** A queue is accepted only when both ABBA rounds beat repeat variation. Otherwise retain FIXED. */
    public static Map<RtExecutionOptions.Realtime,RtExecutionOptions.Queue> selectQueues(List<com.voxellight.debug.RtBenchmarkResults.Comparison> comparisons){
        var selected=new EnumMap<RtExecutionOptions.Realtime,RtExecutionOptions.Queue>(RtExecutionOptions.Realtime.class);
        for(var mode:new RtExecutionOptions.Realtime[]{RtExecutionOptions.Realtime.FULL,RtExecutionOptions.Realtime.SPARSE,RtExecutionOptions.Realtime.CACHE_SPARSE}){
            var best=RtExecutionOptions.Queue.FIXED;double gain=0;
            for(var queue:new RtExecutionOptions.Queue[]{RtExecutionOptions.Queue.COMPACT,RtExecutionOptions.Queue.HYBRID}){
                String name="realtime_queue_"+mode.name().toLowerCase(Locale.ROOT)+"_"+queue.name().toLowerCase(Locale.ROOT);
                for(var result:comparisons)if(result.name().equals(name)&&result.verdict().equals("candidate_faster")&&result.improvementPercent()!=null&&result.improvementPercent()>gain){gain=result.improvementPercent();best=queue;}
            }
            selected.put(mode,best);
        }
        return Collections.unmodifiableMap(selected);
    }
    private static void pair(List<Block> blocks,String name,Config base,Config candidate){for(int round=0;round<2;round++)for(int position=0;position<4;position++){boolean b=position==1||position==2;blocks.add(new Block(name,round,position,b,b?candidate:base));}}
}
