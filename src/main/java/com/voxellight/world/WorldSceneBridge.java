package com.voxellight.world;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded section rebuild markers. Repeated edits merge without queuing more payloads. */
public final class WorldSceneBridge {
    public static final int LOAD = 1;
    public static final int GEOMETRY = 2;
    public static final int LIGHT = 4;
    public static final int RESOURCE = 8;

    public record Request(SectionKey key, long worldGeneration, long resourceGeneration, long version, int reasons) { }
    public record GeometryToken(SectionKey key, long worldGeneration, long resourceGeneration, long version) { }
    public record SurfaceToken(SectionKey key, long worldGeneration, long resourceGeneration, long version) { }
    public record Stats(long worldGeneration, long resourceGeneration, int tracked, int dirty, int inFlight,
                        int resident, long payloadBytes, long accepted, long stale, long coalesced, long unloaded) { }

    private static final class Entry {
        long version;
        long geometryVersion;
        long dirtyOrder;
        int reasons;
        Request inFlight;
        SectionSnapshot snapshot;
    }

    private final int capacity;
    private final LinkedHashMap<SectionKey, Entry> entries = new LinkedHashMap<>();
    private long worldGeneration = 1;
    private long resourceGeneration = 1;
    private long changeRevision;
    private long sequence;
    private long accepted;
    private long stale;
    private long coalesced;
    private long unloaded;

    public WorldSceneBridge(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void reconcile(Collection<SectionKey> desired) {
        var keys = new HashSet<>(desired);
        if (keys.size() > capacity) {
            throw new IllegalArgumentException("section window exceeds capacity");
        }
        entries.entrySet().removeIf(entry -> {
            if (!keys.contains(entry.getKey())) {
                unloaded++;
                changeRevision++;
                return true;
            }
            return false;
        });
        for (SectionKey key : desired) {
            if (!entries.containsKey(key)) {
                var entry = new Entry();
                entries.put(key, entry);
                invalidate(entry, LOAD);
            }
        }
    }

    public synchronized void markDirty(SectionKey key, int reasons) {
        Entry entry = entries.get(key);
        if (entry != null && reasons != 0) {
            invalidate(entry, reasons);
        }
    }

    public synchronized void unload(SectionKey key) {
        if (entries.remove(key) != null) {
            unloaded++;
            changeRevision++;
        }
    }

    public synchronized void markRangeDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int reasons) {
        if (reasons == 0) return;
        for (var item : entries.entrySet()) {
            var key = item.getKey();
            if (key.x() >= minX && key.x() <= maxX && key.y() >= minY && key.y() <= maxY
                    && key.z() >= minZ && key.z() <= maxZ) {
                invalidate(item.getValue(), reasons);
            }
        }
    }

    public synchronized void unloadChunk(int x, int z) {
        entries.entrySet().removeIf(entry -> {
            if (entry.getKey().x() == x && entry.getKey().z() == z) {
                unloaded++;
                changeRevision++;
                return true;
            }
            return false;
        });
    }

    public synchronized Request acquire() {
        SectionKey next = null;
        long oldest = Long.MAX_VALUE;
        for (var item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.reasons != 0 && entry.inFlight == null && entry.dirtyOrder < oldest) {
                next = item.getKey();
                oldest = entry.dirtyOrder;
            }
        }
        if (next == null) {
            return null;
        }
        Entry entry = entries.get(next);
        var request = new Request(next, worldGeneration, resourceGeneration, entry.version, entry.reasons);
        entry.reasons = 0;
        entry.inFlight = request;
        return request;
    }

    public synchronized boolean complete(Request request, SectionSnapshot snapshot) {
        Entry entry = entries.get(request.key());
        if (entry == null || !request.equals(entry.inFlight)) {
            stale++;
            return false;
        }
        entry.inFlight = null;
        if (snapshot == null || !request.equals(snapshot.request())
                || request.worldGeneration() != worldGeneration || request.resourceGeneration() != resourceGeneration
                || request.version() != entry.version) {
            stale++;
            if (entry.reasons == 0) {
                entry.dirtyOrder = ++sequence;
            }
            entry.reasons |= request.reasons();
            return false;
        }
        entry.snapshot = snapshot;
        accepted++;
        return true;
    }

    public synchronized void changeWorld() {
        worldGeneration++;
        changeRevision++;
        entries.clear();
    }

    public synchronized void reloadResources() {
        resourceGeneration++;
        changeRevision++;
        for (Entry entry : entries.values()) {
            invalidate(entry, RESOURCE);
        }
    }

    public synchronized SectionSnapshot snapshot(SectionKey key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.snapshot;
    }

    /** Light-only updates do not invalidate caster geometry. -1 means outside this world/window. */
    public synchronized long geometryVersion(SectionKey key) {
        Entry entry = entries.get(key);
        return entry == null ? -1 : entry.geometryVersion;
    }

    /** Client-thread model extraction need not wait for the independent occupancy encoder. */
    public synchronized GeometryToken geometryToken(SectionKey key) {
        Entry entry = entries.get(key);
        return entry == null ? null : new GeometryToken(key, worldGeneration, resourceGeneration, entry.geometryVersion);
    }

    public synchronized boolean isCurrent(GeometryToken token) {
        Entry entry = entries.get(token.key());
        return entry != null && worldGeneration == token.worldGeneration() && resourceGeneration == token.resourceGeneration()
                && entry.geometryVersion == token.version();
    }

    /** Material surfaces include packed light levels, so light-only updates invalidate them. */
    public synchronized SurfaceToken surfaceToken(SectionKey key) {
        Entry entry = entries.get(key);
        return entry == null ? null : new SurfaceToken(key, worldGeneration, resourceGeneration, entry.version);
    }

    public synchronized boolean isCurrent(SurfaceToken token) {
        Entry entry = entries.get(token.key());
        return entry != null && worldGeneration == token.worldGeneration() && resourceGeneration == token.resourceGeneration()
                && entry.version == token.version();
    }

    /** Atomically capture a geometry token only for a snapshot that is still current. */
    public synchronized long geometryVersion(SectionSnapshot snapshot) {
        Entry entry = entries.get(snapshot.request().key());
        return entry == null || entry.snapshot != snapshot ? -1 : entry.geometryVersion;
    }

    public synchronized List<SectionKey> keys() {
        return List.copyOf(entries.keySet());
    }

    public synchronized List<SectionSnapshot> snapshots() {
        return entries.values().stream().map(entry -> entry.snapshot).filter(snapshot -> snapshot != null).toList();
    }

    public synchronized Stats stats() {
        int dirty = 0;
        int inFlight = 0;
        int resident = 0;
        long bytes = 0;
        for (Entry entry : entries.values()) {
            if (entry.reasons != 0) dirty++;
            if (entry.inFlight != null) inFlight++;
            if (entry.snapshot != null) {
                resident++;
                bytes += entry.snapshot.payloadBytes();
            }
        }
        return new Stats(worldGeneration, resourceGeneration, entries.size(), dirty, inFlight, resident,
                bytes, accepted, stale, coalesced, unloaded);
    }

    public synchronized long changeRevision() { return changeRevision; }

    private void invalidate(Entry entry, int reasons) {
        changeRevision++;
        if (entry.reasons != 0) {
            coalesced++;
        } else {
            entry.dirtyOrder = ++sequence;
        }
        entry.reasons |= reasons;
        entry.version = ++sequence;
        if ((reasons & (LOAD | GEOMETRY | RESOURCE)) != 0) entry.geometryVersion = entry.version;
        // A dirty section is unavailable to consumers until a current snapshot is ready.
        entry.snapshot = null;
    }
}
