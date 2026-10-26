/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import java.util.*;

/** CPU-owned full animation sheet; LabPBR math is the official converter. */
public final class SpritePixels {
    public final Identifier id;
    public final int width,height,frameWidth,frameHeight;
    public final byte[][] channels;
    public final boolean hasTransparency;
    private final MaterialLayout[] layouts=new MaterialLayout[8];
    public int[] frames={0},times={1};
    public boolean interpolate;
    public int currentFrame,nextFrame,subFrame,duration=1;
    public long revision;
    public SpritePixels(Identifier id,NativeImage image,int fw,int fh,LabPBRConverter.VariantCache variants) {
        this.id=id;width=image.getWidth();height=image.getHeight();frameWidth=fw;frameHeight=fh;
        MaterialLayout base=new MaterialLayout(width,height,fw,fh);Arrays.fill(layouts,base);
        int size=Math.multiplyExact(width,height);
        channels=new byte[8][];
        channels[0]=new byte[size*4];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int c=image.getPixel(x,y),i=(y*width+x)*4;
            channels[0][i]=(byte)(c>>16);channels[0][i+1]=(byte)(c>>8);channels[0][i+2]=(byte)c;channels[0][i+3]=(byte)(c>>>24);
        }
        boolean transparent=false;for(int i=3;i<channels[0].length;i+=4)if((channels[0][i]&255)<255){transparent=true;break;}
        hasTransparency=transparent;
        try(NativeImage n=variants.loadVariant(id,"_n");NativeImage s=variants.loadVariant(id,"_s");NativeImage sss=variants.loadVariant(id,"_sss")) {
            if(n!=null) {
                MaterialLayout layout=base.variant(n.getWidth(),n.getHeight());layouts[1]=layouts[2]=layout;
                int count=Math.multiplyExact(layout.width(),layout.height());
                channels[1]=new byte[count*4];LabPBRConverter.blitNormal(channels[1],layout.width(),n,0,0,layout.width(),layout.height());
                channels[2]=new byte[count];LabPBRConverter.blitHeight(channels[2],layout.width(),n,0,0,layout.width(),layout.height());
                if(layout.equals(base))LabPBRConverter.applyAO(channels[0],width,n,0,0,width,height);
                else try(var aligned=align(n,layout,base)) {LabPBRConverter.applyAO(channels[0],width,aligned,0,0,width,height);}
            }
            if(s!=null) {
                MaterialLayout layout=base.variant(s.getWidth(),s.getHeight());
                for(int channel=3;channel<=6;channel++)layouts[channel]=layout;
                int count=Math.multiplyExact(layout.width(),layout.height());
                channels[3]=new byte[count];LabPBRConverter.blitRoughness(channels[3],layout.width(),s,0,0,layout.width(),layout.height());
                channels[4]=new byte[count];LabPBRConverter.blitMetallic(channels[4],layout.width(),s,0,0,layout.width(),layout.height());
                if(LabPBRConverter.hasEmissiveData(s)) {
                    MaterialLayout emission=new MaterialLayout(layout.frameWidth()*base.columns(),layout.frameHeight()*(base.height()/base.frameHeight()),layout.frameWidth(),layout.frameHeight());
                    layouts[5]=emission;
                    channels[5]=new byte[Math.multiplyExact(emission.width(),emission.height())*4];
                    byte[] albedo=emission.equals(base)?channels[0]:align(channels[0],base,emission);
                    if(layout.equals(emission))LabPBRConverter.blitEmissive(channels[5],emission.width(),albedo,s,0,0,emission.width(),emission.height(),0,BuiltinEmission.gain(id));
                    else try(var aligned=align(s,layout,emission)) {LabPBRConverter.blitEmissive(channels[5],emission.width(),albedo,aligned,0,0,emission.width(),emission.height(),0,BuiltinEmission.gain(id));}
                }
                if(LabPBRConverter.hasSSSData(s)) {
                    channels[6]=new byte[count*4];LabPBRConverter.blitSSS(channels[6],layout.width(),s,0,0,layout.width(),layout.height());
                }
            }
            if(sss!=null) {
                MaterialLayout layout=base.variant(sss.getWidth(),sss.getHeight());layouts[7]=layout;
                channels[7]=new byte[Math.multiplyExact(layout.width(),layout.height())*4];
                LabPBRConverter.blitSingleScatteringAlbedo(channels[7],layout.width(),sss,0,0,layout.width(),layout.height());
            }
            if(channels[5]==null && s==null && BuiltinEmission.mask(id)!=null) {
                layouts[5]=base;
                channels[5]=new byte[size*4];LabPBRConverter.blitEmissiveMask(channels[5],width,channels[0],0,0,width,height,BuiltinEmission.mask(id));
            }
        }
    }
    private static NativeImage align(NativeImage source,MaterialLayout from,MaterialLayout to){
        NativeImage result=new NativeImage(to.width(),to.height(),false);
        for(int y=0;y<to.height();y++)for(int x=0;x<to.width();x++){
            int index=to.sourceIndex(x,y,from);result.setPixel(x,y,source.getPixel(index%from.width(),index/from.width()));
        }
        return result;
    }
    private SpritePixels(SpritePixels base,Identifier name,byte[] opaqueAlbedo){
        id=name;width=base.width;height=base.height;frameWidth=base.frameWidth;frameHeight=base.frameHeight;
        channels=base.channels.clone();channels[0]=opaqueAlbedo;System.arraycopy(base.layouts,0,layouts,0,8);hasTransparency=false;
    }
    /** Static opaque soil plus tinted grass coating, evaluated once instead of two intersecting POM surfaces. */
    public SpritePixels grassComposite(SpritePixels overlay,int baseTint,int overlayTint){
        if(animated(0)||overlay.animated(0)||channels[5]!=null||channels[6]!=null||overlay.channels[5]!=null||overlay.channels[6]!=null)return null;
        byte[] albedo=new byte[channels[0].length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int i=(y*width+x)*4,j=((y*overlay.height/height)*overlay.width+x*overlay.width/width)*4;
            float a=(overlay.channels[0][j+3]&255)/255f;
            for(int c=0;c<3;c++){
                int shift=16-c*8;float b=(channels[0][i+c]&255)*((baseTint>>>shift)&255)/255f;
                float o=(overlay.channels[0][j+c]&255)*((overlayTint>>>shift)&255)/255f;
                albedo[i+c]=(byte)Math.round(b*(1-a)+o*a);
            }
            albedo[i+3]=(byte)255;
        }
        var result=new SpritePixels(this,Identifier.fromNamespaceAndPath("rtxremix","block/grass_composite/"+Integer.toHexString(baseTint)+"_"+Integer.toHexString(overlayTint)),albedo);
        for(int channel=1;channel<=4;channel++)if(channels[channel]!=null&&overlay.channels[channel]!=null){
            var from=layouts[channel];var to=overlay.layouts[channel];if(from.frames()!=1||to.frames()!=1)return null;
            int components=channel==1?4:1;byte[] merged=channels[channel].clone();
            for(int y=0;y<from.height();y++)for(int x=0;x<from.width();x++){
                int i=(y*from.width()+x)*components,j=((y*to.height()/from.height())*to.width()+x*to.width()/from.width())*components;
                int mask=((y*overlay.height/from.height())*overlay.width+x*overlay.width/from.width())*4+3;
                float a=(overlay.channels[0][mask]&255)/255f;
                for(int c=0;c<components;c++)merged[i+c]=(byte)Math.round((channels[channel][i+c]&255)*(1-a)+(overlay.channels[channel][j+c]&255)*a);
            }
            result.channels[channel]=merged;
        }
        return result;
    }
    private static byte[] align(byte[] source,MaterialLayout from,MaterialLayout to){
        byte[] result=new byte[Math.multiplyExact(to.width(),to.height())*4];
        for(int y=0;y<to.height();y++)for(int x=0;x<to.width();x++)
            System.arraycopy(source,to.sourceIndex(x,y,from)*4,result,(y*to.width()+x)*4,4);
        return result;
    }
    public int frameWidth(int channel){return layouts[channel].frameWidth();}
    public int frameHeight(int channel){return layouts[channel].frameHeight();}
    public boolean animated(int channel){return layouts[channel].frames()>1;}
    public byte[] frame(int channel) {
        byte[] sheet=channels[channel]; if(sheet==null) return null;
        int components=(channel==2||channel==3||channel==4)?1:4;
        MaterialLayout layout=layouts[channel];int fw=layout.frameWidth(),fh=layout.frameHeight();
        byte[] out=new byte[fw*fh*components];
        int columns=layout.columns(),frame=layout.frame(currentFrame),next=layout.frame(nextFrame);
        int ox=frame%columns*fw,oy=frame/columns*fh;
        int nx=next%columns*fw,ny=next/columns*fh;
        float t=interpolate?(float)subFrame/duration:0;
        for(int y=0;y<fh;y++) for(int x=0;x<fw;x++) for(int c=0;c<components;c++) {
            int a=sheet[((oy+y)*layout.width()+ox+x)*components+c]&255;
            // Vanilla interpolates RGB and keeps current alpha. Discrete metalness is a data channel.
            int b=(t>0 && channel!=4 && !(components==4 && c==3))?sheet[((ny+y)*layout.width()+nx+x)*components+c]&255:a;
            out[(y*fw+x)*components+c]=(byte)(int)(a+(b-a)*t);
        }
        return out;
    }
}
