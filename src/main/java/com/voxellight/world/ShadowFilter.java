package com.voxellight.world;

/** Continuous texel-phase box PCF budgets, independent of volume/reflection quality. */
public enum ShadowFilter {
    FAST(0), BALANCED(1), HIGH(2);
    private final int radius;
    ShadowFilter(int radius){this.radius=radius;}
    public int radius(){return radius;}
    public int taps(){return (2*radius+2)*(2*radius+2);}
}
