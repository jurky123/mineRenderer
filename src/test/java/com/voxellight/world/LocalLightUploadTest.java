package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalLightUploadTest {
    private final LocalLightVolume volume = new LocalLightVolume();
    private final int[] gpu = new int[LocalLightVolume.WIDTH * LocalLightVolume.HEIGHT];
    private final SectionKey center = new SectionKey(0, 0, 0);
    private void apply(List<LocalLightVolume.Upload> regions) {
        for (var r : regions) {
            var bytes = ByteBuffer.allocate(r.bytes()); volume.writeRegion(bytes, r); bytes.flip();
            for (int y = r.y(); y < r.y() + r.height(); y++) for (int x = r.x(); x < r.x() + r.width(); x++)
                gpu[y * LocalLightVolume.WIDTH + x] = bytes.getInt();
        }
        assertArrayEquals(volume.atlas(), gpu);
    }
    @Test void editUnloadAndRecenterMatchCompleteAtlas() {
        var air = new LocalLightVolume.Section(center, new int[4096], List.of());
        var first = volume.rebuild(List.of(air), center); assertEquals(LocalLightVolume.ATLAS_BYTES, first.getFirst().bytes()); apply(first);
        var changed = new int[4096]; Arrays.fill(changed, 33);
        var wall = new LocalLightVolume.Section(center, changed, List.of());
        var edit = volume.rebuild(List.of(wall), center);
        assertEquals(4096 * 4, edit.stream().mapToInt(LocalLightVolume.Upload::bytes).sum()); apply(edit);
        assertTrue(volume.rebuild(List.of(new LocalLightVolume.Section(center, changed, List.of(new LocalLightVolume.Emitter(0,0,0,14,1,1,1)))), center).isEmpty());
        apply(volume.rebuild(List.of(), center));
        var moved = volume.rebuild(List.of(wall), new SectionKey(1, 0, 0));
        assertEquals(LocalLightVolume.ATLAS_BYTES, moved.getFirst().bytes()); apply(moved);
    }
    @Test void widespreadUpdatesUseBoundedFullUploadFallback() {
        apply(volume.rebuild(List.of(), center));
        var sections = new ArrayList<LocalLightVolume.Section>();
        for (int y=-2;y<=2;y++) for(int z=-2;z<=2;z++) for(int x=-2;x<=2;x++)
            if ((x+y+z)%2==0) sections.add(new LocalLightVolume.Section(new SectionKey(x,y,z),new int[4096],List.of()));
        var regions = volume.rebuild(sections, center); assertTrue(regions.size() <= 64); apply(regions);
    }
}
