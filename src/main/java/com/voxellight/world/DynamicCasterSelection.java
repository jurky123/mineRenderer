package com.voxellight.world;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded nearest-first admission independent of camera orientation and entity iteration order. */
public final class DynamicCasterSelection<T> {
    public static final int MAX_ENTITIES = 32, MAX_MODELS = 128;
    public static final int FRAME_BYTES = 1024 * 1024, MODEL_BYTES = 256 * 1024;
    public static final double RADIUS = 64;
    public record Candidate<T>(T value, long id, double distanceSquared) { }
    private final Comparator<Candidate<T>> order = Comparator.<Candidate<T>>comparingDouble(Candidate::distanceSquared)
            .thenComparingLong(Candidate::id);
    private final PriorityQueue<Candidate<T>> nearest = new PriorityQueue<>(MAX_ENTITIES, order.reversed());
    private int candidates;
    public void consider(T value, long id, double distanceSquared) {
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0 || distanceSquared > RADIUS * RADIUS) return;
        candidates++;
        var candidate = new Candidate<>(value, id, distanceSquared);
        if (nearest.size() < MAX_ENTITIES) nearest.add(candidate);
        else if (order.compare(candidate, nearest.peek()) < 0) { nearest.remove(); nearest.add(candidate); }
    }
    public List<T> selected() { return nearest.stream().sorted(order).map(Candidate::value).toList(); }
    public int candidates() { return candidates; }
    public int overflow() { return candidates - nearest.size(); }
}
