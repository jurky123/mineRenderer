package com.voxellight.world;

/** Reference linear intensities, independent of the old LDR shadow-strength convention. */
public record LightingEnvironment(float directR, float directG, float directB, float directStrength,
                                  float skyR, float skyG, float skyB, float skyStrength) {
    public static final int SETTINGS_BYTES = 32;
    public static final long TARGET_LIMIT = 256L * 1024 * 1024;
    public static long targetBytes(int width, int height) {
        return Math.addExact(MaterialEncoding.targetBytes(width, height), Math.multiplyExact((long)width * height, 8));
    }
    public static LightingEnvironment sample(ShadowLight light, boolean overworld, float sunAngle, float rainBrightness) {
        float day = overworld ? Math.clamp((float)Math.cos(sunAngle) * 1.5f + .15f, 0, 1) : 0;
        float weather = .4f + .6f * Math.clamp(rainBrightness, 0, 1);
        if (light.source() == ShadowLight.Source.FIXED) day = 1;
        boolean moon = light.source() == ShadowLight.Source.MOON;
        float elevation = Math.clamp(light.direction().y, 0, 1);
        float warm = Math.clamp((.45f - elevation) / .35f, 0, 1);
        return new LightingEnvironment(moon ? .45f : 1, moon ? .60f : .95f - .3f * warm,
                moon ? 1 : .85f - .5f * warm,
                light.strength() / (moon ? .22f : .45f) * (moon ? .16f : 1.4f),
                .55f, .72f, 1, (.025f + .40f * day) * weather);
    }
}
