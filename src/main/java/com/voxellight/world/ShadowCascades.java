package com.voxellight.world;

import org.joml.Matrix4f;

/** Overlapping world-distance ranges, independent of view yaw/pitch or camera frustum selection. */
public final class ShadowCascades {
    public static final int COUNT = 3, RESOLVE_BYTES = COUNT * 64 + 64 + 3 * 16 + 64 + COUNT * 64;
    public static final float FADE_START = 112, RADIUS = 128;
    public record Range(int mapSize, float halfExtent, float blendStart, float blendEnd) { }
    private static final Range[] RANGES = {
            new Range(2048, 32, 12, 16),
            new Range(1024, 96, 40, 48),
            new Range(1024, 192, 112, 128)
    };
    private ShadowCascades() { }
    /** Affine orthographic light transform; the upper 3x3 is the inverse-transpose normal transform. */
    public static Matrix4f normalMatrix(Matrix4f lightMatrix) {
        return new Matrix4f(lightMatrix).invert().transpose();
    }
    public static Range range(int index) { return RANGES[index]; }
    public static long mapBytes() {
        long bytes = 0;
        for (var range : RANGES) bytes += 5L * range.mapSize() * range.mapSize();
        return bytes;
    }
    public static Matrix4f matrix(double x, double y, double z, ShadowLight light, int index) {
        var range = range(index);
        return ShadowVolume.lightMatrix(x, y, z, light, range.halfExtent(), range.mapSize());
    }
    public static Matrix4f anchored(double cx, double cy, double cz, ShadowMapCache.Anchor anchor, ShadowLight light, int index) {
        return matrix(anchor.x(), anchor.y(), anchor.z(), light, index)
                .translate((float)(cx - anchor.x()), (float)(cy - anchor.y()), (float)(cz - anchor.z()));
    }
}
