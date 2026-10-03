package com.voxellight.world;

import java.util.*;
import java.nio.ByteBuffer;

/** Bounded shape-ID atlas. Unknown space blocks rays; changed sections update only their atlas tiles. */
public final class LocalLightVolume {
    public static final int SIZE = 80, WIDTH = 800, HEIGHT = 640, MAX_LIGHTS = 16;
    public static final int ATLAS_BYTES = WIDTH * HEIGHT * Integer.BYTES;
    private static final int TILE = 16, COLUMNS = WIDTH / TILE, ROWS = HEIGHT / TILE;
    public record Upload(int x, int y, int width, int height) {
        public int bytes() { return width * height * Integer.BYTES; }
    }
    public static final int SETTINGS_BYTES = 48 + MAX_LIGHTS * 32;
    public record Emitter(int x, int y, int z, int emission, float red, float green, float blue) {
        public Emitter {
            if (emission < 1 || emission > 15) throw new IllegalArgumentException("Invalid emission");
        }
        public float radius() { return Math.min(12, emission); }
        public double distanceSquared(double cx, double cy, double cz) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
    public record Section(SectionKey key, int[] opacity, List<Emitter> emitters) {
        public Section {
            if (opacity.length != SectionSnapshot.BLOCKS || emitters.size() > 64) throw new IllegalArgumentException("Invalid local light section");
            opacity = opacity.clone();
            emitters = List.copyOf(emitters);
        }
    }
    public record Active(Emitter emitter, float weight) { }
    private record Slot(Emitter emitter, float weight) { }
    private final int[] atlas = new int[WIDTH * HEIGHT];
    private Map<SectionKey, Section> resident = Map.of();
    private boolean initialized;
    private final List<Slot> slots = new ArrayList<>();
    private List<Emitter> emitters = List.of();
    private int originX, originY, originZ;
    private int candidates;

    public List<Upload> rebuild(Collection<Section> sections, SectionKey center) {
        int nx = (center.x() - 2) * 16, ny = (center.y() - 2) * 16, nz = (center.z() - 2) * 16;
        boolean moved = !initialized || nx != originX || ny != originY || nz != originZ;
        originX = nx; originY = ny; originZ = nz; initialized = true;
        var next = new LinkedHashMap<SectionKey, Section>();
        for (var section : sections) {
            var key = section.key();
            if (Math.abs(key.x() - center.x()) <= 2 && Math.abs(key.y() - center.y()) <= 2 && Math.abs(key.z() - center.z()) <= 2) next.put(key, section);
        }
        var changed = new BitSet(COLUMNS * ROWS);
        if (moved) {
            Arrays.fill(atlas, OccluderShapes.UNKNOWN);
            for (var section : next.values()) writeSection(section.key(), section, changed);
        } else {
            var keys = new LinkedHashSet<>(resident.keySet()); keys.addAll(next.keySet());
            for (var key : keys) {
                var old = resident.get(key); var current = next.get(key);
                if (old == current || old != null && current != null && Arrays.equals(old.opacity(), current.opacity())) continue;
                writeSection(key, current, changed);
            }
        }
        resident = next;
        emitters = next.values().stream().flatMap(section -> section.emitters().stream()).toList();
        var current = new HashSet<>(emitters);
        slots.removeIf(slot -> !current.contains(slot.emitter()));
        return moved ? List.of(new Upload(0, 0, WIDTH, HEIGHT)) : regions(changed);
    }

    private void writeSection(SectionKey key, Section section, BitSet changed) {
        int sx = key.x() * 16 - originX, sy = key.y() * 16 - originY, sz = key.z() * 16 - originZ;
        for (int y = 0; y < 16; y++) {
            int px = sx + ((sy + y) % 10) * SIZE, py = sz + ((sy + y) / 10) * SIZE;
            changed.set((py / TILE) * COLUMNS + px / TILE);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                atlas[(py + z) * WIDTH + px + x] = section == null ? OccluderShapes.UNKNOWN : section.opacity()[SectionKey.blockIndex(x, y, z)];
            }
        }
    }

    private static List<Upload> regions(BitSet dirty) {
        var result = new ArrayList<Upload>();
        for (int index = dirty.nextSetBit(0); index >= 0; index = dirty.nextSetBit(0)) {
            int x = index % COLUMNS, y = index / COLUMNS, width = 1, height = 1;
            while (x + width < COLUMNS && dirty.get(index + width)) width++;
            dirty.clear(index, index + width);
            while (y + height < ROWS) {
                int row = (y + height) * COLUMNS + x;
                if (dirty.nextClearBit(row) < row + width) break;
                dirty.clear(row, row + width); height++;
            }
            result.add(new Upload(x * TILE, y * TILE, width * TILE, height * TILE));
            if (result.size() > 64) return List.of(new Upload(0, 0, WIDTH, HEIGHT));
        }
        return List.copyOf(result);
    }

    public void writeRegion(ByteBuffer target, Upload region) {
        if (region.x() < 0 || region.y() < 0 || region.width() <= 0 || region.height() <= 0
                || region.x() + region.width() > WIDTH || region.y() + region.height() > HEIGHT || target.remaining() < region.bytes()) {
            throw new IllegalArgumentException("Invalid shape-grid upload");
        }
        for (int y = region.y(); y < region.y() + region.height(); y++) {
            int start = y * WIDTH + region.x();
            for (int x = 0; x < region.width(); x++) target.putInt(atlas[start + x]);
        }
    }

    public List<Active> select(double x, double y, double z, float seconds) {
        return select(x,y,z,seconds,MAX_LIGHTS);
    }
    public List<Active> select(double x,double y,double z,float seconds,int budget) {
        if(budget<0 || budget>MAX_LIGHTS)throw new IllegalArgumentException("Invalid light budget");
        while(slots.size()>budget)slots.removeLast();
        var retained = new HashSet<Emitter>();
        slots.forEach(slot -> retained.add(slot.emitter()));
        var ranked = emitters.stream().filter(e -> e.distanceSquared(x, y, z) < 36 * 36)
                .sorted(Comparator.<Emitter>comparingDouble(e -> -e.emission() * (retained.contains(e) ? 1.25 : 1) / (1 + e.distanceSquared(x, y, z)))
                        .thenComparingInt(Emitter::x).thenComparingInt(Emitter::y).thenComparingInt(Emitter::z)).toList();
        candidates = ranked.size();
        var desired = new HashSet<>(ranked.subList(0, Math.min(budget, ranked.size())));
        float change = Math.clamp(seconds, 0, 0.1f) / 0.25f;
        for (var it = slots.listIterator(); it.hasNext();) {
            var slot = it.next();
            float weight = Math.clamp(slot.weight() + (desired.contains(slot.emitter()) ? change : -change), 0, 1);
            if (weight <= 0 && !desired.contains(slot.emitter())) it.remove();
            else it.set(new Slot(slot.emitter(), weight));
        }
        retained.clear(); slots.forEach(slot -> retained.add(slot.emitter()));
        for (var emitter : ranked) {
            if (slots.size() >= budget) break;
            if (desired.contains(emitter) && !retained.contains(emitter)) slots.add(new Slot(emitter, Math.min(1, change)));
        }
        return slots.stream().map(slot -> new Active(slot.emitter(), slot.weight())).toList();
    }

    public static int atlasIndex(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) throw new IndexOutOfBoundsException();
        return x + (y % 10) * SIZE + (z + (y / 10) * SIZE) * WIDTH;
    }
    public boolean blocked(int x, int y, int z) {
        x -= originX; y -= originY; z -= originZ;
        return x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE || atlas[atlasIndex(x, y, z)] != 0;
    }
    public int[] atlas() { return atlas; }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    public int candidates() { return candidates; }
    public void clear() { resident = Map.of(); initialized = false; emitters = List.of(); slots.clear(); candidates = 0; Arrays.fill(atlas, OccluderShapes.UNKNOWN); }
}
