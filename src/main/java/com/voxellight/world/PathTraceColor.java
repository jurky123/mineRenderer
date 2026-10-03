package com.voxellight.world;
public final class PathTraceColor {
    public static double linear(double encoded){return encoded<=.04045?encoded/12.92:Math.pow((encoded+.055)/1.055,2.4);}
    private PathTraceColor(){}
}
