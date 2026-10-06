package com.voxellight.debug;

import java.nio.file.*;
import java.io.IOException;
import java.util.LinkedHashMap;

/** Bounded delayed GPU ray counters; frame IDs refer to submission, not readback completion. */
public final class RtWorkMetrics {
    private record Sample(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit){}
    private final LinkedHashMap<Long,Sample> samples=new LinkedHashMap<>();
    public void record(long frame,int width,int height,int spp,long scene,long[] active,long shadow,long anyHit){
        if(active.length!=6)throw new IllegalArgumentException("six vertices");
        samples.put(frame,new Sample(frame,width,height,spp,scene,active.clone(),shadow,anyHit));
        while(samples.size()>1800)samples.remove(samples.firstEntry().getKey());
    }
    public void clear(){samples.clear();}
    public void export(Path path)throws IOException{
        try(var writer=Files.newBufferedWriter(path)){
            writer.write("frame,width,height,spp,scene_generation,active_0,active_1,active_2,active_3,active_4,active_5,shadow_rays,any_hit\n");
            for(var sample:samples.values()){
                writer.write(sample.frame+","+sample.width+","+sample.height+","+sample.spp+","+sample.scene);
                for(long count:sample.active)writer.write(","+count);
                writer.write(","+sample.shadow+","+sample.anyHit+"\n");
            }
        }
    }
}
