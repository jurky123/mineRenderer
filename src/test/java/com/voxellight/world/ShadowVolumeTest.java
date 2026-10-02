package com.voxellight.world;

import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowVolumeTest {
    @Test
    void cachedVolumeProjectsTheSameWorldPointWhileCameraMovesAndRotates() {
        for (double origin : new double[]{0, 29_999_984, -29_999_984}) {
            int x = ShadowVolume.anchor(origin + 0.1), y = 64, z = ShadowVolume.anchor(origin + 0.1);
            Vector3f expected = null;
            for (int step = 0; step < 70; step++) {
                double cx = origin + 0.1 + step * 0.1, cy = 65 + step * 0.05, cz = origin + 0.1 + step * 0.07;
                assertEquals(x, ShadowVolume.anchor(cx));
                assertEquals(y, ShadowVolume.anchor(cy));
                assertEquals(z, ShadowVolume.anchor(cz));
                var relative = new Vector3f((float)(origin + 5 - cx), (float)(64 - cy), (float)(origin - 3 - cz));
                var projected = ShadowVolume.anchoredMatrix(cx, cy, cz, x, y, z).transformProject(relative);
                if (expected == null) expected = new Vector3f(projected);
                assertEquals(expected.x, projected.x, 0.000001f);
                assertEquals(expected.y, projected.y, 0.000001f);
                assertEquals(expected.z, projected.z, 0.000001f, "Cached depth must stay valid when the camera moves toward the light");
            }
        }
        assertEquals(-8, ShadowVolume.anchor(-0.01));
        assertEquals(0, ShadowVolume.anchor(7.99));
        assertEquals(8, ShadowVolume.anchor(8));
    }
    @Test
    void reconstructedSurfacePlaneProducesTheSameComparisonAcrossCameraViews() {
        var lightMatrix = ShadowVolume.lightMatrix();
        var normalMatrix = new Matrix3f(lightMatrix).invert().transpose();
        for (var normal : new Vector3f[]{new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1)}) {
            var tangent = Math.abs(normal.y) > 0.5 ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            var bitangent = new Vector3f(normal).cross(tangent);
            var point = new Vector3f(2, -1, -5);
            var center = lightMatrix.transformProject(new Vector3f(point));
            var sample = lightMatrix.transformProject(new Vector3f(point).add(new Vector3f(tangent).mul(0.05f)).add(new Vector3f(bitangent).mul(0.07f)));
            for (int angle = 0; angle < 360; angle += 15) {
                var view = new Matrix3f().rotationY((float)Math.toRadians(angle)).rotateX(0.2f);
                var inverseView = new Matrix3f(view).invert();
                var dx = view.transform(new Vector3f(tangent));
                var dy = view.transform(new Vector3f(bitangent));
                inverseView.transform(dx); inverseView.transform(dy);
                var reconstructed = dx.cross(dy);
                normalMatrix.transform(reconstructed);
                float gradientU = -2 * reconstructed.x / reconstructed.z;
                float gradientV = -2 * reconstructed.y / reconstructed.z;
                float predicted = center.z + gradientU * (sample.x - center.x) * 0.5f + gradientV * (sample.y - center.y) * 0.5f;
                assertEquals(sample.z, predicted, 0.000001f, "PCF receiver depth must not change with camera yaw");
                assertFalse(predicted - 0.00012f > sample.z, "A surface must not become its own blocker when the view changes");
                assertTrue(predicted - 0.00012f > sample.z - 0.003f, "Confirmed occlusion remains valid without complete neighboring casters");
            }
        }
    }
    @Test
    void movingCameraKeepsWorldGeometryOnTheSameShadowTexelLattice() {
        for (double origin : new double[]{0, 29_999_990, -29_999_990}) {
            var original = projectWorld(origin + 5, 64, origin - 10, origin, 65, origin);
            for (int i = 1; i <= 100; i++) {
                var moved = projectWorld(origin + 5, 64, origin - 10, origin + i * 0.01, 65, origin + i * 0.003);
                double dx = (moved.x - original.x) * ShadowVolume.MAP_SIZE / 2;
                double dy = (moved.y - original.y) * ShadowVolume.MAP_SIZE / 2;
                assertEquals(Math.rint(dx), dx, 0.001, "A camera translation may move an integer number of texels, not change phase");
                assertEquals(Math.rint(dy), dy, 0.001);
            }
        }
    }

    private static Vector3f projectWorld(double x, double y, double z, double camX, double camY, double camZ) {
        return ShadowVolume.lightMatrix(camX, camY, camZ).transformProject(new Vector3f((float)(x - camX), (float)(y - camY), (float)(z - camZ)));
    }

    @Test
    void nearestTexelPlaneComparisonRemovesSelfShadowingWithoutErasingABlocker() {
        // A synthetic sloped receiver models the staircase error visible in the supplied mask image.
        double u = 0.5003, v = 0.5006, z = 0.5, du = 0.55, dv = -0.8;
        int oldAcne = 0;
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            double sampleU = (Math.floor(u * ShadowVolume.MAP_SIZE) + x + 0.5) / ShadowVolume.MAP_SIZE;
            double sampleV = (Math.floor(v * ShadowVolume.MAP_SIZE) + y + 0.5) / ShadowVolume.MAP_SIZE;
            double surface = z + du * (sampleU - u) + dv * (sampleV - v);
            if (z - 0.0003 > surface) oldAcne++;
            double receiverAtTexel = z + du * (sampleU - u) + dv * (sampleV - v);
            assertFalse(receiverAtTexel - 0.00012 > surface);
            assertTrue(receiverAtTexel - 0.00012 > surface - 0.003, "A separate upstream blocker still casts a shadow");
        }
        assertTrue(oldAcne > 0, "The old center-depth comparison must reproduce the regression");
    }
    @Test
    void upstreamCasterProjectsToSameTexelAndSmallerDepthThanReceiver() {
        var light = ShadowVolume.lightDirection();
        var matrix = ShadowVolume.lightMatrix();
        var receiver = new Vector3f(5, -2, 7);
        var caster = new Vector3f(receiver).add(new Vector3f(light).mul(10));
        matrix.transformProject(receiver);
        matrix.transformProject(caster);
        assertEquals(receiver.x, caster.x, 0.000001f);
        assertEquals(receiver.y, caster.y, 0.000001f);
        assertTrue(caster.z < receiver.z - 0.001f, "Ordinary shadow depth requires LESS and receiver minus bias");
        assertTrue(caster.z > 0 && receiver.z < 1);
        assertFalse(receiver.z - 0.0003f > receiver.z, "The receiving surface must not shadow itself");
    }

    @Test
    void lightNearAndFarPlanesMapToZeroAndOne() {
        var light = ShadowVolume.lightDirection();
        var near = new Vector3f(light).mul(96 - ShadowVolume.NEAR);
        var far = new Vector3f(light).mul(96 - ShadowVolume.FAR);
        ShadowVolume.lightMatrix().transformProject(near);
        ShadowVolume.lightMatrix().transformProject(far);
        assertEquals(0, near.z, 0.000001f);
        assertEquals(1, far.z, 0.000001f);
    }

    @Test
    void reversedSceneDepthReconstructsWorldRelativePositionAfterCameraRotation() {
        // JOML's reversed perspective follows the vanilla near/far ordering with zero-to-one depth.
        var projection = new Matrix4f().perspective((float)Math.toRadians(70), 16f / 9, 256, 0.05f, true);
        var view = new Matrix4f().rotationY(0.4f).rotateX(-0.2f);
        var original = new Vector3f(3, -4, -20);
        var clip = new Matrix4f(projection).mul(view).transformProject(new Vector3f(original));
        assertTrue(clip.z > 0 && clip.z < 1);
        var reconstructed = new Matrix4f(view).invert().mul(new Matrix4f(projection).invert()).transformProject(clip);
        assertEquals(original.x, reconstructed.x, 0.0001f);
        assertEquals(original.y, reconstructed.y, 0.0001f);
        assertEquals(original.z, reconstructed.z, 0.0001f);
        // Integer origin subtraction keeps the same local geometry near +/- 30 million blocks.
        int chunkOrigin = 29_999_984;
        int cameraBlock = 29_999_990;
        float relative = 7.25f + (chunkOrigin - cameraBlock) - 0.5f;
        assertEquals(0.75f, relative);
    }
}
