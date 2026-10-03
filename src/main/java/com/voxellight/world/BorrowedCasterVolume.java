package com.voxellight.world;

import org.joml.Vector3f;

/** Conservative, allocation-free section test for native offscreen caster lookup.
 * The local scene bridge keeps its independent 48-block budget. */
public final class BorrowedCasterVolume {
    public static final float EXTRUSION = 96;
    public static final float SECTION_RADIUS = (float)(Math.sqrt(3) * 9);
    private BorrowedCasterVolume() { }

    public static boolean intersects(double x, double y, double z, Vector3f direction, float radius) {
        double along = Math.clamp(x * direction.x + y * direction.y + z * direction.z, 0, EXTRUSION);
        double dx = x - direction.x * along, dy = y - direction.y * along, dz = z - direction.z * along;
        double reach = radius + SECTION_RADIUS;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }
}
