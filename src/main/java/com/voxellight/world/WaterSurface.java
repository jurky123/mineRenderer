package com.voxellight.world;

/** Normal-wave controls and a bounded monotonic phase independent of world coordinate magnitude. */
public final class WaterSurface {
    public static final float DEFAULT_STRENGTH = .12f, DEFAULT_SPEED = 1;
    private WaterSurface() { }
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
