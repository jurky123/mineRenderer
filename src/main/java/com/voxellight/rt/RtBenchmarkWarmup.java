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
    public double readySeconds(long now){return readySince<0?0:(now-readySince)/1e9;}
    public double stableSeconds(long now){return stableSince<0?0:(now-stableSince)/1e9;}
}
