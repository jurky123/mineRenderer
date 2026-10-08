package com.voxellight.rt;

/** Pipeline initialization and actual rendered warmup have independent monotonic clocks. */
public final class RtBenchmarkWarmup {
    public enum Status {INITIALIZING,WARMUP,READY,INITIALIZATION_TIMEOUT,STABILITY_TIMEOUT}
    public static final int INITIALIZATION_LIMIT_SECONDS=180;
    private final long started;
    private long readySince=-1,stableSince=-1,signature,firstGpuSince=-1,gpuWindowSince=-1;
    public enum GpuStatus {WAITING_FIRST_SAMPLE,COLLECTING,SETTLING,READY,FIRST_SAMPLE_TIMEOUT,COLLECTION_TIMEOUT,STABILITY_TIMEOUT}
    public RtBenchmarkWarmup(long now){started=now;}
    public Status observe(long now,boolean available,long terrainSignature){
        if(!available){readySince=stableSince=firstGpuSince=gpuWindowSince=-1;return now-started>=INITIALIZATION_LIMIT_SECONDS*1_000_000_000L?Status.INITIALIZATION_TIMEOUT:Status.INITIALIZING;}
        if(readySince<0){readySince=stableSince=now;signature=terrainSignature;}
        if(signature!=terrainSignature){signature=terrainSignature;stableSince=now;}
        if(now-readySince>=4_000_000_000L&&now-stableSince>=1_000_000_000L)return Status.READY;
        return now-readySince>=30_000_000_000L?Status.STABILITY_TIMEOUT:Status.WARMUP;
    }
    /** Lazy driver execution is not GPU variance: start its clock only after a full window arrives. */
    public GpuStatus gpuStatus(long now,java.util.List<Long> nanos){
        if(readySince<0)return GpuStatus.WAITING_FIRST_SAMPLE;
        if(nanos.isEmpty())return now-readySince>=INITIALIZATION_LIMIT_SECONDS*1_000_000_000L?GpuStatus.FIRST_SAMPLE_TIMEOUT:GpuStatus.WAITING_FIRST_SAMPLE;
        if(firstGpuSince<0)firstGpuSince=now;
        if(nanos.size()<30)return now-firstGpuSince>=INITIALIZATION_LIMIT_SECONDS*1_000_000_000L?GpuStatus.COLLECTION_TIMEOUT:GpuStatus.COLLECTING;
        if(gpuWindowSince<0)gpuWindowSince=now;
        if(gpuStable(nanos))return GpuStatus.READY;
        return now-gpuWindowSince>=30_000_000_000L?GpuStatus.STABILITY_TIMEOUT:GpuStatus.SETTLING;
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
