package com.voxellight.world;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded, world-space diffuse irradiance for previously traced static block faces. */
public final class PathTraceSurfaceCache {
    public static final int WIDTH=128, HEIGHT=128, SLOTS=WIDTH*HEIGHT;
    private final float[] tags=new float[SLOTS*4], light=new float[SLOTS*4];
    private boolean populated;
    public static int slot(int x,int y,int z,int face) {
        return (x*73856093 ^ y*19349663 ^ z*83492791 ^ face*0x9e3779b1)&(SLOTS-1);
    }
    public void clear(){Arrays.fill(tags,0);Arrays.fill(light,0);populated=false;}
    public boolean populated(){return populated;}
    public void add(double x,double y,double z,float nx,float ny,float nz,float r,float g,float b,float ar,float ag,float ab) {
        int axis=Math.abs(nx)>.999?0:Math.abs(ny)>.999?1:Math.abs(nz)>.999?2:-1;
        if(axis<0||!Float.isFinite(r+g+b)||r<0||g<0||b<0)return;
        float component=axis==0?nx:axis==1?ny:nz;
        int face=axis*2+(component<0?1:0);
        int cx=(int)Math.floor((x+nx*.02)/2),cy=(int)Math.floor((y+ny*.02)/2),cz=(int)Math.floor((z+nz*.02)/2);
        double plane=axis==0?x-cx*2.:axis==1?y-cy*2.:z-cz*2.;
        int j=slot(cx,cy,cz,face)*4;
        float tag=(float)(face+1+plane/32.);
        boolean same=tags[j]==cx&&tags[j+1]==cy&&tags[j+2]==cz&&Math.abs(tags[j+3]-tag)<.001;
        float weight=same?.25f:1;
        tags[j]=cx;tags[j+1]=cy;tags[j+2]=cz;tags[j+3]=tag;
        // Store irradiance, so neighboring surfaces retain their own authored color.
        light[j]+=weight*(Math.min(8,r/Math.max(.05f,ar))-light[j]);
        light[j+1]+=weight*(Math.min(8,g/Math.max(.05f,ag))-light[j+1]);
        light[j+2]+=weight*(Math.min(8,b/Math.max(.05f,ab))-light[j+2]);light[j+3]=1;populated=true;
    }
    public void write(ByteBuffer tagBuffer,ByteBuffer lightBuffer) {
        for(float v:tags)tagBuffer.putFloat(v);
        for(float v:light)lightBuffer.putFloat(v);
        tagBuffer.flip();lightBuffer.flip();
    }
}
