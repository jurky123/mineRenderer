package com.voxellight.adapter;

import java.nio.*;

/** Reload-owned CPU classification of the exact uploaded ID/palette grid; uncertain material stays slow. */
public final class RtMaterialCoverage {
    private record Grid(int width,int height,byte[] transmission,short[] opacity){}
    private static volatile Grid grid;
    private static java.lang.ref.WeakReference<com.mojang.blaze3d.textures.GpuTexture> watched=new java.lang.ref.WeakReference<>(null);
    private static boolean opacityValid;private static long opacityEpoch;
    public static long opacityEpoch(){return opacityEpoch;}
    public static boolean opacityValid(){return opacityValid;}
    static void watch(com.mojang.blaze3d.textures.GpuTexture texture){watched=new java.lang.ref.WeakReference<>(texture);}
    public static void written(com.mojang.blaze3d.textures.GpuTexture texture,int mip,int x,int y,int w,int h){
        var current=grid;if(!opacityValid||current==null||watched.get()!=texture||mip!=0)return;
        int tw=texture.getWidth(0),th=texture.getHeight(0);
        int x0=Math.clamp(x*current.width/tw,0,current.width-1),y0=Math.clamp(y*current.height/th,0,current.height-1);
        int x1=Math.clamp((int)Math.ceil((x+w)*(double)current.width/tw)-1,0,current.width-1),y1=Math.clamp((int)Math.ceil((y+h)*(double)current.height/th)-1,0,current.height-1);
        for(int row=y0;row<=y1;row++)for(int column=x0;column<=x1;column++)if(current.opacity[row*current.width+column]>=0&&current.opacity[row*current.width+column]!=256){opacityValid=false;opacityEpoch++;return;}
    }
    private RtMaterialCoverage(){}
    static void publish(int w,int h,byte[] transmission,short[] opacity){grid=new Grid(w,h,transmission,opacity);opacityValid=true;opacityEpoch++;}
    static void clear(){grid=null;opacityValid=false;opacityEpoch++;watched.clear();}
    public static boolean transmissive(byte[] triangles,int offset){
        var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());int flags=data.getInt(offset+36);
        if((flags&128)!=0)return false; // Current dynamic material contract is diffuse.
        if((flags&2)!=0)return true;
        var current=grid;if(current==null)return true;
        int[] bounds=bounds(data,offset,current);if(bounds==null)return true;
        for(int y=bounds[1];y<=bounds[3];y++)for(int x=bounds[0];x<=bounds[2];x++)if(current.transmission[y*current.width+x]!=0)return true;
        return false;
    }
    public static int opacity(byte[] triangles,int offset){
        var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());int flags=data.getInt(offset+36);
        var current=grid;if(current==null||!opacityValid||(flags&128)!=0)return -3; // FULLY_UNKNOWN_TRANSPARENT: exact any-hit fallback.
        int[] bounds=bounds(data,offset,current);if(bounds==null)return -3;
        int state=-1,tint=data.getInt(offset+32)>>>24;
        for(int y=bounds[1];y<=bounds[3];y++)for(int x=bounds[0];x<=bounds[2];x++){
            int alpha=current.opacity[y*current.width+x];if(alpha<0)return -3; // animated/unknown source
            boolean visible=alpha==256||((flags&16)!=0?alpha*tint*10>=65025:alpha*tint*2>=65025);
            int next=visible?-2:-1;if(state==-1&&x==bounds[0]&&y==bounds[1])state=next;else if(state!=next)return -3;
        }
        return state;
    }
    private static int[] bounds(ByteBuffer data,int offset,Grid current){
        float minU=1,minV=1,maxU=0,maxV=0;
        for(int v=0;v<3;v++){float u=data.getFloat(offset+v*40+12),t=data.getFloat(offset+v*40+16);if(!Float.isFinite(u)||!Float.isFinite(t))return null;minU=Math.min(minU,u);maxU=Math.max(maxU,u);minV=Math.min(minV,t);maxV=Math.max(maxV,t);}
        // One texel guard protects UV interpolation/atlas rounding at boundaries.
        return new int[]{Math.clamp((int)Math.floor(minU*current.width)-1,0,current.width-1),Math.clamp((int)Math.floor(minV*current.height)-1,0,current.height-1),Math.clamp((int)Math.floor(maxU*current.width)+1,0,current.width-1),Math.clamp((int)Math.floor(maxV*current.height)+1,0,current.height-1)};
    }
}
