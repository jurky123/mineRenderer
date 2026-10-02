package com.voxellight.world;

/** B1 material contract: no lighting, AO or fog in albedo; emission is strength, not radiance. */
public final class MaterialEncoding {
    public static final int CUTOUT = 1, TINTED = 2, UNSHADED = 4, ANIMATED = 8, ENTITY = 16;
    public static final int PIXEL_BYTES = 24; // RGBA8 + two RGBA16F targets + private D32.
    public static final long TARGET_LIMIT = 192L * 1024 * 1024;
    public static final long RESIDENT_LIMIT = 16L * 1024 * 1024;
    public static final int SECTION_LIMIT = 1024 * 1024;
    private MaterialEncoding() { }
    public static long targetBytes(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid material target size");
        return Math.multiplyExact(Math.multiplyExact((long)width, height), PIXEL_BYTES);
    }
    public static int packQuadEmissionFlags(int emission, int flags) {
        if (emission < 0 || emission > 15 || flags < 0 || flags > 15) throw new IllegalArgumentException("Invalid material metadata");
        return emission | (flags << 4);
    }
    public static float decodeSrgb(float value) { return value <= .04045f ? value / 12.92f : (float)Math.pow((value + .055f) / 1.055f, 2.4); }
    public static float encodeSrgb(float value) { return value <= .0031308f ? value * 12.92f : 1.055f * (float)Math.pow(value, 1 / 2.4) - .055f; }
    /** Positive reversed-Z bit distance: bounded rounding tolerance, never a world-distance epsilon. */
    public static boolean depthMatches(float surface, float scene) {
        return Float.isFinite(surface) && Float.isFinite(scene) && surface > 0 && scene > 0
                && Math.abs((long)Float.floatToRawIntBits(surface) - Float.floatToRawIntBits(scene)) <= 8;
    }
}
