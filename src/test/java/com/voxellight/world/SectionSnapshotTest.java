package com.voxellight.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionSnapshotTest {
    @Test
    void paletteOwnershipAndOccupancySurviveMutationsOfInputArrays() {
        var indices = new short[4096];
        indices[4095] = 1;
        var ids = new int[]{0, 17};
        var flags = new byte[]{0, SectionSnapshot.FULL_OCCLUDER | SectionSnapshot.NON_AIR};
        var emissions = new byte[]{0, 15};
        var request = new WorldSceneBridge.Request(new SectionKey(-1, -4, 1), 1, 1, 1, WorldSceneBridge.LOAD);
        var snapshot = new SectionSnapshot(request, indices, ids, flags, emissions);
        indices[4095] = 0;
        ids[1] = 999;
        flags[1] = 0;
        emissions[1] = 0;
        assertTrue(snapshot.fullOccluder(4095));
        assertFalse(snapshot.fullOccluder(4094));
        assertEquals(new SectionSnapshot.Material(17, 3, 15), snapshot.material(4095));
        assertEquals(1, snapshot.fullCount());
        assertEquals(1, snapshot.nonAirCount());
        assertEquals(1, snapshot.emissiveCount());
        assertEquals(8_716, snapshot.payloadBytes());
    }

    @Test
    void coordinateMappingIncludesNegativeBlocksAndSectionBoundaries() {
        assertEquals(new SectionKey(-1, -1, -2), SectionKey.fromBlock(-1, -16, -17));
        assertEquals(4095, SectionKey.blockIndex(-1, -1, -1));
        assertEquals(0, SectionKey.blockIndex(16, 32, -16));
        assertEquals(256, SectionKey.blockIndex(0, 1, 0));
    }

    @Test
    void invalidPaletteCannotPublishBrokenSectionData() {
        var request = new WorldSceneBridge.Request(new SectionKey(0, 0, 0), 1, 1, 1, 1);
        var invalidIndices = new short[4096];
        invalidIndices[64] = -1;
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(request,
                invalidIndices, new int[]{0}, new byte[]{0}, new byte[]{0}));
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(request,
                new short[4096], new int[]{0}, new byte[]{0}, new byte[]{16}));
    }
}
