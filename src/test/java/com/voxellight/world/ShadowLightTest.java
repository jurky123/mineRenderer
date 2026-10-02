package com.voxellight.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShadowLightTest {
    @Test
    void directionMatchesTheNativeSkyPoseAndShadowDirectionReversesAcrossNoon() {
        for (double angle : new double[]{-1.0, -0.5, 0, 0.5, 1.0}) {
            var light = ShadowLight.world((float)angle, (float)(angle + Math.PI), 1, 0);
            assertEquals(ShadowLight.Source.SUN, light.source());
            var nativePose = new Matrix4f().rotationY((float)(-Math.PI / 2)).rotateX((float)angle)
                    .transformPosition(new Vector3f(0, 100, 0)).normalize();
            assertTrue(nativePose.distance(light.direction()) < 0.0003f, "Match SkyRenderer's Y(-90) * X(angle) celestial pose");
        }
        assertTrue(ShadowLight.world(-0.5f, 3, 1, 0).direction().x > 0);
        assertTrue(ShadowLight.world(0.5f, 3, 1, 0).direction().x < 0);
    }

    @Test
    void moonStrengthDependsOnPhaseAndWeatherWithoutChangingProjection() {
        var full = ShadowLight.world((float)Math.PI, 0, 1, 0);
        var rainy = ShadowLight.world((float)Math.PI, 0, 0, 0);
        assertEquals(ShadowLight.Source.MOON, full.source());
        assertEquals(0.22f, full.strength(), 0.000001f);
        assertEquals(full.key(), rainy.key());
        assertEquals(full.strength() * 0.25f, rainy.strength(), 0.000001f);
        assertEquals(ShadowLight.Source.NONE, ShadowLight.world((float)Math.PI, 0, 1, 4).source());
        assertEquals(0.11f, ShadowLight.world((float)Math.PI, 0, 1, 2).strength(), 0.000001f);
    }

    @Test
    void horizonAndInvalidSkyInputsNeverProduceAnUnderGroundLightOrNonfiniteMatrix() {
        assertEquals(ShadowLight.Source.NONE, ShadowLight.world((float)Math.PI / 2, (float)-Math.PI / 2, 1, 0).source());
        assertEquals(ShadowLight.Source.NONE, ShadowLight.world(Float.NaN, 0, 1, 0).source());
        assertEquals(ShadowLight.Source.NONE, ShadowLight.world(0, 0, Float.POSITIVE_INFINITY, 0).source());
        assertThrows(IllegalArgumentException.class, () -> ShadowLight.quantize(Double.NaN));
        for (float angle : new float[]{-1.2f, -0.01f, 0, 0.01f, 1.2f}) {
            var light = ShadowLight.world(angle, angle + (float)Math.PI, 1, 0);
            var matrix = ShadowVolume.lightMatrix(0, 64, 0, light);
            assertTrue(matrix.isFinite(), "Noon projection must not look along its up vector");
            assertEquals(1, light.direction().length(), 0.000001f);
            var receiver = matrix.transformProject(new Vector3f(1, -2, 3));
            var caster = matrix.transformProject(new Vector3f(1, -2, 3).add(light.direction().mul(5)));
            assertEquals(receiver.x, caster.x, 0.000001f);
            assertEquals(receiver.y, caster.y, 0.000001f);
            assertTrue(caster.z < receiver.z);
        }
    }

    @Test
    void angularQuantizationHasBoundedErrorAndWrapsAtTheDayBoundary() {
        double step = 2 * Math.PI / ShadowLight.ANGLE_STEPS;
        for (int i = -20000; i <= 20000; i += 13) {
            double angle = i * 0.00073;
            double quantized = ShadowLight.quantize(angle) * step;
            double error = Math.abs(Math.atan2(Math.sin(angle - quantized), Math.cos(angle - quantized)));
            assertTrue(error <= step / 2 + 0.00000001);
        }
        assertEquals(ShadowLight.quantize(-0.3), ShadowLight.quantize(2 * Math.PI - 0.3));
        assertEquals(0, ShadowLight.quantize(2 * Math.PI));
    }

    @Test
    void changingAngleOrSourceInvalidatesAllCachedTilesButStrengthOnlyChangesDoNot() {
        var cache = new ShadowMapCache();
        var anchor = new ShadowMapCache.Anchor(0, 64, 0);
        var noon = ShadowLight.world(0, (float)Math.PI, 1, 0);
        var initial = cache.plan(anchor, noon, true, List.of());
        initial.regions().forEach(cache::rendered);
        assertEquals(0, cache.plan(anchor, ShadowLight.world(0, (float)Math.PI, 0, 0), true, List.of()).pages());
        assertEquals(64, cache.plan(anchor, ShadowLight.world(0.1f, (float)Math.PI, 1, 0), true, List.of()).pages());
        assertEquals("celestial light moved", cache.plan(anchor, noon, true, List.of()).reason());
        cache.plan(anchor, noon, true, List.of()).regions().forEach(cache::rendered);
        assertEquals(64, cache.plan(anchor, new ShadowLight(ShadowLight.Source.MOON, noon.angleStep(), 0.1f), true, List.of()).pages());
    }

    @Test
    void tinyNativeAngleChangesMoveTheProjectionContinuouslyEvenWithinOneDisplayStep() {
        var first = ShadowLight.world(0.3f, 3, 1, 0);
        var second = ShadowLight.world(0.30001f, 3, 1, 0);
        assertEquals(first.angleStep(), second.angleStep());
        assertNotEquals(first.key(), second.key(), "Display quantization must not freeze or snap the render direction");
        assertTrue(first.direction().distance(second.direction()) > 0);
        assertTrue(first.direction().distance(second.direction()) < 0.00002f);
        var p = new Vector3f(12, -4, 7);
        for (int origin : new int[]{0, 29999984, -29999984}) {
            var a = ShadowVolume.lightMatrix(origin, 64, origin, first).transformProject(new Vector3f(p));
            var b = ShadowVolume.lightMatrix(origin, 64, origin, second).transformProject(new Vector3f(p));
            assertTrue(a.distance(b) < 0.00001f, "Rotating light must not globally resnap at large world coordinates");
            var local = ShadowVolume.lightMatrix(0, 64, 0, first).transformProject(new Vector3f(p));
            assertEquals(local, a);
        }
    }

    @Test
    void dynamicProjectionKeepsCachedWorldPointsFixedAtLargeCoordinates() {
        for (double origin : new double[]{0, 29_999_984, -29_999_984}) {
            var light = ShadowLight.world(-0.7f, 3, 1, 0);
            int anchor = ShadowVolume.anchor(origin);
            Vector3f expected = null;
            for (int i = 0; i < 70; i++) {
                double cx = origin + 0.1 + i * 0.1, cy = 65 + i * 0.03, cz = origin + 0.2 + i * 0.05;
                var point = new Vector3f((float)(origin + 5 - cx), (float)(64 - cy), (float)(origin - 3 - cz));
                var projected = ShadowVolume.anchoredMatrix(cx, cy, cz, anchor, 64, anchor, light).transformProject(point);
                if (expected == null) expected = new Vector3f(projected);
                assertEquals(expected.x, projected.x, 0.000001f);
                assertEquals(expected.y, projected.y, 0.000001f);
                assertEquals(expected.z, projected.z, 0.000001f);
            }
        }
    }
}
