package com.voxellight.world;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BorrowedCasterVolumeTest {
    @Test void lowSunSelectsUpstreamDistantCastersAndRejectsUnrelatedSections() {
        var light = new Vector3f(1, .1f, 0).normalize();
        assertTrue(BorrowedCasterVolume.intersects(208, 0, 0, light, 128));
        assertFalse(BorrowedCasterVolume.intersects(-208, 0, 0, light, 128));
        assertFalse(BorrowedCasterVolume.intersects(0, 0, 240, light, 128));
    }

    @Test void conservativeSectionSphereCannotRejectAnIntersectingBox() {
        for (var light : new Vector3f[]{new Vector3f(1, .1f, 0).normalize(), new Vector3f(0, 1, 0),
                new Vector3f(-.7f, .2f, .6f).normalize()}) {
            for (int x = -10; x <= 15; x++) for (int y = -10; y <= 15; y++) for (int z = -10; z <= 10; z++) {
                if (CasterVolume.distanceSquared(x, y, z, light, BorrowedCasterVolume.EXTRUSION) <= 128 * 128) {
                    assertTrue(BorrowedCasterVolume.intersects(x * 16, y * 16, z * 16, light, 128),
                            "Borrowed lookup must include every section box intersecting the swept receiver sphere");
                }
            }
        }
    }

    @Test void extendingNativeShadowsDoesNotExpandTheDuplicateSceneBridge() {
        assertEquals(128, ShadowCascades.RADIUS);
        assertEquals(384, CasterVolume.MAX_SECTIONS);
        var local = CasterVolume.select(new SectionKey(0, 0, 0), ShadowLight.fixed(), true);
        assertFalse(local.candidates().contains(new SectionKey(10, 0, 0)));
        assertEquals(2048, ShadowCascades.range(0).mapSize());
        assertEquals(32, ShadowCascades.range(0).halfExtent());
    }
}
