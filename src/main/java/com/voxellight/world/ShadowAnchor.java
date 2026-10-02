package com.voxellight.world;

/** Hysteresis prevents walking across a grid boundary from alternating between two maps. */
public final class ShadowAnchor {
    private ShadowMapCache.Anchor anchor;

    public ShadowMapCache.Anchor update(double x, double y, double z) {
        if (anchor == null) anchor = new ShadowMapCache.Anchor(nearest(x), nearest(y), nearest(z));
        else anchor = new ShadowMapCache.Anchor(keep(x, anchor.x()), keep(y, anchor.y()), keep(z, anchor.z()));
        return anchor;
    }

    private static int nearest(double p) { return (int)(Math.floor(p / ShadowVolume.ANCHOR_GRID + 0.5) * ShadowVolume.ANCHOR_GRID); }
    private static int keep(double p, int current) { return Math.abs(p - current) <= ShadowVolume.ANCHOR_GRID ? current : nearest(p); }
    public void clear() { anchor = null; }
}
