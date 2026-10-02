package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicCasterSelectionTest {
    @Test void nearestAdmissionIsBoundedAndIndependentOfIterationOrder() {
        var a = new DynamicCasterSelection<Integer>(); var b = new DynamicCasterSelection<Integer>();
        for (int i=0;i<500;i++) a.consider(i, i, i * 4);
        for (int i=499;i>=0;i--) b.consider(i, i, i * 4);
        assertEquals(a.selected(), b.selected());
        assertEquals(32, a.selected().size()); assertEquals(468, a.overflow());
        assertEquals(0, a.selected().getFirst()); assertEquals(31, a.selected().getLast());
    }
    @Test void equalDistancesHaveStableIdOrderAndInvalidOrFarCandidatesAreExcluded() {
        var selection = new DynamicCasterSelection<Integer>();
        selection.consider(3,3,10); selection.consider(1,1,10); selection.consider(2,2,10);
        selection.consider(4,4,4097); selection.consider(5,5,Double.NaN); selection.consider(6,6,-1);
        assertEquals(List.of(1,2,3), selection.selected()); assertEquals(3, selection.candidates()); assertEquals(0, selection.overflow());
    }
    @Test void aNewFrameDoesNotRetainRemovedEntities() {
        var first = new DynamicCasterSelection<Integer>(); first.consider(1,1,10);
        var next = new DynamicCasterSelection<Integer>(); next.consider(2,2,10);
        assertEquals(List.of(2), next.selected());
    }
}
