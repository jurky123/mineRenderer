package com.voxellight.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CasterGeometryBudgetTest {
    private static final long MIB = 1024 * 1024;

    @Test
    void replacementAtFullResidencyAllowsTemporaryOverlapButNotPermanentGrowth() {
        // A full 32 MiB scene can stage two 4 MiB replacements while its old 8 MiB batch is still alive.
        assertDoesNotThrow(() -> CasterGeometryBudget.check(32 * MIB, 8 * MIB, 0, 4 * MIB));
        assertDoesNotThrow(() -> CasterGeometryBudget.check(32 * MIB, 8 * MIB, 4 * MIB, 4 * MIB));
        assertThrows(CasterGeometryBudget.Exceeded.class, () -> CasterGeometryBudget.check(32 * MIB, 7 * MIB, 4 * MIB, 4 * MIB));
    }

    @Test
    void stagingAndSectionCapsRemainEnforcedWhenMostOldGeometryWillShrink() {
        assertThrows(CasterGeometryBudget.Exceeded.class, () -> CasterGeometryBudget.check(32 * MIB, 32 * MIB, 8 * MIB, 1));
        assertThrows(CasterGeometryBudget.Exceeded.class, () -> CasterGeometryBudget.check(0, 0, 0, 4 * MIB + 1));
        assertDoesNotThrow(() -> CasterGeometryBudget.check(32 * MIB, 4 * MIB, 0, 0));
    }

    @Test
    void streamingStillHasTheOriginalResidentCapAndInvalidAccountingIsRejected() {
        assertDoesNotThrow(() -> CasterGeometryBudget.check(28 * MIB, 0, 0, 4 * MIB));
        assertThrows(CasterGeometryBudget.Exceeded.class, () -> CasterGeometryBudget.check(28 * MIB + 1, 0, 0, 4 * MIB));
        assertThrows(IllegalArgumentException.class, () -> CasterGeometryBudget.check(0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CasterGeometryBudget.check(0, 0, -1, 0));
    }
}
