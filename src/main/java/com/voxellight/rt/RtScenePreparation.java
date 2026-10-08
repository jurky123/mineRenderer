package com.voxellight.rt;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Bounded CPU build/publish boundary. Vulkan allocation, upload and publication stay on the render thread. */
public final class RtScenePreparation<K> implements AutoCloseable {
    private record Ticket(long version,long epoch,byte[] source,CompletableFuture<RtGeometryRanges> result){}
    private final Map<K,Ticket> pending=new HashMap<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"VoxelLight scene preparation");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong outstandingBytes=new AtomicLong();
    private final long budget;
    private boolean closed;
    private long submitted,published,stale,waitNanos;
    public RtScenePreparation(long budget){if(budget<0)throw new IllegalArgumentException("budget");this.budget=budget;}
    public boolean stage(K key,long version,long epoch,byte[] source,Function<byte[],RtGeometryRanges> builder){
        if(closed||pending.size()>=32||source.length>budget-outstandingBytes.get())return false;
        var old=pending.get(key);if(old!=null&&old.version==version&&old.epoch==epoch&&old.source==source)return true;
        byte[] copy=source.clone();outstandingBytes.addAndGet(copy.length);
        try{var future=CompletableFuture.supplyAsync(()->{try{return builder.apply(copy);}finally{outstandingBytes.addAndGet(-copy.length);}},worker);pending.put(key,new Ticket(version,epoch,source,future));submitted++;return true;}
        catch(RejectedExecutionException error){outstandingBytes.addAndGet(-copy.length);return false;}
    }
    public RtGeometryRanges publish(K key,long version,long epoch,byte[] source){
        var ticket=pending.remove(key);if(ticket==null)return null;
        if(ticket.version!=version||ticket.epoch!=epoch||ticket.source!=source){stale++;return null;}
        long start=System.nanoTime();try{var result=ticket.result.join();published++;return result;}catch(CompletionException error){stale++;return null;}finally{waitNanos+=System.nanoTime()-start;}
    }
    public void discardUnpublished(){pending.clear();}
    public String status(){return "scenePrepSubmitted="+submitted+", scenePrepPublished="+published+", scenePrepStale="+stale+", scenePrepWaitNs="+waitNanos+", scenePrepOutstandingBytes="+outstandingBytes.get();}
    public void close(){closed=true;pending.clear();worker.shutdownNow();}
}
