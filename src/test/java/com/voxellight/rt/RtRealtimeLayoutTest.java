package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtRealtimeLayoutTest {
 @Test void queuesProbesHistoryAndCacheNeverOverlap(){for(int spp=1;spp<=8;spp++){long pixels=640L*360,capacity=pixels*spp;assertEquals(1648+4*capacity,RtRealtimeLayout.probeBase(pixels,spp));assertEquals(RtRealtimeLayout.probeBase(pixels,spp)+3*capacity,RtRealtimeLayout.historyBase(pixels,spp));assertEquals(RtRealtimeLayout.historyBase(pixels,spp)+16*pixels,RtRealtimeLayout.cacheBase(pixels,spp));assertEquals((RtRealtimeLayout.requestBase(pixels,spp)+6144)*16,RtRealtimeLayout.bytes(pixels,spp));}}
 @Test void boundedRequestsReplaceFivePerPathSlotsWhileRetainingTwoHitRecordSlots(){long pixels=427L*240;assertEquals(1648+2*pixels,RtRealtimeLayout.hitBase(pixels,1));assertEquals(32*pixels,(RtRealtimeLayout.probeBase(pixels,1)-RtRealtimeLayout.hitBase(pixels,1))*16);long old=(1648+10*pixels+16*pixels+655360)*16;assertEquals(48*pixels-98304,old-RtRealtimeLayout.bytes(pixels,1));assertEquals(98304,1024*RtRealtimeLayout.REQUEST_SLOTS*16);}
 @Test void deviceRangeBoundsIncludeEveryByte(){for(int spp=1;spp<=8;spp++){long range=128L*1024*1024,pixels=RtRealtimeLayout.maxPixels(range,spp);assertTrue(RtRealtimeLayout.bytes(pixels,spp)<=range);assertTrue(RtRealtimeLayout.bytes(pixels+1,spp)>range);}}
 @Test void rejectsOverflowAndInvalidSamples(){assertThrows(IllegalArgumentException.class,()->RtRealtimeLayout.bytes(1,0));assertThrows(IllegalArgumentException.class,()->RtRealtimeLayout.bytes(0,1));assertThrows(ArithmeticException.class,()->RtRealtimeLayout.bytes(Long.MAX_VALUE,8));}
}
