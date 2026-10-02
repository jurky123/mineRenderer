package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class CasterBoundsTest {
    @Test
    void actualVerticesIncludeExtendedModelsAndKeepLargeWorldPrecision() {
        var vertices = ByteBuffer.allocate(8 + 2 * 28).order(ByteOrder.nativeOrder());
        vertices.position(8);
        vertices.putFloat(-2.25f).putFloat(17.5f).putFloat(3.125f);
        vertices.position(8 + 28);
        vertices.putFloat(20.25f).putFloat(-1.5f).putFloat(19.75f);
        vertices.position(8);
        var section = new SectionKey(1_874_999, 4, -1_875_000);
        var bounds = CasterBounds.fromVertices(section, vertices, 28);
        assertEquals(29_999_981.75, bounds.minX());
        assertEquals(30_000_004.25, bounds.maxX());
        assertEquals(62.5, bounds.minY());
        assertEquals(81.5, bounds.maxY());
        assertEquals(-29_999_996.875, bounds.minZ());
        assertEquals(-29_999_980.25, bounds.maxZ());
        assertEquals(8, vertices.position(), "Extraction must not consume the upload buffer");
        assertEquals(64, vertices.limit());
    }

    @Test
    void unionIncludesBothSolidAndCutoutFootprints() {
        var solid = new CasterBounds(0, 0, 0, 1, 1, 1);
        var cutout = new CasterBounds(-4, 0.5, 0.25, 2, 3, 1.5);
        assertEquals(new CasterBounds(-4, 0, 0, 2, 3, 1.5), solid.union(cutout));
    }

    @Test
    void malformedOrNonfiniteVerticesCannotPublishAnUnsafeCacheFootprint() {
        var section = new SectionKey(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> CasterBounds.fromVertices(section, ByteBuffer.allocate(29), 28));
        assertThrows(IllegalArgumentException.class, () -> CasterBounds.fromVertices(section, ByteBuffer.allocate(0), 28));
        var vertices = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
        vertices.putFloat(Float.NaN).putFloat(0).putFloat(0).position(0);
        assertThrows(IllegalArgumentException.class, () -> CasterBounds.fromVertices(section, vertices, 28));
    }
}
