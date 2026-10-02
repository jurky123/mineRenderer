package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OccluderShapesTest {
    @Test void canonicalIdsAndPackedBoundsStayStable() {
        var palette = new OccluderShapes();
        var slab = new OccluderShapes.Box(0, 0, 0, 1, .5f, 1);
        var rail = new OccluderShapes.Box(.4f, .5f, 0, .6f, 1, 1);
        int word = palette.register(List.of(slab, rail));
        assertEquals(2, word & 31);
        assertEquals(1, word >>> 5);
        assertEquals(word, palette.register(List.of(rail, slab, slab)));
        var bytes = ByteBuffer.allocate(OccluderShapes.WIDTH * 16);
        palette.writeRows(bytes, 1, 2); bytes.flip();
        assertEquals(0, bytes.getFloat()); assertEquals(0, bytes.getFloat()); assertEquals(0, bytes.getFloat()); bytes.getFloat();
        assertEquals(1, bytes.getFloat()); assertEquals(.5f, bytes.getFloat()); assertEquals(1, bytes.getFloat());
        assertEquals(OccluderShapes.EMPTY, palette.register(List.of()));
        assertEquals(OccluderShapes.FULL, palette.register(List.of(new OccluderShapes.Box(-1, 0, 0, 2, 1, 1))));
    }
    @Test void paletteOverflowIsBoundedAndConservative() {
        var palette = new OccluderShapes();
        for (int i = 1; i < OccluderShapes.MAX_SHAPES; i++)
            assertTrue(palette.register(List.of(new OccluderShapes.Box(0, 0, 0, 1, i / 2048f, 1))) > 0);
        assertEquals(OccluderShapes.FULL, palette.register(List.of(new OccluderShapes.Box(0, 0, 0, 1, .9f, 1))));
        assertEquals(1024, palette.rowCount()); assertEquals(1, palette.paletteOverflows());
        palette.clear(); assertEquals(1, palette.rowCount()); assertEquals(0, palette.paletteOverflows());
    }
    @Test void complexShapesUseConservativeCombinedBounds() {
        var palette = new OccluderShapes(); var boxes = new ArrayList<OccluderShapes.Box>();
        for (int i = 0; i < 17; i++) boxes.add(new OccluderShapes.Box(i / 32f, 0, 0, (i + 1) / 32f, .5f, 1));
        assertEquals(1, palette.register(boxes) & 31); assertEquals(1, palette.complexFallbacks());
    }
}
