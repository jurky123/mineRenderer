package com.voxellight.world;

/** Bounded spatial terrain AO reference; texture guides carry visibility, oct normal and view depth. */
public final class AmbientOcclusion {
    public static final int SETTINGS_BYTES=32, PIXEL_BYTES=16; // Two half-resolution RGBA16F targets.
    public static final long TARGET_LIMIT=32L*1024*1024;
    public static final float RADIUS=1.5f, STRENGTH=.85f, BIAS=.035f, SCREEN_RADIUS=80;
    public static final float PLANE_TOLERANCE=.08f, NORMAL_DOT=.85f, MIN_VISIBILITY=.35f;
    private AmbientOcclusion() { }
    public static int halfSize(int size) {
        if(size<=0)throw new IllegalArgumentException("Invalid AO target size");
        return size/2+size%2;
    }
    /** Fixed representative of a 2x2 block, including the last partial block at odd sizes. */
    public static int guidePixel(int halfPixel,int fullSize) {
        if(halfPixel<0 || halfPixel>=halfSize(fullSize))throw new IllegalArgumentException("Invalid AO guide");
        return (int)Math.min(2L*halfPixel+1,fullSize-1L);
    }
    public static long targetBytes(int width,int height) {
        return Math.multiplyExact(Math.multiplyExact((long)halfSize(width),halfSize(height)),PIXEL_BYTES);
    }
    /** Independent CPU reference for the normalized cosine-weighted horizon-slice integral. */
    public static double sliceVisibility(double normalAngle,double negativeHorizon,double positiveHorizon) {
        double lo=normalAngle-Math.PI/2, hi=normalAngle+Math.PI/2;
        double full=integral(hi,normalAngle)+integral(lo,normalAngle);
        double visible=integral(Math.clamp(positiveHorizon,0,hi),normalAngle)
                +integral(Math.clamp(negativeHorizon,lo,0),normalAngle);
        return Math.clamp(visible/full,0,1);
    }
    private static double integral(double h,double n) {
        double s=Math.sin(h);
        return .5*Math.cos(n)*s*s+Math.sin(n)*(.5*h-.25*Math.sin(2*h));
    }
}
