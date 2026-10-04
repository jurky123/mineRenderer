package com.voxellight.rt;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AsyncResourceTest {
 @Test void renderCallerDoesNotWaitForNativeCompilation() throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  try(var executor=Executors.newSingleThreadExecutor()){
   var resource=new AsyncResource<>(executor,()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return 42;},v->fail("Adopted handle destroyed"));
   try{assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(resource.finished());assertThrows(IllegalStateException.class,resource::take);}finally{release.countDown();}
   executor.submit(()->{}).get(2,TimeUnit.SECONDS);
   assertTrue(resource.finished());assertEquals(42,resource.take());resource.close();
  }
 }
 @Test void disableDuringStartupDisposesLateHandleExactlyOnce(){
  var tasks=new ArrayDeque<Runnable>();var disposed=new AtomicInteger();
  var resource=new AsyncResource<>(tasks::add,()->7,v->disposed.addAndGet(v));
  resource.close();tasks.remove().run();resource.close();assertEquals(7,disposed.get());assertThrows(IllegalStateException.class,resource::take);
 }
 @Test void completedUnadoptedHandleIsDisposed(){
  var tasks=new ArrayDeque<Runnable>();var disposed=new AtomicInteger();
  var resource=new AsyncResource<>(tasks::add,()->7,v->disposed.addAndGet(v));tasks.remove().run();resource.close();resource.close();assertEquals(7,disposed.get());
 }
 @Test void startupFailureBecomesRecoverableException(){
  var tasks=new ArrayDeque<Runnable>();var error=new IllegalStateException("compiler failure");
  var resource=new AsyncResource<Integer>(tasks::add,()->{throw error;},v->fail());tasks.remove().run();
  assertTrue(resource.finished());assertSame(error,assertThrows(IllegalStateException.class,resource::take).getCause());resource.close();
 }
}
