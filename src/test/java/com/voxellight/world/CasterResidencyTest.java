package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CasterResidencyTest {
    private final SectionKey center = new SectionKey(0, 4, 0);
    private final SectionKey far = new SectionKey(3, 4, 0), mid = new SectionKey(2, 4, 0), near = new SectionKey(1, 4, 0);

    @Test void memoryPressureMakesRoomForNearGeometryWithoutDiscardingCloserOrEmptySections() {
        var resident = Map.of(far, 4L, mid, 4L, center, 8L, new SectionKey(3, 5, 3), 0L);
        assertEquals(List.of(far), CasterResidency.evictions(resident, near, center, 16, 4, 16));
        assertEquals(List.of(far, mid), CasterResidency.evictions(resident, near, center, 16, 8, 16));
        assertTrue(CasterResidency.evictions(resident, near, center, 16, 9, 16).isEmpty(), "An impossible fit must not erase existing valid shadows");
        assertTrue(CasterResidency.evictions(resident, far, center, 16, 1, 16).isEmpty());
        assertTrue(CasterResidency.evictions(resident, near, center, 8, 4, 16).isEmpty());
    }

    @Test void equalDistanceTieBreakCannotEvictEachOtherBackAndForth() {
        var a = new SectionKey(-1, 4, 0);
        var b = new SectionKey(1, 4, 0);
        assertEquals(List.of(b), CasterResidency.evictions(Map.of(b, 4L), a, center, 4, 4, 4));
        assertTrue(CasterResidency.evictions(Map.of(a, 4L), b, center, 4, 4, 4).isEmpty());
    }

    @Test void budgetFailureReportsRequestedBytesBeforeUploadAndAllowsSafeDeferral() {
        var failure = assertThrows(CasterGeometryBudget.Exceeded.class,
                () -> CasterGeometryBudget.check(CasterGeometryBudget.RESIDENT_BYTES, 0, 0, 1024));
        assertEquals(1024, failure.nextBytes());
        var tooLarge = assertThrows(CasterGeometryBudget.Exceeded.class,
                () -> CasterGeometryBudget.check(0, 0, 0, CasterGeometryBudget.SECTION_BYTES + 1));
        assertTrue(tooLarge.nextBytes() > CasterGeometryBudget.SECTION_BYTES);
    }
}
