package com.voxellight.adapter;

import java.nio.*;

/** Reload-owned CPU classification of the exact uploaded ID/palette grid; uncertain material stays slow. */
public final class RtMaterialCoverage {
    private record Grid(int width,int height,byte[] transmission,short[] opacity){}
    private static volatile Grid grid;
    private static java.lang.ref.WeakReference<com.mojang.blaze3d.textures.GpuTexture> watched=new java.lang.ref.WeakReference<>(null);
    private static boolean opacityValid;private static long opacityEpoch,knownOpacityTexels;
    private static final ThreadLocal<com.mojang.blaze3d.textures.GpuTexture> animationTarget=new ThreadLocal<>();
    /** Vanilla atlas animation draws only preclassified unknown sprite regions; preserve static texels. */
    public static void animationPass(com.mojang.blaze3d.textures.GpuTexture texture,Runnable draw){
        var previous=animationTarget.get();animationTarget.set(texture);
        try{draw.run();}finally{if(previous==null)animationTarget.remove();else animationTarget.set(previous);}
    }
    public static void rendered(com.mojang.blaze3d.textures.GpuTextureView view){
        if(animationTarget.get()==view.texture())return;
        int mip=view.baseMipLevel();written(view.texture(),mip,0,0,view.getWidth(0),view.getHeight(0));
    }
    public static long knownOpacityTexels(){return knownOpacityTexels;}
    public static long opacityEpoch(){return opacityEpoch;}
    public static boolean opacityValid(){return opacityValid;}
    static void watch(com.mojang.blaze3d.textures.GpuTexture texture){watched=new java.lang.ref.WeakReference<>(texture);}
    public static void written(com.mojang.blaze3d.textures.GpuTexture texture,int mip,int x,int y,int w,int h){
        var current=grid;if(!opacityValid||current==null||watched.get()!=texture||mip!=0)return;
        int tw=texture.getWidth(0),th=texture.getHeight(0);
        int x0=Math.clamp(x*current.width/tw,0,current.width-1),y0=Math.clamp(y*current.height/th,0,current.height-1);
        int x1=Math.clamp((int)Math.ceil((x+w)*(double)current.width/tw)-1,0,current.width-1),y1=Math.clamp((int)Math.ceil((y+h)*(double)current.height/th)-1,0,current.height-1);
        short[] opacity=null;
        for(int row=y0;row<=y1;row++)for(int column=x0;column<=x1;column++){int index=row*current.width+column;if(current.opacity[index]>=0&&current.opacity[index]!=256){if(opacity==null)opacity=current.opacity.clone();opacity[index]=-1;knownOpacityTexels--;}}
        if(opacity!=null){grid=new Grid(current.width,current.height,current.transmission,opacity);opacityValid=knownOpacityTexels>0;opacityEpoch++;}
    }
    private RtMaterialCoverage(){}
    static void publish(int w,int h,byte[] transmission,short[] opacity){grid=new Grid(w,h,transmission,opacity);knownOpacityTexels=0;for(short alpha:opacity)if(alpha>=0&&alpha<256)knownOpacityTexels++;opacityValid=knownOpacityTexels>0;opacityEpoch++;}
    static void clear(){grid=null;opacityValid=false;knownOpacityTexels=0;opacityEpoch++;watched.clear();}
    public static java.util.function.BiPredicate<byte[],Integer> transmissionSnapshot(){var snapshot=grid;return (triangles,offset)->transmissive(triangles,offset,snapshot);}
    public static boolean transmissive(byte[] triangles,int offset){return transmissive(triangles,offset,grid);}
    private static boolean transmissive(byte[] triangles,int offset,Grid current){
        var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());int flags=data.getInt(offset+36);
        if((flags&128)!=0)return false; // Current dynamic material contract is diffuse.
        if((flags&2)!=0)return true;
        if(current==null)return true;
        int[] bounds=bounds(data,offset,current);if(bounds==null)return true;
        for(int y=bounds[1];y<=bounds[3];y++)for(int x=bounds[0];x<=bounds[2];x++)if(current.transmission[y*current.width+x]!=0)return true;
        return false;
    }
    public static int opacity(byte[] triangles,int offset){
        var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());int flags=data.getInt(offset+36);
        var current=grid;if(current==null||(flags&128)!=0)return -3; // FULLY_UNKNOWN_TRANSPARENT: exact any-hit fallback.
        int[] bounds=bounds(data,offset,current);if(bounds==null)return -3;
        int state=-1,tint=data.getInt(offset+32)>>>24;
        for(int y=bounds[1];y<=bounds[3];y++)for(int x=bounds[0];x<=bounds[2];x++){
            int alpha=current.opacity[y*current.width+x];if(alpha<0)return -3; // animated/unknown source
            boolean visible=alpha==256||((flags&16)!=0?alpha*tint*10>=65025:alpha*tint*2>=65025);
            int next=visible?-2:-1;if(state==-1&&x==bounds[0]&&y==bounds[1])state=next;else if(state!=next)return -3;
        }
        return state;
    }
    /** CUTOUT retains source order after range splitting, so these indices match the BLAS range. */
    public static int[] indices(byte[] triangles){
        var data=ByteBuffer.wrap(triangles).order(ByteOrder.nativeOrder());int[] indices=new int[triangles.length/120];int count=0;
        for(int offset=0;offset<triangles.length;offset+=120)if((data.getInt(offset+36)&1)!=0)indices[count++]=opacity(triangles,offset);
        return java.util.Arrays.copyOf(indices,count);
    }
    private static int[] bounds(ByteBuffer data,int offset,Grid current){
        float minU=1,minV=1,maxU=0,maxV=0;
        for(int v=0;v<3;v++){float u=data.getFloat(offset+v*40+12),t=data.getFloat(offset+v*40+16);if(!Float.isFinite(u)||!Float.isFinite(t))return null;minU=Math.min(minU,u);maxU=Math.max(maxU,u);minV=Math.min(minV,t);maxV=Math.max(maxV,t);}
        // One texel guard protects UV interpolation/atlas rounding at boundaries.
        return new int[]{Math.clamp((int)Math.floor(minU*current.width)-1,0,current.width-1),Math.clamp((int)Math.floor(minV*current.height)-1,0,current.height-1),Math.clamp((int)Math.floor(maxU*current.width)+1,0,current.width-1),Math.clamp((int)Math.floor(maxV*current.height)+1,0,current.height-1)};
    }
}
