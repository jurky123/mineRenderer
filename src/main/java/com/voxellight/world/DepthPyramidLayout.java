package com.voxellight.world;

/** Half-resolution R32 reversed-Z maximum-depth mip chain; edge cells merge odd children. */
public record DepthPyramidLayout(int width,int height,int levels,long bytes) {
    public static final long LIMIT=16L*1024*1024;
    public static DepthPyramidLayout of(int fullWidth,int fullHeight) {
        if(fullWidth<=0 || fullHeight<=0)throw new IllegalArgumentException("Invalid depth pyramid size");
        int w=(int)((fullWidth+1L)/2),h=(int)((fullHeight+1L)/2),levels=0;long bytes=0;
        for(int x=w,y=h;;x=Math.max(1,x/2),y=Math.max(1,y/2)) {
            bytes=Math.addExact(bytes,Math.multiplyExact(4L*x,y));levels++;
            if(x==1 && y==1)break;
        }
        return new DepthPyramidLayout(w,h,levels,bytes);
    }
    public int width(int level){check(level);return Math.max(1,width>>level);}
    public int height(int level){check(level);return Math.max(1,height>>level);}
    private void check(int level){if(level<0 || level>=levels)throw new IllegalArgumentException("Invalid depth mip");}
}
