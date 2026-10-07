package com.voxellight.rt;

/** uint4 offsets shared with common/realtime_policy.slang; no new descriptor bindings. */
public final class RtRealtimeLayout {
    public static final long HEADER=1648,CACHE_SLOTS=65536L*10;
    private RtRealtimeLayout(){}
    public static long capacity(long pixels,int spp){if(pixels<1||spp<1||spp>8)throw new IllegalArgumentException("pixels >= 1, spp 1..8");return Math.multiplyExact(pixels,spp);}
    public static long probeBase(long pixels,int spp){return Math.addExact(HEADER,Math.multiplyExact(capacity(pixels,spp),2));}
    public static long historyBase(long pixels,int spp){return Math.addExact(HEADER,Math.multiplyExact(capacity(pixels,spp),10));}
    public static long cacheBase(long pixels,int spp){return Math.addExact(historyBase(pixels,spp),Math.multiplyExact(pixels,16));}
    public static long bytes(long pixels,int spp){return Math.multiplyExact(Math.addExact(cacheBase(pixels,spp),CACHE_SLOTS),16);}
    public static long maxPixels(long range,int spp){capacity(1,spp);return Math.max(0,(range-16*(HEADER+CACHE_SLOTS))/(spp*160L+256));}
}
