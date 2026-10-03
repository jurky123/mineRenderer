package com.voxellight.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShadowCascadesTest {
    @Test void uploadedNormalMatricesMatchThePreviousShaderInverseTransposeInEveryCascade() {
        for(var light:List.of(ShadowLight.fixed(),ShadowLight.world(-1.2f,3,1,0),ShadowLight.world(.7f,3,1,0))) {
            for(int c=0;c<3;c++) {
                var matrix=ShadowCascades.anchored(29_999_000.25,65,-29_999_000.5,new ShadowMapCache.Anchor(29_999_000,64,-29_999_000),light,c);
                var expected=new org.joml.Matrix3f(matrix).invert().transpose();
                var actual=new org.joml.Matrix3f(ShadowCascades.normalMatrix(matrix));
                for(var normal:List.of(new Vector3f(1,0,0),new Vector3f(0,1,0),new Vector3f(0,0,1),new Vector3f(.3f,.7f,-.5f))) {
                    var e=expected.transform(new Vector3f(normal));var a=actual.transform(new Vector3f(normal));
                    assertEquals(e.x,a.x,1e-4);assertEquals(e.y,a.y,1e-4);assertEquals(e.z,a.z,1e-4);
                }
            }
        }
    }

    @Test void everyRangeCoversItsReceiverSphereEvenAtTheLargestAnchorOffset() {
        var anchor = new ShadowMapCache.Anchor(0, 64, 0);
        for (var light : List.of(ShadowLight.fixed(), ShadowLight.world(-1.2f, 3, 1, 0), ShadowLight.world(0, 3, 1, 0), ShadowLight.world(1.2f, 3, 1, 0))) {
            for (int c = 0; c < 3; c++) {
                var range = ShadowCascades.range(c);
                var matrix = ShadowCascades.anchored(8, 72, 8, anchor, light, c);
                for (int yaw = 0; yaw < 360; yaw += 15) for (int pitch = -90; pitch <= 90; pitch += 15) {
                    double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                    float radius = range.blendEnd();
                    var receiver = new Vector3f((float)(Math.cos(y) * Math.cos(p) * radius), (float)(Math.sin(p) * radius), (float)(Math.sin(y) * Math.cos(p) * radius));
                    var projected = matrix.transformProject(new Vector3f(receiver));
                    float guard = 8f / range.mapSize();
                    assertTrue(Math.abs(projected.x) < 1 - guard * 2, "A selected range cannot hit its hard UV boundary");
                    assertTrue(Math.abs(projected.y) < 1 - guard * 2);
                    assertTrue(projected.z > 0 && projected.z < 1, "Range blend must not cross a depth clip plane");
                    var blocker = matrix.transformProject(new Vector3f(receiver).add(light.direction().mul(BorrowedCasterVolume.EXTRUSION)));
                    assertEquals(projected.x, blocker.x, 0.000001f);
                    assertEquals(projected.y, blocker.y, 0.000001f);
                    assertTrue(blocker.z < projected.z);
                }
            }
        }
    }

    @Test void viewYawPitchAndFovDoNotChangeWorldRadiusOrCascadeProjection() {
        for (double origin : new double[]{0, 29999984, -29999984}) {
            double cx = origin + 0.2, cy = 65, cz = origin + 0.1;
            var anchor = new ShadowMapCache.Anchor(ShadowVolume.anchor(cx), 64, ShadowVolume.anchor(cz));
            var light = ShadowLight.world(-0.7f, 3, 1, 0);
            // Points at both overlap bands and the previous 24-block cutoff.
            for (float radius : new float[]{12, 14, 16, 24, 26, 29, 32, 40, 44, 47, 64, 96, 112, 127}) {
                var original = new Vector3f(0, -2, -radius);
                for (int yaw = -30; yaw <= 30; yaw += 10) for (int pitch = -20; pitch <= 20; pitch += 10) for (int fov : new int[]{50, 70, 100}) {
                    var view = new Matrix4f().rotationY((float)Math.toRadians(yaw)).rotateX((float)Math.toRadians(pitch));
                    var projection = new Matrix4f().perspective((float)Math.toRadians(fov), 16f / 9, 256, 0.05f, true);
                    var clip = new Matrix4f(projection).mul(view).transformProject(new Vector3f(original));
                    var restored = new Matrix4f(view).invert().mul(new Matrix4f(projection).invert()).transformProject(clip);
                    assertEquals(original.length(), restored.length(), 0.0001f, "Turning in place cannot choose another radius band");
                    for (int c = 0; c < 3; c++) {
                        var matrix = ShadowCascades.anchored(cx, cy, cz, anchor, light, c);
                        var expected = matrix.transformProject(new Vector3f(original));
                        var actual = matrix.transformProject(new Vector3f(restored));
                        assertEquals(expected.x, actual.x, 0.00001f);
                        assertEquals(expected.y, actual.y, 0.00001f);
                        assertEquals(expected.z, actual.z, 0.00001f);
                    }
                }
            }
        }
    }

    @Test void cascadeTileCachesUseTheActualMapDimensionsAndInvalidateIndependently() {
        var anchor = new ShadowMapCache.Anchor(0, 64, 0);
        var bounds = new CasterBounds(20, 65, 0, 21, 66, 1);
        for (int i = 0; i < 3; i++) {
            var cache = new ShadowMapCache(i);
            int size = ShadowCascades.range(i).mapSize();
            var first = cache.plan(anchor, ShadowLight.fixed(), true, List.of());
            assertEquals((size / 256) * (size / 256), first.pages());
            assertEquals(List.of(new ShadowMapCache.Rect(0, 0, size, size)), first.regions());
            first.regions().forEach(cache::rendered);
            assertEquals(0, cache.plan(anchor, ShadowLight.fixed(), true, List.of()).pages());
            cache.invalidate(bounds);
            var dirty = cache.plan(anchor, ShadowLight.fixed(), true, List.of());
            assertTrue(dirty.pages() > 0 && dirty.pages() < cache.tileCount());
            for (var rect : dirty.regions()) {
                assertTrue(rect.x() >= 0 && rect.y() >= 0 && rect.x() + rect.width() <= size && rect.y() + rect.height() <= size);
            }
            var changed = cache.plan(anchor, ShadowLight.world(0.3f, 3, 1, 0), true, List.of());
            assertEquals(cache.tileCount(), changed.pages());
        }
        assertEquals(30L * 1024 * 1024, ShadowCascades.mapBytes(), "Three maps must keep the published resource bound");
    }
}
