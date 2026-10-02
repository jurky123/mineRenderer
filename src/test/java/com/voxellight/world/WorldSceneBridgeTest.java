package com.voxellight.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldSceneBridgeTest {
    private static final SectionKey A = new SectionKey(0, 4, 0);
    private static final SectionKey B = new SectionKey(1, 4, 0);

    private static SectionSnapshot empty(WorldSceneBridge.Request request) {
        return new SectionSnapshot(request, new short[4096], new int[]{0}, new byte[]{0}, new byte[]{0});
    }

    @Test
    void historyRevisionChangesOnEditsUnloadsAndGenerationsButNotRepeatedReconcileOrAcquire() {
        var scene=new WorldSceneBridge(2);
        long initial=scene.changeRevision();scene.reconcile(List.of(A));assertTrue(scene.changeRevision()>initial);
        long loaded=scene.changeRevision();scene.reconcile(List.of(A));scene.acquire();assertEquals(loaded,scene.changeRevision());
        scene.markDirty(A,WorldSceneBridge.LIGHT);assertTrue(scene.changeRevision()>loaded);
        long light=scene.changeRevision();scene.markDirty(A,WorldSceneBridge.GEOMETRY);assertTrue(scene.changeRevision()>light);
        long edited=scene.changeRevision();scene.unload(A);assertTrue(scene.changeRevision()>edited);
        long removed=scene.changeRevision();scene.reloadResources();assertTrue(scene.changeRevision()>removed);
        long resources=scene.changeRevision();scene.changeWorld();assertTrue(scene.changeRevision()>resources);
    }

    @Test
    void liveGeometryTokenIsReadyDuringPaletteEncodingAndSurvivesLightCompletion() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var encoding = scene.acquire();
        var token = scene.geometryToken(A);
        assertNotNull(token);
        assertNull(scene.snapshot(A));
        assertTrue(scene.isCurrent(token), "The client model compiler can run before occupancy encoding completes");
        scene.markDirty(A, WorldSceneBridge.LIGHT);
        assertTrue(scene.isCurrent(token));
        assertFalse(scene.complete(encoding, empty(encoding)));
        assertTrue(scene.isCurrent(token));
        var fresh = scene.acquire();
        assertTrue(scene.complete(fresh, empty(fresh)));
        assertTrue(scene.isCurrent(token));
    }

    @Test
    void anyChangedMemberPreventsPublishingAnObsoleteReplacementGroup() {
        var scene = new WorldSceneBridge(2);
        scene.reconcile(List.of(A, B));
        scene.markRangeDirty(0, 4, 0, 1, 4, 0, WorldSceneBridge.GEOMETRY);
        var group = List.of(scene.geometryToken(A), scene.geometryToken(B));
        assertTrue(group.stream().allMatch(scene::isCurrent));
        scene.markDirty(B, WorldSceneBridge.GEOMETRY);
        assertFalse(group.stream().allMatch(scene::isCurrent));
        assertTrue(scene.isCurrent(group.getFirst()));
        assertFalse(scene.isCurrent(group.getLast()));
    }

    @Test
    void geometryTokensCannotCrossReloadUnloadOrWorldGenerations() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var token = scene.geometryToken(A);
        scene.reloadResources();
        assertFalse(scene.isCurrent(token));
        token = scene.geometryToken(A);
        scene.unload(A);
        assertFalse(scene.isCurrent(token));
        assertNull(scene.geometryToken(A));
        scene.reconcile(List.of(A));
        token = scene.geometryToken(A);
        scene.changeWorld();
        scene.reconcile(List.of(A));
        assertFalse(scene.isCurrent(token));
    }

    @Test
    void lightUpdatesPreserveCasterVersionButGeometryReloadAndWorldChangesDoNot() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        long original = scene.geometryVersion(A);
        assertTrue(original > 0);
        scene.markDirty(A, WorldSceneBridge.LIGHT);
        assertEquals(original, scene.geometryVersion(A));
        scene.markDirty(A, WorldSceneBridge.GEOMETRY);
        assertTrue(scene.geometryVersion(A) > original);
        long edited = scene.geometryVersion(A);
        scene.reloadResources();
        assertTrue(scene.geometryVersion(A) > edited);
        long reloaded = scene.geometryVersion(A);
        scene.changeWorld();
        assertEquals(-1, scene.geometryVersion(A));
        scene.reconcile(List.of(A));
        assertTrue(scene.geometryVersion(A) > reloaded, "Same coordinate in another world must not reuse a caster");
        scene.unloadChunk(A.x(), A.z());
        assertEquals(-1, scene.geometryVersion(A));
    }

    @Test
    void staleSnapshotCannotAcquireANewCasterGeometryToken() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var request = scene.acquire();
        var old = empty(request);
        assertTrue(scene.complete(request, old));
        assertEquals(scene.geometryVersion(A), scene.geometryVersion(old));
        scene.markDirty(A, WorldSceneBridge.GEOMETRY);
        assertEquals(-1, scene.geometryVersion(old));
        var replacement = scene.acquire();
        var fresh = empty(replacement);
        assertTrue(scene.complete(replacement, fresh));
        assertEquals(-1, scene.geometryVersion(old));
        assertEquals(scene.geometryVersion(A), scene.geometryVersion(fresh));
    }

    @Test
    void editStormMergesMarkersAndRejectsAnObsoleteWorkerResult() {
        var scene = new WorldSceneBridge(2);
        scene.reconcile(List.of(A, B));
        var old = scene.acquire();
        for (int i = 0; i < 10_000; i++) scene.markDirty(A, WorldSceneBridge.GEOMETRY);
        assertEquals(2, scene.stats().tracked());
        assertEquals(1, scene.stats().inFlight());
        assertEquals(2, scene.stats().dirty());
        assertFalse(scene.complete(old, empty(old)));
        assertNull(scene.snapshot(A));
        var next = scene.acquire();
        assertEquals(B, next.key(), "Older pending work must not starve behind repeatedly edited A");
        assertTrue(scene.complete(next, empty(next)));
        var latest = scene.acquire();
        assertEquals(A, latest.key());
        assertNotEquals(old.version(), latest.version());
        assertEquals(WorldSceneBridge.LOAD | WorldSceneBridge.GEOMETRY, latest.reasons());
        assertTrue(scene.complete(latest, empty(latest)));
        assertEquals(0, scene.stats().dirty());
        assertEquals(2, scene.stats().resident());
        assertTrue(scene.stats().coalesced() >= 9_999);
    }

    @Test
    void unloadAndReloadAtTheSameCoordinateCannotAcceptOldDataOrClearNewJob() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var old = scene.acquire();
        scene.unloadChunk(0, 0);
        scene.reconcile(List.of(A));
        var fresh = scene.acquire();
        assertFalse(scene.complete(old, empty(old)));
        assertEquals(1, scene.stats().inFlight());
        assertTrue(scene.complete(fresh, empty(fresh)));
        assertEquals(fresh, scene.snapshot(A).request());
    }

    @Test
    void resourceAndWorldGenerationsInvalidateSnapshotsAndRunningWork() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var first = scene.acquire();
        assertTrue(scene.complete(first, empty(first)));
        scene.reloadResources();
        assertNull(scene.snapshot(A));
        var duringReload = scene.acquire();
        scene.reloadResources();
        assertFalse(scene.complete(duringReload, empty(duringReload)));
        var afterReload = scene.acquire();
        assertTrue(afterReload.resourceGeneration() > duringReload.resourceGeneration());
        scene.changeWorld();
        scene.reconcile(List.of(A));
        var newWorld = scene.acquire();
        assertFalse(scene.complete(afterReload, empty(afterReload)));
        assertEquals(1, scene.stats().inFlight());
        assertTrue(scene.complete(newWorld, empty(newWorld)));
        assertTrue(newWorld.worldGeneration() > first.worldGeneration());
    }

    @Test
    void rangeUpdatesAreLimitedToResidentWindowAndCombineDirtyReasons() {
        var scene = new WorldSceneBridge(2);
        scene.reconcile(List.of(A, B));
        scene.markRangeDirty(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, WorldSceneBridge.LIGHT);
        scene.markDirty(A, WorldSceneBridge.GEOMETRY);
        scene.markDirty(new SectionKey(9999, 0, 0), WorldSceneBridge.GEOMETRY);
        var request = scene.acquire();
        assertEquals(A, request.key());
        assertEquals(WorldSceneBridge.LOAD | WorldSceneBridge.GEOMETRY | WorldSceneBridge.LIGHT, request.reasons());
        assertEquals(2, scene.stats().tracked());
        scene.reconcile(List.of(B));
        assertFalse(scene.complete(request, empty(request)));
        assertNull(scene.snapshot(A));
    }

    @Test
    void exceedingCapacityDoesNotMutateExistingWindow() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        assertThrows(IllegalArgumentException.class, () -> scene.reconcile(List.of(A, B)));
        assertEquals(1, scene.stats().tracked());
        assertEquals(A, scene.acquire().key());
    }

    @Test
    void failedEncodingRetriesLatestStateAndDirtySnapshotsAreUnavailable() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var request = scene.acquire();
        assertFalse(scene.complete(request, null));
        var retry = scene.acquire();
        assertNotNull(retry);
        assertTrue(scene.complete(retry, empty(retry)));
        assertTrue(scene.stats().payloadBytes() > 0);
        scene.markDirty(A, WorldSceneBridge.LIGHT);
        assertNull(scene.snapshot(A));
        assertEquals(0, scene.stats().payloadBytes());
    }

    @Test
    void packedSurfaceLightInvalidatesWithoutInvalidatingShadowGeometry() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        var geometry = scene.geometryToken(A);
        var material = scene.surfaceToken(A);
        scene.markDirty(A,WorldSceneBridge.LIGHT);
        assertTrue(scene.isCurrent(geometry));
        assertFalse(scene.isCurrent(material));
        var replacement = scene.surfaceToken(A);
        assertTrue(scene.isCurrent(replacement));
        scene.reloadResources();
        assertFalse(scene.isCurrent(replacement));
        scene.changeWorld();
        assertNull(scene.surfaceToken(A));
    }

    @Test
    void supersedingLightUpdateDoesNotEraseAnUnpublishedGeometryChange() {
        var scene = new WorldSceneBridge(1);
        scene.reconcile(List.of(A));
        scene.markDirty(A, WorldSceneBridge.GEOMETRY);
        var geometry = scene.acquire();
        scene.markDirty(A, WorldSceneBridge.LIGHT);
        assertFalse(scene.complete(geometry, empty(geometry)));
        var replacement = scene.acquire();
        assertEquals(WorldSceneBridge.LOAD | WorldSceneBridge.GEOMETRY | WorldSceneBridge.LIGHT, replacement.reasons());
    }
}
