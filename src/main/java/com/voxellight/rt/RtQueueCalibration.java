package com.voxellight.rt;

import com.voxellight.debug.PassMetrics;
import java.util.*;

/** Delayed timestamps joined to real alive curves. Never learns from CPU time or unmatched frames. */
public final class RtQueueCalibration {
    private record Curve(int width,int height,int spp,long scene,int bucket){}
    private record Key(int width,int height,int spp,int bucket){}
    private final LinkedHashMap<Long,Curve> curves=new LinkedHashMap<>();
    private final LinkedHashMap<Long,PassMetrics.Sample> timings=new LinkedHashMap<>();
    private record Costs(List<Long> fixed,List<Long> compact){}
    private final LinkedHashMap<Key,Costs> costs=new LinkedHashMap<>();
    private Curve latest;
    private long joined;
    public void alive(long frame,int width,int height,int spp,long scene,long[] active){
        if(active.length!=6||active[0]<=0)return;
        long sum=0;for(int i=1;i<6;i++){if(active[i]<0||active[i]>active[i-1])return;sum+=active[i];}
        var curve=new Curve(width,height,spp,scene,(int)Math.min(9,sum*10/(active[0]*5)));
        curves.put(frame,curve);latest=curve;trim(curves,256);join(frame);
    }
    public void timing(PassMetrics.Sample sample){
        if(!sample.mode().equals("vulkan_rt_batch_fixed")&&!sample.mode().equals("vulkan_rt_batch_compact"))return;
        if(sample.gpuNanos()==null||sample.gpuNanos()<=0)return;
        timings.put(sample.frame(),sample);trim(timings,256);join(sample.frame());
    }
    private void join(long frame){
        var curve=curves.get(frame);var sample=timings.get(frame);if(curve==null||sample==null)return;
        curves.remove(frame);timings.remove(frame);
        if(curve.width!=sample.width()||curve.height!=sample.height()||curve.spp!=sample.spp()||curve.scene!=sample.sceneGeneration())return;
        var key=new Key(curve.width,curve.height,curve.spp,curve.bucket);
        var values=costs.computeIfAbsent(key,k->new Costs(new ArrayList<>(),new ArrayList<>()));
        var list=(sample.mode().endsWith("compact")?values.compact:values.fixed);list.add(sample.gpuNanos());if(list.size()>31)list.removeFirst();joined++;trim(costs,64);
    }
    public boolean compact(long frame,int width,int height,int spp,boolean profiling){
        if(latest==null||latest.width!=width||latest.height!=height||latest.spp!=spp)return profiling&&((frame/8)&1)!=0;
        var values=costs.get(new Key(width,height,spp,latest.bucket));
        if(values!=null&&values.fixed.size()>=6&&values.compact.size()>=6)return median(values.compact)<median(values.fixed)*.97;
        // Counter readback occurs every eighth frame: alternate each sampled frame, not every eighth block.
        return profiling&&((frame/8)&1)!=0;
    }
    private static long median(List<Long> values){var sorted=new ArrayList<>(values);sorted.sort(Long::compare);return sorted.get(sorted.size()/2);}
    private static void trim(LinkedHashMap<?,?> map,int size){while(map.size()>size)map.remove(map.firstEntry().getKey());}
    public String status(){return "aliveBucket="+(latest==null?"unobserved":latest.bucket)+"/matchedGpuSamples="+joined+"/workloads="+costs.size();}
}
