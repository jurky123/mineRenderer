package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.AmbientOcclusion;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Owns half-resolution terrain AO and guides. No frame history and no native depth writes. */
final class AmbientOcclusionPass implements AutoCloseable {
    static final RenderPipeline AO= pipeline(false), AO_FILTER=pipeline(true);
    private final GpuTexture[] textures=new GpuTexture[2];
    private final GpuTextureView[] views=new GpuTextureView[2];
    private GpuTexture neutral;
    private GpuTextureView neutralView;
    private GpuBuffer settings;
    private int width,height;
    private boolean enabled=true,debug,active;
    private String state="waiting";
    private boolean prepare(CommandEncoder encoder,RenderTarget output) {
        active=false;
        var device=RenderSystem.getDevice();
        if(settings==null)settings=device.createBuffer(()->"VoxelLight AO settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,AmbientOcclusion.SETTINGS_BYTES);
        if(neutral==null) {
            neutral=device.createTexture("VoxelLight neutral AO",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,1,1,1,1);
            neutralView=device.createTextureView(neutral);
            try(var pass=encoder.createRenderPass(descriptor(neutralView,1,1,"VoxelLight neutral AO clear"))) { }
        }
        if(!enabled || AmbientOcclusion.targetBytes(output.width,output.height)>AmbientOcclusion.TARGET_LIMIT) {
            releaseTargets();state=enabled?"target budget exceeded; ambient retained":"off";return false;
        }
        if(!device.precompilePipeline(AO,RenderProbe.SHADERS).isValid() || !device.precompilePipeline(AO_FILTER,RenderProbe.SHADERS).isValid())
            throw new IllegalStateException("Terrain AO shader compilation failed");
        int nextWidth=AmbientOcclusion.halfSize(output.width),nextHeight=AmbientOcclusion.halfSize(output.height);
        if(textures[0]==null || width!=nextWidth || height!=nextHeight) {
            releaseTargets();width=nextWidth;height=nextHeight;
            for(int i=0;i<2;i++) {
                textures[i]=device.createTexture("VoxelLight half-resolution AO "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,width,height,1,1);
                views[i]=device.createTextureView(textures[i]);
            }
        }
        active=true;state="half-res spatial terrain";return true;
    }
    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows) {
        prepare(encoder,output);
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,AmbientOcclusion.SETTINGS_BYTES)
                    .putVec4(AmbientOcclusion.RADIUS,AmbientOcclusion.STRENGTH,AmbientOcclusion.BIAS,AmbientOcclusion.SCREEN_RADIUS)
                    .putVec4(AmbientOcclusion.PLANE_TOLERANCE,AmbientOcclusion.NORMAL_DOT,active?1:0,debug?1:0).get());
        }
        if(!active)return;
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try(var pass=encoder.createRenderPass(descriptor(views[0],width,height,"VoxelLight terrain horizon AO"))) {
            pass.setPipeline(AO);
            pass.bindTexture("MaterialNormal",material.view(1),nearest);
            pass.bindTexture("MaterialDepth",material.view(3),nearest);
            pass.bindTexture("MaterialAlbedo",material.view(0),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindTransform(pass);pass.setUniform("AoSettings",settings);pass.draw(3,1,0,0);
        }
        try(var pass=encoder.createRenderPass(descriptor(views[1],width,height,"VoxelLight AO bilateral spatial filter"))) {
            pass.setPipeline(AO_FILTER);pass.bindTexture("AoInput",views[0],nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            shadows.bindTransform(pass);pass.setUniform("AoSettings",settings);pass.draw(3,1,0,0);
        }
    }
    void bind(RenderPass pass) {
        pass.bindTexture("AmbientVisibility",active?views[1]:neutralView,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.setUniform("AoSettings",settings);
    }
    void bindSettings(RenderPass pass){pass.setUniform("AoSettings",settings);}
    void setEnabled(boolean value,boolean show){enabled=value;debug=show;active=false;state=value?"waiting":"off";if(!enabled)releaseTargets();}
    String status(){return ", ao="+state+(debug?"; grayscale view":"")+", aoBytes="+(textures[0]==null?0:(long)width*height*AmbientOcclusion.PIXEL_BYTES)+", aoSize="+width+"x"+height;}
    static RenderPassDescriptor descriptor(GpuTextureView target,int width,int height,String label) {
        return RenderPassDescriptor.create(()->label).withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(target,Optional.of(new Vector4f(1,.5f,.5f,-1)));
    }
    private void releaseTargets() {
        for(int i=0;i<2;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(textures[i]!=null){textures[i].close();textures[i]=null;}}
        width=height=0;active=false;
    }
    @Override public void close() {
        releaseTargets();if(neutralView!=null){neutralView.close();neutralView=null;}if(neutral!=null){neutral.close();neutral=null;}
        if(settings!=null){settings.close();settings=null;}state="waiting";
    }
    private static RenderPipeline pipeline(boolean filter) {
        var layout=BindGroupLayout.builder().withSampler("SceneDepth");
        if(filter)layout.withSampler("AoInput");else layout.withSampler("MaterialNormal").withSampler("MaterialDepth").withSampler("MaterialAlbedo");
        layout.withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).withUniform("AoSettings",UniformType.UNIFORM_BUFFER);
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",filter?"pipeline/ao_filter":"pipeline/ao"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",filter?"ao_filter":"ao"))
                .withBindGroupLayout(layout.build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
