package com.voxellight.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Fixed local reference light. All transforms use camera-relative positions, including at large coordinates. */
public final class ShadowVolume {
    public static final int MAP_SIZE = 2048;
    public static final int ANCHOR_GRID = 8;
    public static final float HALF_EXTENT = 48;
    public static final float NEAR = 1;
    public static final float FAR = 192;
    public static final float FADE_START = 16;
    public static final float RECEIVER_RADIUS = 24;
    public static final int SETTINGS_BYTES = 160;

    private ShadowVolume() { }

    public static Vector3f lightDirection() {
        return new Vector3f(0.6f, 1, 0.35f).normalize();
    }

    public static Matrix4f lightMatrix() {
        return projection(lightDirection(), new Vector3f(0, 1, 0));
    }

    private static Matrix4f projection(Vector3f direction, Vector3f up) {
        return projection(direction, up, HALF_EXTENT, 96, FAR);
    }

    private static Matrix4f projection(Vector3f direction, Vector3f up, float extent, float eyeDistance, float far) {
        var eye = new Vector3f(direction).mul(eyeDistance);
        var view = new Matrix4f().lookAt(eye, new Vector3f(), up);
        // Ordinary [0,1] shadow depth, independent of vanilla reversed-Z.
        return new Matrix4f().ortho(-extent, extent, -extent, extent, NEAR, far, true).mul(view);
    }

    public static Matrix4f lightMatrix(double cameraX, double cameraY, double cameraZ) {
        return snap(lightMatrix(), cameraX, cameraY, cameraZ);
    }

    public static Matrix4f lightMatrix(double x, double y, double z, ShadowLight light) {
        if (light.source() == ShadowLight.Source.FIXED || light.source() == ShadowLight.Source.NONE) return lightMatrix(x, y, z);
        // Celestial directions lie in XY; constant Z-up stays nonsingular and stable at noon.
        // Do not resnap a rotating map against the global origin: that adds subtexel jumps,
        // especially far from spawn. This anchor-relative projection moves continuously with the sky.
        return projection(light.direction(), new Vector3f(0, 0, 1));
    }

    public static Matrix4f lightMatrix(double x, double y, double z, ShadowLight light, float extent, int mapSize) {
        boolean fixed = light.source() == ShadowLight.Source.FIXED || light.source() == ShadowLight.Source.NONE;
        var matrix = projection(light.direction(), fixed ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1), extent, 128, 256);
        return fixed ? snap(matrix, x, y, z, extent, mapSize) : matrix;
    }

    private static Matrix4f snap(Matrix4f matrix, double x, double y, double z) {
        return snap(matrix, x, y, z, HALF_EXTENT, MAP_SIZE);
    }

    private static Matrix4f snap(Matrix4f matrix, double cameraX, double cameraY, double cameraZ, float extent, int mapSize) {
        double texel = 2.0 * extent / mapSize;
        // Snap the light-plane origin in double precision; world geometry stays on the same texel lattice.
        double x = (matrix.m00() * cameraX + matrix.m10() * cameraY + matrix.m20() * cameraZ) * extent;
        double y = (matrix.m01() * cameraX + matrix.m11() * cameraY + matrix.m21() * cameraZ) * extent;
        matrix.m30(matrix.m30() + (float)((x - Math.rint(x / texel) * texel) / extent));
        matrix.m31(matrix.m31() + (float)((y - Math.rint(y / texel) * texel) / extent));
        return matrix;
    }

    public static int anchor(double coordinate) {
        return (int)(Math.floor(coordinate / ANCHOR_GRID) * ANCHOR_GRID);
    }

    /** Fixed world volume expressed relative to this frame's camera; cached depth stays unchanged. */
    public static Matrix4f anchoredMatrix(double cameraX, double cameraY, double cameraZ, int x, int y, int z) {
        return lightMatrix(x, y, z).translate((float)(cameraX - x), (float)(cameraY - y), (float)(cameraZ - z));
    }

    public static Matrix4f anchoredMatrix(double cameraX, double cameraY, double cameraZ, int x, int y, int z, ShadowLight light) {
        return lightMatrix(x, y, z, light).translate((float)(cameraX - x), (float)(cameraY - y), (float)(cameraZ - z));
    }

}
