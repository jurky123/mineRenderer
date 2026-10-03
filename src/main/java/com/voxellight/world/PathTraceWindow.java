package com.voxellight.world;

/** Keep the proxy origin stable across section boundaries; recenter only outside its safe inner cube. */
public final class PathTraceWindow {
    private SectionKey center;
    public SectionKey admit(double x,double y,double z){
        if(center==null || Math.abs(x-(center.x()*16.+8))>16 || Math.abs(y-(center.y()*16.+8))>16 || Math.abs(z-(center.z()*16.+8))>16)
            center=SectionKey.fromBlock((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        return center;
    }
    public void reset(){center=null;}
}
