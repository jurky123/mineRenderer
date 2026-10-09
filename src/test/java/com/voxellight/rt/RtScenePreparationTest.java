package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;
class RtScenePreparationTest {
 @Test void publicationUsesAnImmutableSnapshotAndRejectsAnotherGeneration() throws Exception {
  try(var p=new RtScenePreparation<String>(1024)){
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);byte[] bytes={7};
   assertTrue(p.stage("a",1,2,bytes,copy->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return new RtGeometryRanges(copy,new int[]{1});}));
   assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));bytes[0]=9;release.countDown();assertEquals(7,p.publish("a",1,2,bytes).triangles()[0]);
   assertTrue(p.stage("b",1,2,bytes,copy->new RtGeometryRanges(copy,new int[]{1})));assertNull(p.publish("b",1,3,bytes));
  }
 }
 @Test void budgetAndClosedWorkerFallBackWithoutQueuing(){var p=new RtScenePreparation<String>(0);assertFalse(p.stage("a",1,1,new byte[]{1},b->new RtGeometryRanges(b,new int[]{1})));p.close();assertFalse(p.stage("a",1,1,new byte[0],b->new RtGeometryRanges(b,new int[]{0})));}
 @Test void discardedInFlightWorkCannotPublishAfterModeSwitch() throws Exception {
  try(var p=new RtScenePreparation<String>(32)){
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);byte[] old={1};
   assertTrue(p.stage("a",1,1,old,copy->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return new RtGeometryRanges(copy,new int[]{1});}));
   try{assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));p.discardUnpublished();assertNull(p.publish("a",1,1,old));}finally{release.countDown();}
   byte[] current={9};assertTrue(p.stage("a",2,1,current,copy->new RtGeometryRanges(copy,new int[]{1})));
   assertEquals(9,p.publish("a",2,1,current).triangles()[0]);
  }
 }
}
