package com.voxellight.world;

/** Reference water optics and one HDR-background + one immutable depth-image budget. */
public final class WaterOptics {
    public static final int SETTINGS_BYTES=80;
    public static final long TARGET_LIMIT=96L*1024*1024;
    private WaterOptics() { }
    public static long targetBytes(int width,int height){if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid water size");return Math.multiplyExact((long)width*height,12);}
    public static float fresnel(float nDotV){float t=1-Math.clamp(nDotV,0,1);return .02f+.98f*t*t*t*t*t;}
    public static float transmission(float absorption,float thickness){return (float)Math.exp(-Math.max(0,absorption)*Math.clamp(thickness,0,16));}
    /** RGBA16F radial guide tolerates half-float rounding, not broad world-distance mismatches. */
    public static boolean backgroundMatches(float stored,float current){return stored>0 && Float.isFinite(current) && Math.abs(stored-current)<=Math.max(.04f,current*.002f);}
}
