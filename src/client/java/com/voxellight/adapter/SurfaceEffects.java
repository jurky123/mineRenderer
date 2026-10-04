package com.voxellight.adapter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.VisualQuality;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;
/** Half-resolution material reflections and opt-in opaque HDR TAA share one composition target owner. */
final class SurfaceEffects implements AutoCloseable {
    static final RenderPipeline SURFACE_REFLECTION=reflectionPipeline(),SURFACE_COMPOSITE=resolvePipeline(false),COLOR_TEMPORAL=resolvePipeline(true);
    private final DepthPyramid pyramid=new DepthPyramid();
    private GpuTexture reflection;
    private GpuTextureView reflectionView;
    private final GpuTexture[] resolved=new GpuTexture[2];
    private final GpuTextureView[] resolvedViews=new GpuTextureView[2];
    private GpuBuffer settings;
    private int width,height,read;
    private boolean reflections=true,taa,historyValid,activeTaa,rtReflections,reference;
    void reference(boolean value){if(reference!=value)historyValid=false;reference=value;}
    void rtReflections(boolean value){rtReflections=value;}
    private long frames;
    private VisualQuality quality=VisualQuality.BALANCED;
    DepthPyramid pyramid(){return reflections?pyramid:null;}
    boolean needsMotion() {
        return reflections||taa;
    }
    void setReflections(boolean value) {
        reflections=value;
        historyValid=false;
    }
    void setTaa(boolean value) {
        taa=value;
        historyValid=false;
    }
    void setQuality(VisualQuality value) {
        quality=value;
    }
    boolean jitter(Matrix4f projection,RenderTarget target) {
        if(reference||!taa||(long)target.width*target.height*16>96L*1024*1024)return false;
        var device=RenderSystem.getDevice();
        if(!device.precompilePipeline(COLOR_TEMPORAL,RenderProbe.SHADERS).isValid()||!device.precompilePipeline(MotionFrame.MOTION,RenderProbe.SHADERS).isValid())return false;
        int sample=(int)(frames%8)+1;
        float x=(float)(halton(sample,2)-.5),y=(float)(halton(sample,3)-.5);
        projection.m20(projection.m20()-2*x/target.width);
        projection.m21(projection.m21()-2*y/target.height);
        return true;
    }
    static double halton(int index,int base) {
        double f=1,value=0;
        while(index>0) {
            f/=base;
            value+=f*(index%base);
            index/=base;
        }
        return value;
    }
    GpuTextureView render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,ShadowRenderer shadows,GpuTextureView input,GpuBuffer environment,GpuBuffer pbr,EnvironmentPass weather,MotionFrame motion) {
        activeTaa=false;
        if(!motion.ready()||((!taa||reference)&&(!reflections||rtReflections))){close();return input;}
        var device=RenderSystem.getDevice();
        boolean useTaa=taa&&!reference&&(long)output.width*output.height*16<=96L*1024*1024;
        var pipeline=useTaa?COLOR_TEMPORAL:SURFACE_COMPOSITE;
        if(!device.precompilePipeline(pipeline,RenderProbe.SHADERS).isValid())return input;
        int w=output.width,h=output.height;
        if(resolved[0]==null||width!=w||height!=h) {
            close();
            width=w;
            height=h;
        }
        if(settings==null)settings=device.createBuffer(()->"VoxelLight material reflection controls",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,32);
        int targets=useTaa?2:1;
        for(int i=0;i<targets;i++)if(resolved[i]==null) {
            resolved[i]=device.createTexture("VoxelLight surface HDR resolve "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,w,h,1,1);
            resolvedViews[i]=device.createTextureView(resolved[i]);
        }
        if(!useTaa&&resolved[1]!=null) {
            resolvedViews[1].close();
            resolved[1].close();
            resolvedViews[1]=null;
            resolved[1]=null;
            read=0;
            historyValid=false;
        }
        boolean reflect=reflections&&!rtReflections&&(long)((w+1)/2)*((h+1)/2)*8<=32L*1024*1024&&device.precompilePipeline(SURFACE_REFLECTION,RenderProbe.SHADERS).isValid();
        int rw=reflect?(w+1)/2:1,rh=reflect?(h+1)/2:1;
        if(reflection!=null&&(reflection.getWidth(0)!=rw||reflection.getHeight(0)!=rh)){reflectionView.close();reflection.close();reflectionView=null;reflection=null;}
        if(reflection==null) {
            reflection=device.createTexture("VoxelLight half-resolution specular delta",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,rw,rh,1,1);
            reflectionView=device.createTextureView(reflection);
        }
        boolean hzb=reflect&&pyramid.build(encoder,output.getDepthTextureView(),w,h);
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,32).putVec4(reflect?quality.reflectionSteps():0,useTaa&&historyValid?1:0,reflect?1:0,0).putVec4(hzb?1:0,Math.max(0,pyramid.levels()-1),quality==VisualQuality.FAST?48:quality==VisualQuality.BALANCED?80:128,0).get());
        }
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try(var profile=RenderPassProfile.begin(encoder,"surface_reflections");var pass=encoder.createRenderPass(descriptor(reflectionView,rw,rh))) {
            if(reflect) {
                pass.setPipeline(SURFACE_REFLECTION);
                pass.bindTexture("CurrentHdr",input,nearest);
                pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
                pass.bindTexture("SceneHzb",hzb?pyramid.view():output.getDepthTextureView(),nearest);
                pass.bindTexture("MaterialNormal",material.view(1),nearest);
                pass.bindTexture("MaterialPbr",material.view(4),nearest);
                pass.bindTexture("MaterialTable",material.materialTable(),nearest);
                pass.bindTexture("MaterialAlbedo",material.view(0),nearest);
                pass.bindTexture("MaterialEmission",material.view(2),nearest);
                pass.setUniform("SurfaceSettings",settings);
                pass.setUniform("PbrSettings",pbr);
                pass.setUniform("LightingEnvironment",environment);
                weather.bind(pass);
                shadows.bindTransform(pass);
                pass.draw(3,1,0,0);
            }
        }
        int write=useTaa?1-read:0;
        try(var profile=RenderPassProfile.begin(encoder,useTaa?"color_temporal":"surface_composite");var pass=encoder.createRenderPass(descriptor(resolvedViews[write],w,h))) {
            pass.setPipeline(pipeline);
            pass.bindTexture("CurrentHdr",input,nearest);
            pass.bindTexture("ReflectionDelta",reflectionView,nearest);
            pass.bindTexture("MaterialNormal",material.view(1),nearest);
            pass.bindTexture("MaterialAlbedo",material.view(0),nearest);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
            pass.setUniform("SurfaceSettings",settings);
            motion.bind(pass);
            if(useTaa)pass.bindTexture("ColorHistory",resolvedViews[read],nearest);
            pass.draw(3,1,0,0);
        }
        read=write;
        historyValid=useTaa;
        activeTaa=useTaa;
        frames++;
        return resolvedViews[write];
    }
    String status() {
        return ", materialReflections="+reflections+", colorTaa="+(activeTaa?"opaque static surfaces":taa?"waiting/budget":"off")+", surfaceEffectBytes="+(resolved[0]==null?0:(long)width*height*8*(resolved[1]==null?1:2)+(reflection==null?0:(long)reflection.getWidth(0)*reflection.getHeight(0)*8))+", surfaceHzbBytes="+pyramid.bytes();
    }
    @Override public void close() {
        pyramid.close();
        if(reflectionView!=null) {
            reflectionView.close();
            reflectionView=null;
        }
        if(reflection!=null) {
            reflection.close();
            reflection=null;
        }
        for(int i=0;i<2;i++) {
            if(resolvedViews[i]!=null) {
                resolvedViews[i].close();
                resolvedViews[i]=null;
            }
            if(resolved[i]!=null) {
                resolved[i].close();
                resolved[i]=null;
            }
        }
        if(settings!=null) {
            settings.close();
            settings=null;
        }
        historyValid=activeTaa=false;
        read=width=height=0;
    }
    private static RenderPassDescriptor descriptor(GpuTextureView view,int w,int h) {
        return RenderPassDescriptor.create(()->"VoxelLight surface effects").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(view,Optional.of(new Vector4f(0)));
    }
    private static RenderPipeline resolvePipeline(boolean temporal) {
        var layout=MotionFrame.layout(BindGroupLayout.builder().withSampler("CurrentHdr").withSampler("ReflectionDelta").withSampler("MaterialNormal").withSampler("MaterialAlbedo").withSampler("SceneDepth").withUniform("SurfaceSettings",UniformType.UNIFORM_BUFFER));
        if(temporal)layout.withSampler("ColorHistory");
        var builder=base(temporal?"color_temporal":"surface_composite","surface_composite",layout.build());
        if(temporal)builder.withShaderDefine("COLOR_TEMPORAL");
        return builder.build();
    }
    private static RenderPipeline reflectionPipeline() {
        return base("surface_reflection","surface_reflection",BindGroupLayout.builder().withSampler("SceneDepth").withSampler("CurrentHdr").withSampler("SceneHzb").withSampler("MaterialNormal").withSampler("MaterialPbr").withSampler("MaterialTable").withSampler("MaterialAlbedo").withSampler("MaterialEmission").withUniform("SurfaceSettings",UniformType.UNIFORM_BUFFER).withUniform("PbrSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER).withUniform("EnvironmentSettings",UniformType.UNIFORM_BUFFER).withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).build()).build();
    }
    private static RenderPipeline.Builder base(String name,String shader,BindGroupLayout layout) {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/"+name)).withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",shader)).withBindGroupLayout(layout).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
    }
}
