package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowAnchorTest {
    @Test void crossingTheOldFloorBoundaryCannotAlternateBetweenMaps() {
        var anchor = new ShadowAnchor();
        var initial = anchor.update(7.9, 64, -0.1);
        for (int i = 0; i < 100; i++) {
            assertEquals(initial, anchor.update(i % 2 == 0 ? 7.99 : 8.01, 64, i % 2 == 0 ? -0.01 : 0.01));
        }
        var moved = anchor.update(17, 64, 0);
        assertNotEquals(initial, moved);
        assertEquals(moved, anchor.update(15.99, 64, 0));
        anchor.clear();
        assertNotEquals(moved, anchor.update(0, 64, 0));
    }
    @Test void movementKeepsVolumeCenterWithinEightBlocksAtWorldBorders() {
        for (int origin : new int[]{0, 29999984, -29999984}) {
            var anchor = new ShadowAnchor();
            for (int i = -100; i <= 100; i++) {
                double x = origin + i * 0.17, y = 64 + i * 0.09, z = origin - i * 0.13;
                var a = anchor.update(x, y, z);
                assertTrue(Math.abs(x - a.x()) <= 8);
                assertTrue(Math.abs(y - a.y()) <= 8);
                assertTrue(Math.abs(z - a.z()) <= 8);
            }
        }
    }
}
