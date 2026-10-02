package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalLightVolumeTest {
    private final LocalLightVolume volume = new LocalLightVolume();
    private final SectionKey center = new SectionKey(0, 4, 0);
    private LocalLightVolume.Emitter emitter(int x, int y, int z) { return new LocalLightVolume.Emitter(x, y, z, 14, 1, 0.6f, 0.2f); }

    @Test void everyVoxelHasOneUniqueInBoundsAtlasPixel() {
        var seen = new BitSet(LocalLightVolume.WIDTH * LocalLightVolume.HEIGHT);
        for (int y = 0; y < 80; y++) for (int z = 0; z < 80; z++) for (int x = 0; x < 80; x++) {
            int pixel = LocalLightVolume.atlasIndex(x, y, z);
            assertTrue(pixel >= 0 && pixel < 800 * 640);
            assertFalse(seen.get(pixel)); seen.set(pixel);
        }
        assertEquals(80 * 80 * 80, seen.cardinality());
        assertThrows(IndexOutOfBoundsException.class, () -> LocalLightVolume.atlasIndex(-1, 0, 0));
    }

    @Test void knownAirAndFullBlocksRemainDistinctFromUnknownAndUnloadedSpace() {
        var data = new int[4096]; data[SectionKey.blockIndex(1, 2, 3)] = OccluderShapes.FULL;
        var section = new LocalLightVolume.Section(center, data, List.of());
        volume.rebuild(List.of(section), center);
        assertTrue(volume.blocked(1, 66, 3));
        assertFalse(volume.blocked(2, 66, 3));
        assertTrue(volume.blocked(-1, 66, 3), "Uncaptured neighbor cannot admit a ray");
        assertTrue(volume.blocked(1000, 66, 3));
        data[SectionKey.blockIndex(1, 2, 3)] = 0;
        assertTrue(volume.blocked(1, 66, 3), "Section owns its capture");
        volume.rebuild(List.of(new LocalLightVolume.Section(center, data, List.of())), center);
        assertFalse(volume.blocked(1, 66, 3), "An edited wall is removed on rebuild");
        volume.rebuild(List.of(), center);
        assertTrue(volume.blocked(2, 66, 3), "Unloaded sections become unknown, not stale air");
    }

    @Test void rayCoordinatesAndEmitterPositionsPreserveBlocksAtNegativeWorldBorders() {
        var key = new SectionKey(-1874999, 4, -1874999);
        var e = emitter(key.x() * 16 + 1, 66, key.z() * 16 + 3);
        volume.rebuild(List.of(new LocalLightVolume.Section(key, new int[4096], List.of(e))), key);
        assertFalse(volume.blocked(e.x(), e.y(), e.z()));
        assertEquals((key.x() - 2) * 16, volume.originX());
        assertEquals(e, volume.select(e.x(), 66, e.z(), 0.1f).getFirst().emitter());
    }

    @Test void denseEmissiveScenesHaveDeterministicCapsAndFadeInsteadOfPopping() {
        var sources = new ArrayList<LocalLightVolume.Emitter>();
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) sources.add(emitter(x, 66, z));
        volume.rebuild(List.of(new LocalLightVolume.Section(center, new int[4096], sources)), center);
        var first = volume.select(4, 66, 4, 0.016f);
        assertEquals(16, first.size());
        assertEquals(64, volume.candidates());
        assertTrue(first.stream().allMatch(a -> a.weight() > 0 && a.weight() < 0.1));
        for (int i = 0; i < 20; i++) volume.select(4, 66, 4, 0.016f);
        var settled = volume.select(4, 66, 4, 0.016f);
        assertTrue(settled.stream().allMatch(a -> a.weight() == 1));
        assertEquals(settled.stream().map(LocalLightVolume.Active::emitter).toList(),
                volume.select(4.001, 66, 4.001, 0.016f).stream().map(LocalLightVolume.Active::emitter).toList());
        var far = volume.select(100, 66, 100, 0.016f);
        assertEquals(16, far.size());
        assertTrue(far.stream().allMatch(a -> a.weight() > 0.9 && a.weight() < 1));
        for (int i = 0; i < 20; i++) volume.select(100, 66, 100, 0.016f);
        assertTrue(volume.select(100, 66, 100, 0.016f).isEmpty());
    }

    @Test void removedEmittersAndWorldChangesCannotKeepLightingOldPositions() {
        var e = emitter(3, 66, 3);
        volume.rebuild(List.of(new LocalLightVolume.Section(center, new int[4096], List.of(e))), center);
        assertEquals(1, volume.select(3, 66, 3, 0.1f).size());
        volume.rebuild(List.of(new LocalLightVolume.Section(center, new int[4096], List.of())), center);
        assertTrue(volume.select(3, 66, 3, 0.1f).isEmpty());
        volume.clear();
        assertTrue(volume.select(3, 66, 3, 0.1f).isEmpty());
        assertEquals(0, volume.candidates());
    }
}
