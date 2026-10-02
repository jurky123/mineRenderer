package com.voxellight.world;

/** Immutable palette plus conservative occupancy, produced from an owned section copy. */
public final class SectionSnapshot {
    public static final int BLOCKS = 4096;
    public static final int FULL_OCCLUDER = 1;
    public static final int NON_AIR = 2;
    public static final int FLUID = 4;
    public static final int NON_MODEL = 8;

    public record Material(int stateId, int flags, int emission) { }

    private final WorldSceneBridge.Request request;
    private final short[] indices;
    private final int[] stateIds;
    private final byte[] flags;
    private final byte[] emissions;
    private final long[] fullOccupancy = new long[64];
    private final int nonAirCount;
    private final int fullCount;
    private final int emissiveCount;

    public SectionSnapshot(WorldSceneBridge.Request request, short[] indices, int[] stateIds, byte[] flags, byte[] emissions) {
        if (indices.length != BLOCKS || stateIds.length == 0 || stateIds.length > BLOCKS
                || flags.length != stateIds.length || emissions.length != stateIds.length) {
            throw new IllegalArgumentException("Invalid section palette");
        }
        this.request = java.util.Objects.requireNonNull(request);
        this.indices = indices.clone();
        this.stateIds = stateIds.clone();
        this.flags = flags.clone();
        this.emissions = emissions.clone();
        int nonAir = 0;
        int full = 0;
        int emissive = 0;
        for (int i = 0; i < BLOCKS; i++) {
            int palette = Short.toUnsignedInt(this.indices[i]);
            if (palette >= stateIds.length || Byte.toUnsignedInt(this.emissions[palette]) > 15) {
                throw new IllegalArgumentException("Invalid section material");
            }
            if ((this.flags[palette] & NON_AIR) != 0) nonAir++;
            if ((this.flags[palette] & FULL_OCCLUDER) != 0) {
                fullOccupancy[i >>> 6] |= 1L << (i & 63);
                full++;
            }
            if (this.emissions[palette] != 0) emissive++;
        }
        nonAirCount = nonAir;
        fullCount = full;
        emissiveCount = emissive;
    }

    public WorldSceneBridge.Request request() { return request; }
    public int paletteSize() { return stateIds.length; }
    public int nonAirCount() { return nonAirCount; }
    public int fullCount() { return fullCount; }
    public int emissiveCount() { return emissiveCount; }

    public Material material(int index) {
        int palette = Short.toUnsignedInt(indices[java.util.Objects.checkIndex(index, BLOCKS)]);
        return new Material(stateIds[palette], Byte.toUnsignedInt(flags[palette]), Byte.toUnsignedInt(emissions[palette]));
    }

    public boolean fullOccluder(int index) {
        java.util.Objects.checkIndex(index, BLOCKS);
        return (fullOccupancy[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public long payloadBytes() {
        return (long) indices.length * Short.BYTES + (long) stateIds.length * Integer.BYTES
                + flags.length + emissions.length + (long) fullOccupancy.length * Long.BYTES;
    }
}
