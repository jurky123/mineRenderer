package com.voxellight.world;

/** Normal-wave controls and a bounded monotonic phase independent of world coordinate magnitude. */
public final class WaterSurface {
    public static final float DEFAULT_STRENGTH = .09f, DEFAULT_SPEED = 1;
    private WaterSurface() { }
    private static boolean enabled=true;
    public static synchronized void waves(boolean value){enabled=value;}
    private static long tick=System.nanoTime();private static double elapsed;private static float windSpeed=DEFAULT_SPEED,waveStrength=DEFAULT_STRENGTH;
    public static synchronized float clock(){long now=System.nanoTime();elapsed=(elapsed+Math.max(0,(now-tick)/1e9)*windSpeed)%65536;tick=now;return (float)elapsed;}
    public static synchronized void windSpeed(float value){clock();windSpeed=speed(value);}
    public static synchronized void waveStrength(float value){waveStrength=strength(value);}
    public static synchronized float waveStrength(){return enabled?waveStrength:0;}
    public static float strength(float value) {
        if (!Float.isFinite(value) || value < 0 || value > .3f) throw new IllegalArgumentException("Wave strength must be 0..0.3");
        return value;
    }
    public static float speed(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 3) throw new IllegalArgumentException("Wave speed must be 0..3");
        return value;
    }
    public static float phase(long elapsedNanos, float speed) {
        return (float)((Math.max(0, elapsedNanos) / 1e9 * speed(speed) * (Math.PI * 2 / 12)) % (Math.PI * 2));
    }
}
