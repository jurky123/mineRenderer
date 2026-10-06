package com.voxellight.rt;

/** Pipeline initialization and actual rendered warmup have independent monotonic clocks. */
public final class RtBenchmarkWarmup {
    public enum Status {INITIALIZING,WARMUP,READY,INITIALIZATION_TIMEOUT,STABILITY_TIMEOUT}
    public static final int INITIALIZATION_LIMIT_SECONDS=180;
    private final long started;
    private long readySince=-1,stableSince=-1,signature;
    public RtBenchmarkWarmup(long now){started=now;}
    public Status observe(long now,boolean available,long terrainSignature){
        if(!available){readySince=stableSince=-1;return now-started>=INITIALIZATION_LIMIT_SECONDS*1_000_000_000L?Status.INITIALIZATION_TIMEOUT:Status.INITIALIZING;}
        if(readySince<0){readySince=stableSince=now;signature=terrainSignature;}
        if(signature!=terrainSignature){signature=terrainSignature;stableSince=now;}
        if(now-readySince>=4_000_000_000L&&now-stableSince>=1_000_000_000L)return Status.READY;
        return now-readySince>=30_000_000_000L?Status.STABILITY_TIMEOUT:Status.WARMUP;
    }
    /** Compare two halves of the latest 30 actual GPU batch durations, never CPU frame time. */
    public static boolean gpuStable(java.util.List<Long> nanos){
        if(nanos.size()<30)return false;
        var recent=nanos.subList(nanos.size()-30,nanos.size());
        if(recent.stream().anyMatch(n->n==null||n<=0))return false;
        var first=new java.util.ArrayList<>(recent.subList(0,15));var last=new java.util.ArrayList<>(recent.subList(15,30));
        first.sort(Long::compare);last.sort(Long::compare);double a=first.get(7),b=last.get(7);
        return Math.abs(a-b)/Math.min(a,b)<=.10;
    }
    public double readySeconds(long now){return readySince<0?0:(now-readySince)/1e9;}
    public double stableSeconds(long now){return stableSince<0?0:(now-stableSince)/1e9;}
}
