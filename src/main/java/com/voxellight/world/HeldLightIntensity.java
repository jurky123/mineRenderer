package com.voxellight.world;
/** Virtual point intensity in scene-linear units; 15-level emission gives 20 at one block. */
public final class HeldLightIntensity {
    private HeldLightIntensity(){}
    public static boolean flame(String path){return java.util.Set.of("torch","wall_torch","soul_torch","soul_wall_torch","redstone_torch","redstone_wall_torch","lantern","soul_lantern").contains(path);}
    /** Full emissive faces use radiance 8; virtual flame sources use intensity 20. */
    public static float intensity(int emission,boolean flame){return Math.clamp(emission,0,15)/15f*(flame?20f:8f);}
    public static float intensity(int emission){return Math.clamp(emission,0,15)/15f*20f;}
}
