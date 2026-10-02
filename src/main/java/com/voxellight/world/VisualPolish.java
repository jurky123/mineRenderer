package com.voxellight.world;

/** Bounded display controls; exposure is manual, so motion cannot trigger exposure pumping. */
public final class VisualPolish {
    public static final int SETTINGS_BYTES=32;
    public static final float DEFAULT_EV=.75f, BLOOM_STRENGTH=.22f;
    public static final float FADE_START=24, FADE_END=32;
    public static final long BLOOM_LIMIT=16L*1024*1024;
    private VisualPolish() { }
    public static float exposure(float ev) {
        if(!Float.isFinite(ev) || ev < -2 || ev > 2)throw new IllegalArgumentException("Exposure must be between -2 and 2 EV");
        return (float)Math.pow(2,ev);
    }
    public static int quarterSize(int size){if(size<=0)throw new IllegalArgumentException("Invalid target size");return (size+3)/4;}
    public static long bloomBytes(int width,int height){return (long)quarterSize(width)*quarterSize(height)*16;}
    /** The material cube extends at least 32 blocks from the camera on every side. */
    public static float coverage(float distance) {
        float t=Math.clamp((distance-FADE_START)/(FADE_END-FADE_START),0,1);
        return 1-t*t*(3-2*t);
    }
    /** Rational filmic shoulder/toe, normalized at a linear white of 6. */
    public static float filmic(float radiance,float ev) {
        float x=Math.max(0,radiance)*exposure(ev);
        return Math.clamp(curve(x)/curve(6),0,1);
    }
    private static float curve(float x){return (x*(.15f*x+.05f)+.004f)/(x*(.15f*x+.5f)+.06f)-.02f/.3f;}
}
