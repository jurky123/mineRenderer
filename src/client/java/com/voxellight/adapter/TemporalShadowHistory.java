package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.VoxelLightClient;
import com.voxellight.world.TemporalShadowState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.*;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Terrain shadow visibility only; entity material and dynamic shadow footprints never enter history. */
final class TemporalShadowHistory implements AutoCloseable {
    static final RenderPipeline TEMPORAL= pipeline();
    private final TemporalShadowState state=new TemporalShadowState();
    private final GpuTexture[] textures=new GpuTexture[4];
    private final GpuTextureView[] views=new GpuTextureView[4];
    private GpuBuffer settings;
    private int width,height,read;
    private boolean enabled=false,active;
    private String fallback="waiting";
    boolean prepare(RenderTarget target,boolean projectionObserved) {
        active=false;
        if(!enabled){release();fallback="off";return false;}
        if(!projectionObserved){release();fallback="actual projection hook not observed";return false;}
        if(TemporalShadowState.targetBytes(target.width,target.height)>TemporalShadowState.TARGET_LIMIT){release();fallback="history budget exceeded; current shadows retained";return false;}
        var device=RenderSystem.getDevice();
        if(!device.precompilePipeline(TEMPORAL,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Temporal shadow shader compilation failed");
        if(textures[0]==null || width!=target.width || height!=target.height) {
            release();width=target.width;height=target.height;state.invalidate("new/resize history");
            for(int i=0;i<4;i++) {
                textures[i]=device.createTexture("VoxelLight temporal shadow "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,width,height,1,1);
                views[i]=device.createTextureView(textures[i]);
            }
            settings=device.createBuffer(()->"VoxelLight shadow history reprojection",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,TemporalShadowState.SETTINGS_BYTES);
        }
        active=true;fallback="active";return true;
    }
    GpuTextureView input(){return views[2];}
    GpuTextureView resolve(CommandEncoder encoder,GpuTextureView currentHdr,MaterialCapture material,ShadowRenderer shadows,Matrix4f actualProjection) {
        var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var bridge=VoxelLightClient.scene().bridge();var stats=bridge.stats();
        var frame=new TemporalShadowState.Frame(camera.pos.x(),camera.pos.y(),camera.pos.z(),
                new Matrix4f(actualProjection).mul(camera.viewRotationMatrix),new Matrix4f(camera.viewRotationMatrix),
                new TemporalShadowState.Key(stats.worldGeneration(),stats.resourceGeneration(),bridge.geometryChangeRevision(),shadows.geometryRevision(),shadows.light().source()),
                shadows.light().angleRadians(),System.nanoTime());
        var admission=state.admit(frame);
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,TemporalShadowState.SETTINGS_BYTES)
                    .putMat4f(admission.previousWorldToClip()).putVec4(admission.cameraDelta().x,admission.cameraDelta().y,admission.cameraDelta().z,admission.reuse()?1:0)
                    .putVec4(TemporalShadowState.WEIGHT,TemporalShadowState.DEPTH_ABSOLUTE,TemporalShadowState.DEPTH_RELATIVE,TemporalShadowState.NORMAL_DOT).get());
        }
        int write=1-read;
        var descriptor=descriptor(views[3],views[write],width,height);
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var profile = RenderPassProfile.begin(encoder,"temporal"); var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(TEMPORAL);
            pass.bindTexture("CurrentHdr",currentHdr,nearest);pass.bindTexture("CurrentShadow",input(),nearest);
            pass.bindTexture("MaterialNormal",material.view(1),nearest);pass.bindTexture("MaterialDepth",material.view(3),nearest);pass.bindTexture("History",views[read],nearest);
            shadows.bindTransform(pass);pass.setUniform("TemporalSettings",settings);pass.draw(3,1,0,0);
        }
        state.commit(frame,admission);read=write;
        return views[3];
    }
    static RenderPassDescriptor descriptor(GpuTextureView resolved,GpuTextureView history,int width,int height) {
        return RenderPassDescriptor.create(()->"VoxelLight shadow reprojection and rejection")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(resolved,Optional.of(new Vector4f(0)))
                .withColorAttachment(history,Optional.of(new Vector4f(-1,0,0,0)));
    }
    void setEnabled(boolean value){enabled=value;release();state.invalidate(value?"enabled":"off");}
    void invalidate(){state.invalidate("settings/reset");}
    String status(){return ", "+(active?state.status():"temporal="+fallback)+", temporalHistoryBytes="+(textures[0]==null?0:TemporalShadowState.targetBytes(width,height));}
    private void release() {
        for(int i=0;i<4;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(textures[i]!=null){textures[i].close();textures[i]=null;}}
        if(settings!=null){settings.close();settings=null;}width=height=read=0;active=false;
    }
    @Override public void close(){release();state.invalidate("reset");fallback="waiting";}
    private static RenderPipeline pipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/temporal_shadow"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","temporal_shadow"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("CurrentHdr").withSampler("CurrentShadow").withSampler("MaterialNormal").withSampler("MaterialDepth").withSampler("History")
                        .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).withUniform("TemporalSettings",UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
