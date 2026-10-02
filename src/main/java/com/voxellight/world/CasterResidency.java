package com.voxellight.world;

import java.util.*;

/** Geometry pressure may evict farther casters, but never oscillate equal-priority entries. */
public final class CasterResidency {
    private CasterResidency() { }
    public static Comparator<SectionKey> priority(SectionKey center) {
        return Comparator.<SectionKey>comparingLong(key -> {
            long x = key.x() - (long)center.x(), y = key.y() - (long)center.y(), z = key.z() - (long)center.z();
            return x * x + y * y + z * z;
        }).thenComparingInt(SectionKey::x).thenComparingInt(SectionKey::y).thenComparingInt(SectionKey::z);
    }
    public static List<SectionKey> evictions(Map<SectionKey, Long> meshes, SectionKey incoming, SectionKey center,
                                              long resident, long next, long limit) {
        if (next < 0 || resident < 0 || limit < 0) throw new IllegalArgumentException("Invalid residency accounting");
        if (next > limit || resident + next <= limit) return List.of();
        var priority = priority(center);
        var candidates = meshes.keySet().stream().filter(key -> meshes.get(key) > 0 && priority.compare(key, incoming) > 0)
                .sorted(priority.reversed()).toList();
        long freed = 0;
        var result = new ArrayList<SectionKey>();
        for (var key : candidates) {
            freed += meshes.get(key); result.add(key);
            if (resident - freed + next <= limit) return List.copyOf(result);
        }
        return List.of(); // No partial eviction when it cannot make the incoming section fit.
    }
}
