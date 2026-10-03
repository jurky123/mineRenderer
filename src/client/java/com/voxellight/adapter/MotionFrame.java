package com.voxellight.adapter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.VoxelLightClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.*;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;
/** Shared static-surface motion and rejection guides. Dynamic/animated surfaces never reuse history. */
final class MotionFrame implements AutoCloseable  {
    static final RenderPipeline MOTION=pipeline();
    private final GpuTexture[] textures=new GpuTexture[5];
    private final GpuTextureView[] views=new GpuTextureView[5];
    private GpuBuffer settings;
    private int width,height,read;
    private long world=-1,resources=-1,lastTime,frames;
    private Matrix4f previous=new Matrix4f(),current=new Matrix4f();
    private Vector3d previousCamera=new Vector3d(),currentCamera=new Vector3d();
    private boolean ready,valid,pending;
    boolean prepare(CommandEncoder encoder,RenderTarget target,MaterialCapture material,Matrix4f projection,boolean observed) {
        ready=false;
        int w=(target.width+1)/2,h=(target.height+1)/2;
        if(!observed||(long)w*h*20>48L*1024*1024) {
            close();
            return false;
        }
        var device=RenderSystem.getDevice();
        if(!device.precompilePipeline(MOTION,RenderProbe.SHADERS).isValid())return false;
        if(textures[0]==null||w!=width||h!=height) {
            close();
            width=w;
            height=h;
            for(int i=0;i<5;i++) {
                var format=i==4?GpuFormat.RG16_FLOAT:i%2==0?GpuFormat.R32_FLOAT:GpuFormat.RGBA8_UNORM;
                textures[i]=device.createTexture("VoxelLight shared motion guide "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,format,w,h,1,1);
                views[i]=device.createTextureView(textures[i]);
            }
            settings=device.createBuffer(()->"VoxelLight shared temporal frame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,288);
        }
        var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        current.set(projection).mul(camera.viewRotationMatrix);
        currentCamera.set(camera.pos.x(),camera.pos.y(),camera.pos.z());
        var stats=VoxelLightClient.scene().bridge().stats();
        long now=System.nanoTime();
        boolean reuse=valid&&world==stats.worldGeneration()&&resources==stats.resourceGeneration()&&now-lastTime<300_000_000L&&previousCamera.distance(currentCamera)<32;
        Vector3d delta=new Vector3d(currentCamera).sub(previousCamera);
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,288).putMat4f(new Matrix4f(current).invert()).putMat4f(current).putMat4f(previous).putMat4f(new Matrix4f(previous).invert()).putVec4((float)delta.x,(float)delta.y,(float)delta.z,reuse?1:0).putVec4(.88f,.035f,.0015f,(float)(frames%256)).get());
        }
        int write=1-read;
        var descriptor=RenderPassDescriptor.create(()->"VoxelLight camera motion and surface guides").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(views[4],Optional.of(new Vector4f(0))).withColorAttachment(views[write*2],Optional.of(new Vector4f(0))).withColorAttachment(views[write*2+1],Optional.of(new Vector4f(0)));
        try(var profile=RenderPassProfile.begin(encoder,"motion_guides");var pass=encoder.createRenderPass(descriptor)) {
            pass.setPipeline(MOTION);
            var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SceneDepth",target.getDepthTextureView(),nearest);
            pass.bindTexture("MaterialNormal",material.view(1),nearest);
            pass.bindTexture("MaterialAlbedo",material.view(0),nearest);
            pass.setUniform("MotionSettings",settings);
            pass.draw(3,1,0,0);
        }
        world=stats.worldGeneration();
        resources=stats.resourceGeneration();
        lastTime=now;
        pending=ready=true;
        return true;
    }
    void bind(RenderPass pass) {
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("MotionSettings",settings);
        pass.bindTexture("PreviousDepth",views[read*2],nearest);
        pass.bindTexture("PreviousNormal",views[read*2+1],nearest);
        pass.bindTexture("MotionVectors",views[4],nearest);
    }
    boolean ready() {
        return ready;
    }
    void endFrame() {
        if(pending) {
            read=1-read;
            previous.set(current);
            previousCamera.set(currentCamera);
            valid=true;
            frames++;
        }
        pending=ready=false;
    }
    static BindGroupLayout.Builder layout(BindGroupLayout.Builder layout) {
        return layout.withUniform("MotionSettings",UniformType.UNIFORM_BUFFER).withSampler("PreviousDepth").withSampler("PreviousNormal").withSampler("MotionVectors");
    }
    String status() {
        return ", motionGuides=static camera reprojection, motionBytes="+(textures[0]==null?0:(long)width*height*20);
    }
    @Override public void close() {
        for(int i=0;i<5;i++) {
            if(views[i]!=null) {
                views[i].close();
                views[i]=null;
            }
            if(textures[i]!=null) {
                textures[i].close();
                textures[i]=null;
            }
        }
        if(settings!=null) {
            settings.close();
            settings=null;
        }
        valid=ready=pending=false;
        width=height=read=0;
    }
    private static RenderPipeline pipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/motion_guides")).withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","motion_guides")).withBindGroupLayout(BindGroupLayout.builder().withUniform("MotionSettings",UniformType.UNIFORM_BUFFER).withSampler("SceneDepth").withSampler("MaterialNormal").withSampler("MaterialAlbedo").build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RG16_FLOAT,ColorTargetState.WRITE_ALL)).withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.R32_FLOAT,ColorTargetState.WRITE_ALL)).withColorTargetState(2,new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
