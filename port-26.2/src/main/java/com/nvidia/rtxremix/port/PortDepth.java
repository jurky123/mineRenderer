/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL45.*;

/** Upstream weather DepthSeeder, adapted to the actual 26.2 reversed projection. */
public final class PortDepth {
    private final int program,vao,fbo;
    public long copies;
    public PortDepth(){
        int vertex=compile(GL_VERTEX_SHADER,"""
            #version 330 core
            void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0.,1.);}
            """);
        int fragment=compile(GL_FRAGMENT_SHADER,"""
            #version 330 core
            uniform sampler2D RemixDepth;
            uniform vec2 FramebufferSize;
            uniform vec2 RuntimePlanes;
            uniform vec4 ProjectionZ;
            uniform bool ZeroToOne;
            void main(){
                vec2 uv=vec2(gl_FragCoord.x/FramebufferSize.x,1.-gl_FragCoord.y/FramebufferSize.y);
                float d=texture(RemixDepth,uv).r;
                float n=RuntimePlanes.x,f=RuntimePlanes.y;
                float z=-n*f/(f-d*(f-n));
                float ndc=(ProjectionZ.x*z+ProjectionZ.y)/(ProjectionZ.z*z+ProjectionZ.w);
                gl_FragDepth=d>=1.?0.:clamp(ZeroToOne?ndc:ndc*.5+.5,0.,1.);
            }
            """);
        int linked=glCreateProgram();
        try{glAttachShader(linked,vertex);glAttachShader(linked,fragment);glLinkProgram(linked);if(glGetProgrami(linked,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(linked));}
        catch(RuntimeException e){glDeleteProgram(linked);throw e;}
        finally{glDeleteShader(vertex);glDeleteShader(fragment);}
        program=linked;vao=glCreateVertexArrays();fbo=glCreateFramebuffers();
        glNamedFramebufferDrawBuffer(fbo,GL_NONE);glNamedFramebufferReadBuffer(fbo,GL_NONE);
    }
    public static float projectedDepth(float depth,float near,float far,Matrix4fc projection,boolean zeroToOne){
        if(depth>=1)return 0;
        double z=-(double)near*far/(far-(double)depth*(far-near));
        double d=(projection.m22()*z+projection.m32())/(projection.m23()*z+projection.m33());
        return (float)(zeroToOne?d:d*.5+.5);
    }
    public void copy(RenderTarget target,int source,float near,float far,Matrix4fc projection,boolean zeroToOne){
        if(source==0)throw new IllegalStateException("Remix primary depth is unavailable");
        if(!(target.getDepthTexture() instanceof GlTexture depth))throw new IllegalStateException("Expected GL depth target");
        int previousFbo=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),previousProgram=glGetInteger(GL_CURRENT_PROGRAM),previousVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),function=glGetInteger(GL_DEPTH_FUNC);
        boolean mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        int[] caps={GL_DEPTH_TEST,GL_SCISSOR_TEST,GL_BLEND,GL_CULL_FACE,GL_STENCIL_TEST,GL_POLYGON_OFFSET_FILL};boolean[] states=new boolean[caps.length];
        for(int i=0;i<caps.length;i++)states[i]=glIsEnabled(caps[i]);
        glActiveTexture(GL_TEXTURE0);int texture=glGetInteger(GL_TEXTURE_BINDING_2D),sampler=glGetIntegeri(GL_SAMPLER_BINDING,0);
        try(var stack=MemoryStack.stackPush()){
            var viewport=stack.mallocInt(4);glGetIntegerv(GL_VIEWPORT,viewport);
            var range=stack.mallocDouble(2);glGetDoublev(GL_DEPTH_RANGE,range);
            try{
                glNamedFramebufferTexture(fbo,GL_DEPTH_ATTACHMENT,depth.glId(),0);
                if(glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete depth composition target");
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);glViewport(0,0,target.width,target.height);glDepthRange(0,1);
                for(int cap:caps)glDisable(cap);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_ALWAYS);glDepthMask(true);
                glUseProgram(program);glBindVertexArray(vao);glBindTexture(GL_TEXTURE_2D,source);glBindSampler(0,0);
                glUniform1i(glGetUniformLocation(program,"RemixDepth"),0);
                glUniform2f(glGetUniformLocation(program,"FramebufferSize"),target.width,target.height);
                glUniform2f(glGetUniformLocation(program,"RuntimePlanes"),near,far);
                glUniform4f(glGetUniformLocation(program,"ProjectionZ"),projection.m22(),projection.m32(),projection.m23(),projection.m33());
                glUniform1i(glGetUniformLocation(program,"ZeroToOne"),zeroToOne?1:0);
                glDrawArrays(GL_TRIANGLES,0,3);copies++;
            }finally{
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER,previousFbo);glViewport(viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3));glDepthRange(range.get(0),range.get(1));
                glUseProgram(previousProgram);glBindVertexArray(previousVao);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,sampler);glActiveTexture(active);
                glDepthMask(mask);glDepthFunc(function);for(int i=0;i<caps.length;i++)if(states[i])glEnable(caps[i]);else glDisable(caps[i]);
            }
        }
        int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("Depth composition GL error="+error);
    }
    private static int compile(int type,String source){int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE){String message=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException(message);}return shader;}
    public void close(){glDeleteProgram(program);glDeleteVertexArrays(vao);glDeleteFramebuffers(fbo);}
}
