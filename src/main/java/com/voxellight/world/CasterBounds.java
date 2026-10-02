package com.voxellight.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Actual uploaded model bounds, including models that extend outside their section. */
public record CasterBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public CasterBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid caster bounds");
    }

    /** Adapter pins BLOCK's float3 position at byte zero; ignores other attributes. */
    public static CasterBounds fromVertices(SectionKey section, ByteBuffer data, int stride) {
        if (stride < 12 || data.remaining() == 0 || data.remaining() % stride != 0) throw new IllegalArgumentException("Invalid BLOCK vertex payload");
        var vertices = data.duplicate().order(ByteOrder.nativeOrder());
        double x = section.x() * 16.0, y = section.y() * 16.0, z = section.z() * 16.0;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int offset = vertices.position(); offset < vertices.limit(); offset += stride) {
            double px = x + vertices.getFloat(offset), py = y + vertices.getFloat(offset + 4), pz = z + vertices.getFloat(offset + 8);
            minX = Math.min(minX, px); minY = Math.min(minY, py); minZ = Math.min(minZ, pz);
            maxX = Math.max(maxX, px); maxY = Math.max(maxY, py); maxZ = Math.max(maxZ, pz);
        }
        return new CasterBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public CasterBounds union(CasterBounds other) {
        return new CasterBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }
}
