package com.voxellight.world;

public record SectionKey(int x, int y, int z) {
    public static SectionKey fromBlock(int x, int y, int z) {
        return new SectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
    }

    public static int blockIndex(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }
}
