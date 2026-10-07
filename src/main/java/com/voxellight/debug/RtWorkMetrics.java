package com.voxellight.debug;

import java.nio.file.*;
import java.io.IOException;
import java.util.LinkedHashMap;

/** Bounded delayed GPU ray counters; frame IDs refer to submission, not readback completion. */
public final class RtWorkMetrics {
    public record Sample(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit,long opaqueVisibility,long visibilitySamples,long visibilityMismatches,long[][] direct,long[] realtime){
        public Sample(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit,long opaqueVisibility,long visibilitySamples,long visibilityMismatches,long[][] direct){this(frame,width,height,spp,scene,active,shadow,anyHit,opaqueVisibility,visibilitySamples,visibilityMismatches,direct,new long[16]);}
        public Sample(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit,long opaqueVisibility,long visibilitySamples,long visibilityMismatches){this(frame,width,height,spp,scene,active,shadow,anyHit,opaqueVisibility,visibilitySamples,visibilityMismatches,new long[6][4]);}
    }
    private final LinkedHashMap<Long,Sample> samples=new LinkedHashMap<>();
    public void record(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit){
        record(frame,width,height,spp,scene,active,shadow,anyHit,0,0,0);
    }
    public void record(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit,long opaqueVisibility,long visibilitySamples,long visibilityMismatches){
        record(frame,width,height,spp,scene,active,shadow,anyHit,opaqueVisibility,visibilitySamples,visibilityMismatches,new long[6][4]);
    }
    public void record(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit,long opaqueVisibility,long visibilitySamples,long visibilityMismatches,long[][] direct){
        record(frame,width,height,spp,scene,active,shadow,anyHit,opaqueVisibility,visibilitySamples,visibilityMismatches,direct,new long[16]);
    }
    public void record(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit,long opaqueVisibility,long visibilitySamples,long visibilityMismatches,long[][] direct,long[] realtime){
        if(realtime.length!=16)throw new IllegalArgumentException("sixteen realtime counters");
        if(direct.length!=6)throw new IllegalArgumentException("six direct counters");var copied=new long[6][];for(int i=0;i<6;i++){if(direct[i].length!=4)throw new IllegalArgumentException("four counter components");copied[i]=direct[i].clone();}
        if(active.length!=6)throw new IllegalArgumentException("six vertices");
        samples.put(frame,new Sample(frame,width,height,spp,scene,active.clone(),shadow,anyHit,opaqueVisibility,visibilitySamples,visibilityMismatches,copied,realtime.clone()));
        while(samples.size()>1800)samples.remove(samples.firstEntry().getKey());
    }
    public Sample sample(long frame){return samples.get(frame);}
    public void clear(){samples.clear();}
    /** Algorithm coverage is distinct from GPU timing validity; zero denominators stay unavailable. */
    public java.util.Map<String,Object> realtimeSummary(){
        long[] total=new long[16];for(var sample:samples.values())for(int i=0;i<16;i++)total[i]+=sample.realtime[i];
        var result=new LinkedHashMap<String,Object>();result.put("counterSamples",samples.size());result.put("counters",total);
        result.put("fullPathDensity",total[0]==0?null:total[1]/(double)total[0]);result.put("reuseFraction",total[0]==0?null:total[2]/(double)total[0]);
        result.put("cacheHitFraction",total[4]==0?null:total[5]/(double)total[4]);result.put("cacheQueried",total[4]>0);result.put("cacheTerminatedPaths",total[5]>0);
        return result;
    }
    public void export(Path path)throws IOException{
        try(var writer=Files.newBufferedWriter(path)){
            writer.write("frame,width,height,spp,scene_generation,active_0,active_1,active_2,active_3,active_4,active_5,shadow_rays,any_hit,opaque_visibility,visibility_samples,visibility_mismatches");for(int i=0;i<6;i++)writer.write(",surface_hits_"+i+",direct_visibility_"+i+",ris_candidates_"+i+",ris_selected_"+i);for(String name:new String[]{"eligible","full_paths","reused","high_variance","cache_queries","cache_hits","cache_trained","cache_rejected","probes","probe_dropped","medium_protected","sharp_protected","new_exposure","history_rejected","dynamic_protected","history_updates"})writer.write(",rt_"+name);writer.write("\n");
            for(var sample:samples.values()){
                writer.write(sample.frame+","+sample.width+","+sample.height+","+sample.spp+","+sample.scene);
                for(long count:sample.active)writer.write(","+count);
                writer.write(","+sample.shadow+","+sample.anyHit+","+sample.opaqueVisibility+","+sample.visibilitySamples+","+sample.visibilityMismatches);for(var counters:sample.direct)for(long count:counters)writer.write(","+count);for(long count:sample.realtime)writer.write(","+count);writer.write("\n");
            }
        }
    }
}
