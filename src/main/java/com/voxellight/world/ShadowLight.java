package com.voxellight.world;

import org.joml.Vector3f;

/** Single dominant celestial light, sampled from the frame's native sky state. */
public record ShadowLight(Source source, int angleStep, float strength, double angleRadians) {
    public enum Source { FIXED, SUN, MOON, NONE }
    public record Key(Source source, double angleRadians) { }
    public static final int ANGLE_STEPS = 14_400; // Diagnostic display only; world projections use continuous native angles.
    private static final double TAU = Math.PI * 2;

    public ShadowLight {
        if (!Double.isFinite(angleRadians) || source == null || angleStep < 0 || angleStep >= ANGLE_STEPS || !Float.isFinite(strength) || strength < 0 || strength > 0.45f) {
            throw new IllegalArgumentException("Invalid shadow light");
        }
    }

    public ShadowLight(Source source, int angleStep, float strength) {
        this(source, angleStep, strength, angleStep * TAU / ANGLE_STEPS);
    }

    public static ShadowLight fixed() { return new ShadowLight(Source.FIXED, 0, 0.45f); }
    public static ShadowLight none() { return new ShadowLight(Source.NONE, 0, 0); }
    public Key key() { return new Key(source, angleRadians); }

    public static ShadowLight world(float sunAngle, float moonAngle, float rainBrightness, int moonPhase) {
        if (!Float.isFinite(sunAngle) || !Float.isFinite(moonAngle) || !Float.isFinite(rainBrightness)) return none();
        float weather = 0.25f + 0.75f * Math.clamp(rainBrightness, 0, 1);
        float sun = 0.45f * elevation((float)Math.cos(sunAngle)) * weather;
        float phase = Math.abs(Math.floorMod(moonPhase, 8) - 4) / 4f;
        float moon = 0.22f * phase * elevation((float)Math.cos(moonAngle)) * weather;
        if (sun <= 0 && moon <= 0) return none();
        return sun >= moon ? new ShadowLight(Source.SUN, quantize(sunAngle), sun, normalize(sunAngle))
                : new ShadowLight(Source.MOON, quantize(moonAngle), moon, normalize(moonAngle));
    }

    private static float elevation(float height) {
        // Fade near the horizon: this remains a finite local caster window, not a full-view-distance map.
        float t = Math.clamp((height - 0.10f) / 0.15f, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static int quantize(double angle) {
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("Invalid celestial angle");
        double normalized = normalize(angle);
        return (int)(Math.round(normalized * ANGLE_STEPS / TAU) % ANGLE_STEPS);
    }

    private static double normalize(double angle) { return ((angle % TAU) + TAU) % TAU; }

    public Vector3f direction() {
        if (source == Source.FIXED || source == Source.NONE) return ShadowVolume.lightDirection();
        double angle = angleRadians;
        // SkyRenderer's native celestial pose is rotateY(-90 degrees) * rotateX(angle) * (0,100,0).
        return new Vector3f((float)-Math.sin(angle), (float)Math.cos(angle), 0).normalize();
    }
}
