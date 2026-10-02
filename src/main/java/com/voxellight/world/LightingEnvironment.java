package com.voxellight.world;

/** Reference linear intensities, independent of the old LDR shadow-strength convention. */
public record LightingEnvironment(float directR, float directG, float directB, float directStrength,
                                  float skyR, float skyG, float skyB, float skyStrength,
                                  float horizonR, float horizonG, float horizonB, float lowerHemisphere) {
    public static final int SETTINGS_BYTES = 48;
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
                .55f, .72f, 1, (.025f + .40f * day) * weather, .55f, .72f, 1, .45f);
    }
    /** Continuous artistic sky palette, gated by the existing celestial visibility/weather policy. */
    public static LightingEnvironment polished(ShadowLight light, boolean overworld, float sunAngle, float rainBrightness) {
        var reference = sample(light, overworld, sunAngle, rainBrightness);
        if (!overworld && light.source() != ShadowLight.Source.FIXED) return reference;
        float height = light.source() == ShadowLight.Source.FIXED ? 1 : (float)Math.cos(sunAngle);
        float day = smooth(-.12f, .32f, height);
        float dusk = (1 - smooth(.08f, .55f, Math.abs(height))) * day;
        float clear = Math.clamp(rainBrightness, 0, 1);
        float zr = mix(.22f, .42f, day), zg = mix(.32f, .66f, day), zb = mix(.65f, 1, day);
        float hr = mix(.28f, .82f, day) + .22f*dusk;
        float hg = mix(.36f, .88f, day) - .26f*dusk;
        float hb = mix(.62f, 1, day) - .44f*dusk;
        float grey = mix(.35f, .75f, day);
        return new LightingEnvironment(reference.directR(), reference.directG(), reference.directB(),
                reference.directStrength()*1.12f,
                mix(grey,zr,clear),mix(grey,zg,clear),mix(grey,zb,clear),
                (.022f + .56f*day)*(.4f + .6f*clear),
                mix(grey,hr,clear),mix(grey,hg,clear),mix(grey,hb,clear),.30f);
    }
    private static float mix(float a,float b,float t){return a+(b-a)*t;}
    private static float smooth(float a,float b,float x){float t=Math.clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);}

}
