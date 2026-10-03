package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShadowMapCacheTest {
    private final ShadowMapCache cache = new ShadowMapCache();
    private final ShadowMapCache.Anchor initial = new ShadowMapCache.Anchor(0, 64, 0);
    private final CasterBounds local = new CasterBounds(4, 65, 3, 6, 67, 5);

    private void commit(ShadowMapCache.Update update) {
        update.regions().forEach(cache::rendered);
        cache.finishFrame(update);
    }
    private void warm() { commit(cache.plan(initial, true, List.of())); }

    @Test
    void warmStaticMapIsReusableWithoutChangingItsContents() {
        var first = cache.plan(initial, true, List.of());
        assertEquals(64, first.pages());
        assertEquals(List.of(new ShadowMapCache.Rect(0, 0, 2048, 2048)), first.regions());
        commit(first);
        for (int frame = 0; frame < 100; frame++) {
            var next = cache.plan(initial, true, List.of());
            assertEquals(0, next.pages());
            assertTrue(next.regions().isEmpty());
            commit(next);
        }
        assertEquals(1, cache.renders());
        assertEquals(100, cache.reuses());
        assertEquals(6400, cache.pageReuses());
    }

    @Test
    void editsInvalidateOnlyTheirProjectedTilesAndRepeatedEditsMerge() {
        warm();
        cache.invalidate(local);
        var changed = cache.plan(initial, true, List.of());
        assertTrue(changed.pages() > 0 && changed.pages() < 64);
        cache.invalidate(local);
        assertEquals(changed.pages(), cache.plan(initial, true, List.of()).pages());
        // A prepared pass is not published; if recording failed, the next plan must still update those tiles.
        assertEquals(changed.regions(), cache.plan(initial, true, List.of()).regions());
        commit(changed);
        assertEquals(0, cache.plan(initial, true, List.of()).pages());
        assertEquals(64 + changed.pages(), cache.pageUpdates());
    }

    @Test
    void allVolumeBoundariesAndLifecycleResetInvalidateTheWholeMap() {
        for (var moved : List.of(new ShadowMapCache.Anchor(8, 64, 0), new ShadowMapCache.Anchor(0, 72, 0), new ShadowMapCache.Anchor(0, 64, -8))) {
            warm();
            var changed = cache.plan(moved, true, List.of());
            assertEquals(64, changed.pages());
            assertEquals("volume moved", changed.reason());
            commit(changed);
            cache.clear();
            assertEquals(0, cache.renders());
            assertEquals(64, cache.plan(initial, true, List.of()).pages());
        }
    }

    @Test
    void cutoutRefreshesOnlyItsFootprintWhileReferenceRedrawsEverything() {
        warm();
        for (int frame = 0; frame < 10; frame++) {
            var update = cache.plan(initial, true, List.of(local));
            assertTrue(update.pages() > 0 && update.pages() < 64);
            assertEquals("cutout animation safety", update.reason());
            commit(update);
        }
        assertEquals(0, cache.plan(initial, true, List.of()).pages());
        var reference = cache.plan(initial, false, List.of(local));
        assertEquals(64, reference.pages());
        assertEquals("reference redraw", reference.reason());
    }

    @Test
    void offMapChangesAndEmptyMeshesCannotInvalidateVisibleTiles() {
        warm();
        cache.invalidate(null);
        var outside = new CasterBounds(10000, 64, 0, 10001, 65, 1);
        assertNull(cache.project(outside));
        cache.invalidate(outside);
        assertEquals(0, cache.plan(initial, true, List.of(outside)).pages());
    }

    @Test
    void suspendingDirectionalShadowsDoesNotPublishDirtyPagesOrInventReuse() {
        warm();
        cache.invalidate(local);
        var pending = cache.plan(initial, true, List.of());
        long renders = cache.renders(), reuses = cache.reuses();
        cache.suspend();
        assertEquals(0, cache.updatedPages());
        assertEquals(renders, cache.renders());
        assertEquals(reuses, cache.reuses());
        assertEquals(pending.regions(), cache.plan(initial, true, List.of()).regions());
    }

    @Test
    void mergedRegionsCoverEveryDirtyTileExactlyOnce() {
        warm();
        var footprints = new ArrayList<ShadowMapCache.Rect>();
        for (int x = -32; x <= 32; x += 8) {
            var bounds = new CasterBounds(x, 64 + x / 4.0, -x / 3.0, x + 1, 65 + x / 4.0, 1 - x / 3.0);
            cache.invalidate(bounds);
            footprints.add(cache.project(bounds));
        }
        var update = cache.plan(initial, true, List.of());
        boolean[] visited = new boolean[64];
        for (var region : update.regions()) {
            assertEquals(0, region.x() % 256); assertEquals(0, region.y() % 256);
            assertEquals(0, region.width() % 256); assertEquals(0, region.height() % 256);
            for (int y = region.y() / 256; y < (region.y() + region.height()) / 256; y++) {
                for (int x = region.x() / 256; x < (region.x() + region.width()) / 256; x++) {
                    int index = y * 8 + x;
                    assertFalse(visited[index], "No overlapping updates");
                    visited[index] = true;
                    var tile = new ShadowMapCache.Rect(x * 256, y * 256, 256, 256);
                    assertTrue(footprints.stream().anyMatch(tile::intersects), "Must not clear an unrelated tile");
                }
            }
        }
        int count = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            var tile = new ShadowMapCache.Rect(x * 256, y * 256, 256, 256);
            assertEquals(footprints.stream().anyMatch(tile::intersects), visited[y * 8 + x]);
            if (visited[y * 8 + x]) count++;
        }
        assertEquals(count, update.pages());
    }

    @Test void progressiveBudgetDoesNotPublishUnrenderedTiles() {
        var budgeted=new ShadowMapCache(0);
        var light=new ShadowLight(ShadowLight.Source.SUN,0,.45f,.7);
        int total=0;
        for(int frame=0;frame<16;frame++) {
            var update=budgeted.plan(initial,light,true,List.of(),4);
            assertEquals(4,update.pages());
            int area=update.regions().stream().mapToInt(rect->rect.width()*rect.height()/256/256).sum();
            assertEquals(4,area);
            // Planning again before recording cannot discard scheduled tiles.
            assertEquals(update.regions(),budgeted.plan(initial,light,true,List.of(),4).regions());
            update.regions().forEach(budgeted::rendered);total+=update.pages();
            assertEquals(frame<15,budgeted.hasPending());
        }
        assertEquals(64,total);assertEquals(0,budgeted.plan(initial,light,true,List.of(),4).pages());
        budgeted.invalidate(local);
        var edit=budgeted.plan(initial,light,true,List.of());
        assertTrue(edit.pages()>0);edit.regions().forEach(budgeted::rendered);assertFalse(budgeted.hasPending());
        budgeted.resetValidity();assertEquals(64,budgeted.plan(initial,light,true,List.of()).pages());
    }

    private record Blocker(CasterBounds bounds, float depth) { }
    private float reference(List<Blocker> casters, int x, int y) {
        float depth = 1;
        var pixel = new ShadowMapCache.Rect(x, y, 1, 1);
        for (var caster : casters) {
            var rect = cache.project(caster.bounds());
            if (rect != null && rect.intersects(pixel)) depth = Math.min(depth, caster.depth());
        }
        return depth;
    }

    @Test
    void selectiveClearAndRedrawMatchesFullReferenceAfterAddingAndRemovingOverlappingCasters() {
        warm();
        var front = new Blocker(local, 0.2f);
        var behind = new Blocker(new CasterBounds(2, 63, 1, 9, 69, 8), 0.7f);
        var original = List.of(front, behind);
        var added = new Blocker(new CasterBounds(-10, 64, -5, -5, 68, 0), 0.1f);
        // Sparse software depth map: independent reference reduction, covering texel centers across all 64 tiles.
        for (var current : List.of(List.of(behind), List.of(behind, added))) {
            cache.invalidate(front.bounds()); cache.invalidate(added.bounds());
            var update = cache.plan(initial, true, List.of());
            int revealed = 0;
            for (int y = 8; y < 2048; y += 16) for (int x = 8; x < 2048; x += 16) {
                float old = reference(original, x, y);
                float expected = reference(current, x, y);
                var pixel = new ShadowMapCache.Rect(x, y, 1, 1);
                boolean redrawn = update.regions().stream().anyMatch(region -> region.intersects(pixel));
                float actual = redrawn ? reference(current, x, y) : old;
                assertEquals(expected, actual, "Removing nearest caster must reveal current farther caster, not erase it or retain old depth");
                if (old == 0.2f && expected == 0.7f) revealed++;
            }
            assertTrue(revealed > 0);
            commit(update);
        }
    }
}
