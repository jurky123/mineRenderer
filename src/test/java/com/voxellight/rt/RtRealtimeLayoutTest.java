package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtRealtimeLayoutTest {
 @Test void queuesProbesHistoryAndCacheNeverOverlap(){for(int spp=1;spp<=8;spp++){long pixels=640L*360,capacity=pixels*spp;assertEquals(1648+2*capacity,RtRealtimeLayout.probeBase(pixels,spp));assertEquals(RtRealtimeLayout.probeBase(pixels,spp)+8*capacity,RtRealtimeLayout.historyBase(pixels,spp));assertEquals(RtRealtimeLayout.historyBase(pixels,spp)+16*pixels,RtRealtimeLayout.cacheBase(pixels,spp));assertEquals((RtRealtimeLayout.cacheBase(pixels,spp)+655360)*16,RtRealtimeLayout.bytes(pixels,spp));}}
 @Test void deviceRangeBoundsIncludeEveryByte(){for(int spp=1;spp<=8;spp++){long range=128L*1024*1024,pixels=RtRealtimeLayout.maxPixels(range,spp);assertTrue(RtRealtimeLayout.bytes(pixels,spp)<=range);assertTrue(RtRealtimeLayout.bytes(pixels+1,spp)>range);}}
 @Test void rejectsOverflowAndInvalidSamples(){assertThrows(IllegalArgumentException.class,()->RtRealtimeLayout.bytes(1,0));assertThrows(IllegalArgumentException.class,()->RtRealtimeLayout.bytes(0,1));assertThrows(ArithmeticException.class,()->RtRealtimeLayout.bytes(Long.MAX_VALUE,8));}
}
