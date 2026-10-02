package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class LightUpdateScopeTest {
    @Test
    void lightPacketRebuildCannotInvalidateCasterGeometryButActualEditStillDoes() {
        var scope = new LightUpdateScope();
        var scene = new WorldSceneBridge(1);
        var key = new SectionKey(0, 4, 0);
        scene.reconcile(List.of(key));
        var token = scene.geometryToken(key);
        scope.run(() -> scene.markDirty(key, scope.sectionReason(WorldSceneBridge.GEOMETRY)));
        assertTrue(scene.isCurrent(token), "Network relighting must not retire current geometry");
        scene.markDirty(key, scope.sectionReason(WorldSceneBridge.GEOMETRY));
        assertFalse(scene.isCurrent(token), "An actual geometry update outside the packet scope must still invalidate");
    }

    @Test
    void nestedScopesAndExceptionsAlwaysRestoreClassification() {
        var scope = new LightUpdateScope();
        assertThrows(IllegalStateException.class, () -> scope.run(() -> {
            scope.run(() -> assertEquals(WorldSceneBridge.LIGHT, scope.sectionReason(WorldSceneBridge.GEOMETRY)));
            assertEquals(WorldSceneBridge.LIGHT, scope.sectionReason(WorldSceneBridge.GEOMETRY));
            assertEquals(WorldSceneBridge.RESOURCE, scope.sectionReason(WorldSceneBridge.RESOURCE));
            throw new IllegalStateException("Interrupted packet processing");
        }));
        assertEquals(WorldSceneBridge.GEOMETRY, scope.sectionReason(WorldSceneBridge.GEOMETRY));
    }

    @Test
    void lightScopeOnOneThreadCannotHideGeometryEditsOnAnother() {
        var scope = new LightUpdateScope();
        scope.run(() -> assertEquals(WorldSceneBridge.GEOMETRY,
                CompletableFuture.supplyAsync(() -> scope.sectionReason(WorldSceneBridge.GEOMETRY)).join()));
    }
}
