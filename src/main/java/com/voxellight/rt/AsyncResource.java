package com.voxellight.rt;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Single-owner startup. Abandoned results are disposed by the creating worker, never adopted. */
public final class AsyncResource<T> implements AutoCloseable {
 private T value;
 private Throwable failure;
 private boolean finished, abandoned, taken;
 private final Consumer<T> dispose;
 public AsyncResource(Executor executor,Supplier<T> create,Consumer<T> dispose){
  this.dispose=dispose;
  executor.execute(()->{
   T result;
   try{result=create.get();}catch(Throwable error){synchronized(this){failure=error;finished=true;}return;}
   boolean discard;
   synchronized(this){discard=abandoned;if(!discard)value=result;finished=true;}
   if(discard)dispose.accept(result);
  });
 }
 public synchronized boolean finished(){return finished;}
 public synchronized T take(){
  if(!finished||abandoned||taken)throw new IllegalStateException("Startup result unavailable");
  if(failure!=null)throw new IllegalStateException("Native startup failed",failure);
  taken=true;T result=value;value=null;return result;
 }
 public void close(){
  T discard;
  synchronized(this){abandoned=true;discard=value;value=null;}
  if(discard!=null)dispose.accept(discard);
 }
}
