package com.voxellight.rt;

import java.util.*;

/** Suite-owned renderer inputs; copies protect snapshots across context rebuilds. */
public final class RtBenchmarkInputs {
    private final Map<String,float[]> values=new LinkedHashMap<>();
    public float[] freeze(String key,float[] live){
        var saved=values.computeIfAbsent(key,k->live.clone());
        if(saved.length!=live.length)throw new IllegalArgumentException("benchmark input shape changed: "+key);
        return saved.clone();
    }
    public Map<String,float[]> snapshot(){var copy=new LinkedHashMap<String,float[]>();values.forEach((key,value)->copy.put(key,value.clone()));return Collections.unmodifiableMap(copy);}
}
