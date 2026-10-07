package com.voxellight.rt;

/** Matches the frame-global sample position used by the NGX primary-ray variant. */
public final class RtJitter {
    private RtJitter(){}
    public static float halton(int frame,int base){int n=Math.floorMod(frame,1024)+1;float value=0,fraction=1;while(n>0){fraction/=base;value+=fraction*(n%base);n/=base;}return value-.5f;}
    public static float x(int frame){return halton(frame,2);}
    public static float y(int frame){return halton(frame,3);}
}
