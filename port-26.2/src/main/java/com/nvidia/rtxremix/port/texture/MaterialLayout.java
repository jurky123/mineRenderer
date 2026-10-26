/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;

/** Independent channel resolution, sharing albedo's frame indices or repeating a static map. */
public record MaterialLayout(int width,int height,int frameWidth,int frameHeight){
    public MaterialLayout{
        if(width<=0||height<=0||frameWidth<=0||frameHeight<=0||width%frameWidth!=0||height%frameHeight!=0)
            throw new IllegalArgumentException("Invalid material sheet dimensions");
    }
    public int columns(){return width/frameWidth;}
    public int frames(){return columns()*(height/frameHeight);}
    public int frame(int index){return frames()==1?0:index;}
    public MaterialLayout variant(int w,int h){
        int columns=columns(),rows=height/frameHeight;
        if((long)w*height==(long)h*width&&w%columns==0&&h%rows==0)
            return new MaterialLayout(w,h,w/columns,h/rows);
        if((long)w*frameHeight==(long)h*frameWidth)return new MaterialLayout(w,h,w,h);
        // Older packs may retain extra animation tiles after vanilla shortens an animation.
        if(w%columns==0){
            int fw=w/columns;
            if((long)fw*frameHeight%frameWidth==0){
                int fh=(int)((long)fw*frameHeight/frameWidth);
                if(fh>0&&h%fh==0&&columns*(h/fh)>=frames())return new MaterialLayout(w,h,fw,fh);
            }
        }
        throw new IllegalArgumentException("PBR map must match the animation sheet or one static frame: "+w+"x"+h);
    }
    /** Pixel-centred nearest sampling in the same frame; never wrap into another quadrant. */
    public int sourceIndex(int x,int y,MaterialLayout source){
        int frame=(y/frameHeight)*columns()+x/frameWidth;
        int sourceFrame=source.frame(frame);
        int sx=Math.min(source.frameWidth-1,(int)(((long)(x%frameWidth)*2+1)*source.frameWidth/(2L*frameWidth)));
        int sy=Math.min(source.frameHeight-1,(int)(((long)(y%frameHeight)*2+1)*source.frameHeight/(2L*frameHeight)));
        return (sourceFrame/source.columns()*source.frameHeight+sy)*source.width+sourceFrame%source.columns()*source.frameWidth+sx;
    }
}
