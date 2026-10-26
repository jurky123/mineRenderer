/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.sky;

import static org.lwjgl.opengl.GL45.*;

/** Adapt the upstream generator's GL boundary to 26.2's cached renderer state. */
final class SkyGlState implements AutoCloseable {
    private final int program=glGetInteger(GL_CURRENT_PROGRAM),active=glGetInteger(GL_ACTIVE_TEXTURE);
    private final int pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int ubo=glGetInteger(GL_UNIFORM_BUFFER_BINDING),base=glGetIntegeri(GL_UNIFORM_BUFFER_BINDING,0);
    private final long offset=glGetInteger64i(GL_UNIFORM_BUFFER_START,0),size=glGetInteger64i(GL_UNIFORM_BUFFER_SIZE,0);
    private final int[] units={0,14,15},textures=new int[3];
    private final int[] imageProps={GL_IMAGE_BINDING_NAME,GL_IMAGE_BINDING_LEVEL,GL_IMAGE_BINDING_LAYERED,GL_IMAGE_BINDING_LAYER,GL_IMAGE_BINDING_ACCESS,GL_IMAGE_BINDING_FORMAT};
    private final int[][] images=new int[4][6];
    private final int[] pixelProps={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_ROWS,GL_PACK_SKIP_PIXELS,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_IMAGES,GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_PIXELS,GL_UNPACK_IMAGE_HEIGHT,GL_UNPACK_SKIP_IMAGES};
    private final int[] pixels=new int[pixelProps.length];
    SkyGlState(){
        for(int i=0;i<units.length;i++){glActiveTexture(GL_TEXTURE0+units[i]);textures[i]=glGetInteger(GL_TEXTURE_BINDING_2D);}
        for(int i=0;i<images.length;i++)for(int j=0;j<imageProps.length;j++)images[i][j]=glGetIntegeri(imageProps[j],i);
        for(int i=0;i<pixelProps.length;i++){pixels[i]=glGetInteger(pixelProps[i]);glPixelStorei(pixelProps[i],i==0||i==6?1:0);}
        glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glActiveTexture(active);
    }
    public void close(){
        for(int i=0;i<units.length;i++){glActiveTexture(GL_TEXTURE0+units[i]);glBindTexture(GL_TEXTURE_2D,textures[i]);}
        for(int i=0;i<images.length;i++){int[] v=images[i];glBindImageTexture(i,v[0],v[1],v[2]!=0,v[3],v[4],v[5]);}
        if(base!=0&&size>0)glBindBufferRange(GL_UNIFORM_BUFFER,0,base,offset,size);else glBindBufferBase(GL_UNIFORM_BUFFER,0,base);
        glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);
        for(int i=0;i<pixelProps.length;i++)glPixelStorei(pixelProps[i],pixels[i]);
        glActiveTexture(active);glUseProgram(program);
    }
}
