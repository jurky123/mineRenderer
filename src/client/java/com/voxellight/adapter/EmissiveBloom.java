package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.VisualPolish;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Quarter-resolution emission-only bloom. Captured once before late entity material overwrites. */
final class EmissiveBloom implements AutoCloseable {
    static final RenderPipeline BLOOM_EXTRACT=pipeline(false), BLOOM_BLUR=pipeline(true);
    private final GpuTexture[] textures=new GpuTexture[2];
    private final GpuTextureView[] views=new GpuTextureView[2];
    private GpuTexture neutral;
    private GpuTextureView neutralView;
    private GpuBuffer settings;
    private int width,height;
    private boolean active;
    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,boolean enabled) {
        var device=RenderSystem.getDevice();
        active=false;
        if(neutral==null) {
            neutral=device.createTexture("VoxelLight black bloom",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,1,1,1,1);
            neutralView=device.createTextureView(neutral);
            try (var profile = RenderPassProfile.begin(encoder,"bloom_clear"); var pass = encoder.createRenderPass(descriptor(neutralView,1,1))){ }
        }
        if(!enabled || VisualPolish.bloomBytes(output.width,output.height)>VisualPolish.BLOOM_LIMIT){releaseTargets();return;}
        if(!device.precompilePipeline(BLOOM_EXTRACT,RenderProbe.SHADERS).isValid() || !device.precompilePipeline(BLOOM_BLUR,RenderProbe.SHADERS).isValid())
            throw new IllegalStateException("Emissive bloom shader compilation failed");
        int nextWidth=VisualPolish.quarterSize(output.width),nextHeight=VisualPolish.quarterSize(output.height);
        if(textures[0]==null || width!=nextWidth || height!=nextHeight) {
            releaseTargets();width=nextWidth;height=nextHeight;
            for(int i=0;i<2;i++) {
                textures[i]=device.createTexture("VoxelLight quarter-resolution bloom "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,width,height,1,1);
                views[i]=device.createTextureView(textures[i]);
            }
        }
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var profile = RenderPassProfile.begin(encoder,"bloom_extract"); var pass = encoder.createRenderPass(descriptor(views[0],width,height))) {
            pass.setPipeline(BLOOM_EXTRACT);
            pass.bindTexture("MaterialAlbedo",material.view(0),nearest);
            pass.bindTexture("MaterialNormal",material.view(1),nearest);
            pass.bindTexture("MaterialEmission",material.view(2),nearest);
            pass.bindTexture("MaterialDepth",material.view(3),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            pass.draw(3,1,0,0);
        }
        if(settings==null)settings=device.createBuffer(()->"VoxelLight bloom blur direction",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
        for(int axis=0;axis<2;axis++) {
            try(var stack=MemoryStack.stackPush()) {
                encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,16).putVec4(axis==0?1:0,axis==1?1:0,0,0).get());
            }
            try (var profile = RenderPassProfile.begin(encoder,"bloom_filter"); var pass = encoder.createRenderPass(descriptor(views[1-axis],width,height))) {
                pass.setPipeline(BLOOM_BLUR);
                pass.bindTexture("BloomInput",views[axis],RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("BloomSettings",settings);pass.draw(3,1,0,0);
            }
        }
        active=true;
    }
    void bind(RenderPass pass) {pass.bindTexture("EmissiveBloom",active?views[0]:neutralView,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));}
    String status(){return ", bloom="+(active?"quarter-res emissive":"off/budget fallback")+", bloomBytes="+(active?(long)width*height*16:0);}
    static RenderPassDescriptor descriptor(GpuTextureView target,int width,int height) {
        return RenderPassDescriptor.create(()->"VoxelLight emissive bloom").withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(target,Optional.of(new Vector4f(0)));
    }
    private void releaseTargets(){for(int i=0;i<2;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(textures[i]!=null){textures[i].close();textures[i]=null;}}width=height=0;active=false;}
    @Override public void close(){releaseTargets();if(neutralView!=null){neutralView.close();neutralView=null;}if(neutral!=null){neutral.close();neutral=null;}if(settings!=null){settings.close();settings=null;}}
    private static RenderPipeline pipeline(boolean blur) {
        var layout=BindGroupLayout.builder();
        if(blur)layout.withSampler("BloomInput").withUniform("BloomSettings",UniformType.UNIFORM_BUFFER);
        else layout.withSampler("MaterialAlbedo").withSampler("MaterialNormal").withSampler("MaterialEmission").withSampler("MaterialDepth").withSampler("SceneDepth");
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",blur?"pipeline/bloom_blur":"pipeline/bloom_extract"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",blur?"bloom_blur":"bloom_extract"))
                .withBindGroupLayout(layout.build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
}
