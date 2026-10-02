package com.voxellight.world;

import java.nio.ByteBuffer;
import java.util.*;

/** Stable, bounded shape IDs. The grid stores (row << 5) | boxCount; special IDs need no boxes. */
public final class OccluderShapes {
    public static final int EMPTY = 0, FULL = -1, UNKNOWN = -2;
    public static final int MAX_SHAPES = 1024, MAX_BOXES = 16, WIDTH = MAX_BOXES * 2;
    public static final int TEXTURE_BYTES = WIDTH * MAX_SHAPES * 4 * Float.BYTES;
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Box {
            if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)
                    || minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid occluder box");
        }
        public Box clipped() { return new Box(clamp(minX), clamp(minY), clamp(minZ), clamp(maxX), clamp(maxY), clamp(maxZ)); }
        private static float clamp(float value) { return Math.clamp(value, 0, 1); }
        public boolean empty() { return minX == maxX || minY == maxY || minZ == maxZ; }
        public boolean full() { return minX == 0 && minY == 0 && minZ == 0 && maxX == 1 && maxY == 1 && maxZ == 1; }
        public Box union(Box other) {
            return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }
    }
    private final Map<List<Box>, Integer> ids = new HashMap<>();
    private final List<List<Box>> rows = new ArrayList<>();
    private long complexFallbacks, paletteOverflows;

    public OccluderShapes() { clear(); }
    public int register(Collection<Box> input) {
        var boxes = input.stream().map(Box::clipped).filter(box -> !box.empty()).distinct()
                .sorted(Comparator.comparingDouble(Box::minX).thenComparingDouble(Box::minY).thenComparingDouble(Box::minZ)
                        .thenComparingDouble(Box::maxX).thenComparingDouble(Box::maxY).thenComparingDouble(Box::maxZ)).toList();
        if (boxes.isEmpty()) return EMPTY;
        if (boxes.stream().anyMatch(Box::full)) return FULL;
        if (boxes.size() > MAX_BOXES) {
            complexFallbacks++;
            var bound = boxes.getFirst();
            for (int i = 1; i < boxes.size(); i++) bound = bound.union(boxes.get(i));
            if (bound.full()) return FULL;
            boxes = List.of(bound);
        }
        var existing = ids.get(boxes);
        if (existing != null) return existing;
        if (rows.size() >= MAX_SHAPES) { paletteOverflows++; return FULL; }
        int word = (rows.size() << 5) | boxes.size();
        ids.put(boxes, word); rows.add(boxes);
        return word;
    }

    /** Only newly appended rows are uploaded. Existing IDs never change until all owning grids reset. */
    public void writeRows(ByteBuffer target, int first, int end) {
        if (first < 0 || end > rows.size() || first > end) throw new IllegalArgumentException("Invalid shape upload range");
        if (target.remaining() < (end - first) * WIDTH * 16) throw new IllegalArgumentException("Shape upload is too small");
        for (int row = first; row < end; row++) {
            var boxes = rows.get(row);
            for (int i = 0; i < MAX_BOXES; i++) {
                if (i < boxes.size()) {
                    var box = boxes.get(i);
                    target.putFloat(box.minX()).putFloat(box.minY()).putFloat(box.minZ()).putFloat(0);
                    target.putFloat(box.maxX()).putFloat(box.maxY()).putFloat(box.maxZ()).putFloat(0);
                } else for (int component = 0; component < 8; component++) target.putFloat(0);
            }
        }
    }
    public int rowCount() { return rows.size(); }
    public long complexFallbacks() { return complexFallbacks; }
    public long paletteOverflows() { return paletteOverflows; }
    public void clear() { ids.clear(); rows.clear(); rows.add(List.of()); complexFallbacks = 0; paletteOverflows = 0; }
}
