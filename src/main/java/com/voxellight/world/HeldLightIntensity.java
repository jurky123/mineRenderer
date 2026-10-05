package com.voxellight.world;
/** Virtual point intensity in scene-linear units; 15-level emission gives 20 at one block. */
public final class HeldLightIntensity {
    private HeldLightIntensity(){}
    public static float intensity(int emission){return Math.clamp(emission,0,15)/15f*20f;}
}
