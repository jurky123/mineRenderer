/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.sky;

/** Official LightManager sky equations; only Minecraft/asset ownership is adapted. */
public final class SkyMath {
    public static final float SUN_PATH_ROTATION_DEG = 30f;
    private static final float SUN_ANGULAR_DIAMETER_DEGREES = 0.53f;
    private static final float MOON_ANGULAR_DIAMETER_DEGREES = 0.52f;
    private static final float SUN_ANGULAR_RADIUS = (float) Math.toRadians(SUN_ANGULAR_DIAMETER_DEGREES * 0.5f);
    private static final float MOON_ANGULAR_RADIUS = (float) Math.toRadians(MOON_ANGULAR_DIAMETER_DEGREES * 0.5f);
    private static final float SUN_PATH_ROTATION_RAD = (float) Math.toRadians(SUN_PATH_ROTATION_DEG);
    private static final float MIN_DIRECT_RADIANCE = 1.0e-6f;
    private static final float BASE_SUN_LUMINANCE = 30.0f;
    private static final float BASE_MOON_LUMINANCE = 0.06f;
    private static final float RAIN_SUN_LUMINANCE = 0.075f;
    private static final float THUNDER_SUN_LUMINANCE = 0.03f;
    private static final float RAIN_MOON_LUMINANCE = 0.0225f;
    private static final float THUNDER_MOON_LUMINANCE = 0.0375f;
    // Weather dome intensity is authored in sun-luminance units. Keep that
    // reference independent of the normalized direct-light multiplier.
    private static final float WEATHER_DOME_RADIANCE_REFERENCE = 10.0f;
    public static final float THUNDER_SKY_INTENSITY = 0.8f;
    public static final float DEFAULT_SUN_INTENSITY = 1.0f;
    public static final float DEFAULT_MOON_INTENSITY = 1.0f;

    private static float sunIntensity = DEFAULT_SUN_INTENSITY;
    private static float moonIntensity = DEFAULT_MOON_INTENSITY;

    public static SkyLightState evaluateSkyLightState(
            float skyAngle,
            float rainGradient,
            float thunderGradient) {
        float[] sunPosition = computeSunDirection(skyAngle);
        float[] moonPosition = {-sunPosition[0], -sunPosition[1], -sunPosition[2]};

        float sunLuminance = BASE_SUN_LUMINANCE * sunIntensity;
        float moonLuminance = BASE_MOON_LUMINANCE * moonIntensity;
        float[] sunSource = {sunLuminance, sunLuminance, sunLuminance};
        float[] moonSource = {moonLuminance * 0.85f, moonLuminance * 0.9f, moonLuminance};
        float[] sunDirect = applyTransmittance(sunSource, directAtmosphereTransmittance(sunPosition[1]));
        float[] moonDirect = applyTransmittance(moonSource, directAtmosphereTransmittance(moonPosition[1]));
        sunDirect = scaleRadiance(sunDirect, weatherSunDirectScale(rainGradient, thunderGradient));
        moonDirect = scaleRadiance(moonDirect, weatherMoonDirectScale(rainGradient, thunderGradient));

        return new SkyLightState(
                sunPosition, moonPosition,
                sunSource, moonSource,
                thresholdDirectRadiance(sunDirect), thresholdDirectRadiance(moonDirect),
                SUN_ANGULAR_RADIUS, MOON_ANGULAR_RADIUS);
    }

    static float weatherSunDirectScale(float rainGradient, float thunderGradient) {
        float weatherSunLuminance = lerp(
                RAIN_SUN_LUMINANCE,
                THUNDER_SUN_LUMINANCE,
                thunderGradient);
        return lerp(1.0f, weatherSunLuminance / BASE_SUN_LUMINANCE, rainGradient);
    }

    static float weatherMoonDirectScale(float rainGradient, float thunderGradient) {
        float weatherMoonLuminance = lerp(
                RAIN_MOON_LUMINANCE,
                THUNDER_MOON_LUMINANCE,
                thunderGradient);
        return lerp(1.0f, weatherMoonLuminance / BASE_MOON_LUMINANCE, rainGradient);
    }

    private static float[] scaleRadiance(float[] radiance, float scale) {
        return new float[]{
                radiance[0] * scale,
                radiance[1] * scale,
                radiance[2] * scale
        };
    }

    private static float lerp(float from, float to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        return from + (to - from) * t;
    }

    public static float[] computeSunDirection(float skyAngle) {
        float angle = (float)(skyAngle * 2.0 * Math.PI + Math.PI / 2.0);
        float sx = (float) Math.cos(angle);
        float sy = (float) Math.sin(angle);
        float sz = 0f;

        float cr = (float) Math.cos(SUN_PATH_ROTATION_RAD);
        float sr = (float) Math.sin(SUN_PATH_ROTATION_RAD);
        float ny = sy * cr - sz * sr;
        float nz = sy * sr + sz * cr;

        return new float[] { sx, ny, nz };
    }

    public static float celestialVisibility(float height) {
        float t = Math.max(0.0f, Math.min(1.0f, (height + 0.15f) / 0.20f));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float[] directAtmosphereTransmittance(float height) {
        if (height <= 0.0f) {
            return new float[]{0.0f, 0.0f, 0.0f};
        }

        float zenithRadians = (float) Math.acos(Math.max(0.0f, Math.min(1.0f, height)));
        float zenithDegrees = zenithRadians * (180.0f / (float) Math.PI);
        float airMass = 1.0f / (height + 0.15f * (float) Math.pow(93.885f - zenithDegrees, -1.253f));

        float rayleighR = 0.145f;
        float rayleighG = 0.340f;
        float rayleighB = 0.580f;
        float mie = 0.080f;

        return new float[]{
                (float) Math.exp(-(rayleighR + mie) * airMass),
                (float) Math.exp(-(rayleighG + mie) * airMass),
                (float) Math.exp(-(rayleighB + mie) * airMass)
        };
    }

    private static float[] applyTransmittance(float[] radiance, float[] transmittance) {
        return new float[]{
                radiance[0] * transmittance[0],
                radiance[1] * transmittance[1],
                radiance[2] * transmittance[2]
        };
    }

    private static float[] thresholdDirectRadiance(float[] radiance) {
        return (radiance[0] + radiance[1] + radiance[2]) > MIN_DIRECT_RADIANCE
                ? radiance
                : new float[]{0.0f, 0.0f, 0.0f};
    }

    public record SkyLightState(
            float[] sunPosition,
            float[] moonPosition,
            float[] sunSourceRadiance,
            float[] moonSourceRadiance,
            float[] sunDirectRadiance,
            float[] moonDirectRadiance,
            float sunAngularRadius,
            float moonAngularRadius) {
        public float weatherDomeRadiance() {
            float sunRadiance = luminance(sunSourceRadiance)
                    * celestialVisibility(sunPosition[1]);
            float moonRadiance = luminance(moonSourceRadiance)
                    * celestialVisibility(moonPosition[1]);
            return (sunRadiance + moonRadiance) / WEATHER_DOME_RADIANCE_REFERENCE;
        }

        private static float luminance(float[] radiance) {
            return radiance[0] * 0.2126f + radiance[1] * 0.7152f + radiance[2] * 0.0722f;
        }

        public static SkyLightState empty() {
            return new SkyLightState(
                    new float[]{0.0f, 1.0f, 0.0f},
                    new float[]{0.0f, -1.0f, 0.0f},
                    new float[]{0.0f, 0.0f, 0.0f},
                    new float[]{0.0f, 0.0f, 0.0f},
                    new float[]{0.0f, 0.0f, 0.0f},
                    new float[]{0.0f, 0.0f, 0.0f},
                    SUN_ANGULAR_RADIUS,
                    MOON_ANGULAR_RADIUS);
        }
    }
}
