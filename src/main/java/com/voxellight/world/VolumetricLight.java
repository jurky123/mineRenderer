package com.voxellight.world;

/** First shadowed-medium reference: fixed spatial sampling, no temporal/RGB accumulation. */
public final class VolumetricLight {
    public static final int STEPS = 16, SETTINGS_BYTES = 32;
    public static final float MAX_DISTANCE = 96, ANISOTROPY = .65f, PHASE_SCALE = .25f;
    public static final long TARGET_LIMIT = 8L * 1024 * 1024;
    private VolumetricLight() { }
    public static long targetBytes(int width, int height) { return targetBytes(width,height,true); }
    public static long targetBytes(int width, int height, boolean filtered) {
        return Math.multiplyExact((long)VisualPolish.quarterSize(width) * VisualPolish.quarterSize(height), filtered?16:8);
    }
    public static float phase(float cosine) {
        float g = ANISOTROPY;
        return (float)(PHASE_SCALE * (1 - g * g) / Math.pow(1 + g * g - 2 * g * Math.clamp(cosine, -1, 1), 1.5));
    }
    public static float stepTransmission(float density, float height, float distance) {
        return stepTransmission(density,height,distance,STEPS);
    }
    public static float stepTransmission(float density, float height, float distance, int steps) {
        double tau = density * Math.exp(-Math.clamp(height / Atmosphere.HEIGHT_SCALE, -1, 4)) * distance;
        return (float)Math.exp(-Math.clamp(tau, 0, 2.0 / steps));
    }
    public static float guideWeight(float receiverDistance, float guideDistance) {
        return (float)Math.exp(-Math.abs(receiverDistance - guideDistance) / Math.max(.5f, receiverDistance * .025f));
    }
}
