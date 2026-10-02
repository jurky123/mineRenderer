package com.voxellight.adapter;

import com.mojang.blaze3d.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import com.voxellight.world.MaterialEncoding;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;

/** Actual native opaque model vertices, with independent bounded material ownership. */
final class EntityMaterials implements AutoCloseable {
    static final RenderPipeline CULL=pipeline(true),NO_CULL=pipeline(false);
    private record Draw(int base,int indices,int style,PreparedRenderType nativeType) { }
    private final List<Draw> draws=new ArrayList<>();
    private ByteBuffer frame;
    private ByteBufferBuilder scratch;
    private GpuBuffer vertices;
    private final GpuBuffer[] settings=new GpuBuffer[4];
    private BufferBuilder builder;
    private MaterialVertexTee tee;
    private PreparedRenderType current;
    private int currentStyle,attempts,skipped,failures,bytes,maxIndices,models,uploadedBytes;
    private long captureNanos,start;
    private boolean enabled=true,unsafeOverlay;

    void begin(){draws.clear();attempts=skipped=failures=bytes=maxIndices=models=uploadedBytes=0;captureNanos=0;unsafeOverlay=false;if(frame!=null)frame.clear();}
    static int style(RenderPipeline pipeline) {
        if(pipeline==RenderPipelines.ENTITY_SOLID)return 0;
        if(pipeline==RenderPipelines.ENTITY_CUTOUT || pipeline==RenderPipelines.ENTITY_CUTOUT_CULL)return 1;
        if(pipeline==RenderPipelines.ARMOR_CUTOUT_NO_CULL)return 2;
        if(pipeline==RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL)return 3;
        return -1;
    }
    VertexConsumer consumer(ModelFeatureRenderer.Submit<?> submit,VertexConsumer nativeConsumer) {
        if(!enabled)return nativeConsumer;
        int style=style(submit.renderType().pipeline());
        if(style<0 || submit.sheetedDecalPose()!=null || submit.renderType().outputTarget()!=OutputTarget.MAIN_TARGET
                || submit.renderType().format()!=DefaultVertexFormat.ENTITY || submit.renderType().primitiveTopology()!=PrimitiveTopology.QUADS){skipped++;return nativeConsumer;}
        if(++attempts>DynamicCasterSelection.MAX_MODELS){skipped++;if(style==3)unsafeOverlay=true;return nativeConsumer;}
        start=System.nanoTime();
        try {
            var prepared=submit.renderType().prepare();
            if(prepared.textures().stream().noneMatch(t->t.name().equals("Sampler0"))
                    || (style<2 && prepared.textures().stream().noneMatch(t->t.name().equals("Sampler1")))){skipped++;if(style==3)unsafeOverlay=true;return nativeConsumer;}
            allocate();
            scratch.clear();current=prepared;currentStyle=style;
            builder=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            tee=new MaterialVertexTee(nativeConsumer,submit.sprite()==null?builder:submit.sprite().wrap(builder));
            return tee;
        } catch(RuntimeException error){failures++;if(style==3)unsafeOverlay=true;clearCapture();return nativeConsumer;}
    }
    void finish(VertexConsumer consumer,boolean success) {
        if(consumer!=tee || tee==null)return;
        try {
            if(!success || tee.failed()){skipped++;if(currentStyle==3)unsafeOverlay=true;return;}
            try(var mesh=builder.build()) {
                if(mesh==null)return;
                if(!append(mesh,currentStyle,current) && currentStyle==3)unsafeOverlay=true;
            }
        } catch(RuntimeException error){failures++;if(currentStyle==3)unsafeOverlay=true;}
        finally{captureNanos+=System.nanoTime()-start;clearCapture();}
    }
    private void allocate(){if(frame==null){frame=MemoryUtil.memAlloc(DynamicCasterSelection.FRAME_BYTES);scratch=new ByteBufferBuilder(4096,DynamicCasterSelection.MODEL_BYTES);}}
    boolean append(MeshData mesh,int style,PreparedRenderType type) {
        var data=mesh.vertexBuffer().duplicate();
        if(draws.size()>=DynamicCasterSelection.MAX_MODELS || mesh.drawState().vertexCount()%4!=0 || data.remaining()>DynamicCasterSelection.FRAME_BYTES)return reject(style);
        allocate();
        if(data.remaining()>frame.remaining())return reject(style);
        int base=frame.position()/DefaultVertexFormat.ENTITY.getVertexSize();frame.put(data);
        draws.add(new Draw(base,mesh.drawState().indexCount(),style,type));models=draws.size();
        bytes=frame.position();maxIndices=Math.max(maxIndices,mesh.drawState().indexCount());return true;
    }
    private boolean reject(int style){skipped++;if(style==3)unsafeOverlay=true;return false;}
    int modelCount(){return models;}
    void endFrame(){draws.clear();clearCapture();}
    int bytes(){return bytes;}
    private void clearCapture(){tee=null;builder=null;current=null;if(scratch!=null)scratch.clear();}
    boolean hasModels(){return !unsafeOverlay && !draws.isEmpty();}
    void render(CommandEncoder encoder,MaterialCapture material,int width,int height) {
        var device=RenderSystem.getDevice();
        if(!device.precompilePipeline(CULL,RenderProbe.SHADERS).isValid() || !device.precompilePipeline(NO_CULL,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Entity material shader compilation failed");
        if(vertices==null)vertices=device.createBuffer(()->"VoxelLight entity material vertices",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,DynamicCasterSelection.FRAME_BYTES);
        encoder.writeToBuffer(vertices.slice(0,bytes),frame.duplicate().flip());uploadedBytes=bytes;
        for(int i=0;i<settings.length;i++) {
            if(settings[i]==null)settings[i]=device.createBuffer(()->"VoxelLight entity material settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
            try(var stack=MemoryStack.stackPush()){encoder.writeToBuffer(settings[i].slice(),Std140Builder.onStack(stack,16).putVec4(i==0?0:.1f,i==0?MaterialEncoding.ENTITY:MaterialEncoding.ENTITY|MaterialEncoding.CUTOUT,i>=2?0:1,i==3?1:0).get());}
        }
        var sequence=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);var indices=sequence.getBuffer(maxIndices);
        var views=new com.mojang.blaze3d.textures.GpuTextureView[]{material.view(0),material.view(1),material.view(2),material.view(3)};
        try(var pass=encoder.createRenderPass(MaterialCapture.captureDescriptor(views,width,height))) {
            pass.setVertexBuffer(0,vertices.slice());pass.setIndexBuffer(indices,sequence.type());
            // Opaque decals already drew natively; clear their material coverage after base surfaces.
            for(var draw:draws.stream().sorted(Comparator.comparingInt(d->d.style()==3?1:0)).toList()) {
                var type=draw.nativeType();
                pass.setPipeline(type.pipeline().isCull()?CULL:NO_CULL);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms",type.dynamicTransforms());pass.setUniform("EntityMaterialSettings",settings[draw.style()]);
                var texture=type.textures().stream().filter(t->t.name().equals("Sampler0")).findFirst().orElseThrow();
                var overlay=type.textures().stream().filter(t->t.name().equals("Sampler1")).findFirst().orElse(texture);
                pass.bindTexture("Sampler0",texture.textureView(),texture.sampler());pass.bindTexture("Sampler1",overlay.textureView(),overlay.sampler());
                if(type.scissorState().enabled())pass.enableScissor(type.scissorState().x(),type.scissorState().y(),type.scissorState().width(),type.scissorState().height());else pass.disableScissor();
                pass.drawIndexed(draw.indices(),1,0,draw.base(),0);
            }
        }
    }
    void setEnabled(boolean value){enabled=value;begin();}
    String status(){return ", entityMaterials="+(enabled?"on":"off")+", entityMaterialModels="+models+", entityMaterialSkipped="+skipped
            +", entityMaterialFailures="+failures+", entityMaterialPackedBytes="+bytes+", entityMaterialUploadBytes="+uploadedBytes+", entityMaterialCaptureNs="+captureNanos
            +", entityMaterialOverlayFallback="+unsafeOverlay+", entityMaterialBufferBytes="+(vertices==null?0:DynamicCasterSelection.FRAME_BYTES);}
    @Override public void close(){begin();clearCapture();if(vertices!=null){vertices.close();vertices=null;}for(int i=0;i<settings.length;i++)if(settings[i]!=null){settings[i].close();settings[i]=null;}if(scratch!=null){scratch.close();scratch=null;}if(frame!=null){MemoryUtil.memFree(frame);frame=null;}}
    private static RenderPipeline pipeline(boolean cull) {
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/entity_material_"+(cull?"cull":"no_cull")))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","entity_material")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","entity_material"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER)
                        .withUniform("EntityMaterialSettings",UniformType.UNIFORM_BUFFER).withSampler("Sampler0").withSampler("Sampler1").build())
                .withVertexBinding(0,DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).withCull(cull);
        builder.withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(2,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));return builder.build();
    }
}
