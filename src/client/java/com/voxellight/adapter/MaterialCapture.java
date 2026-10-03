package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.VoxelLightClient;
import com.voxellight.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.util.*;

/** Material MRT targets. Default borrows visible native geometry; local meshes remain a comparison path. */
final class MaterialCapture implements AutoCloseable {
    static final int SETTINGS_BYTES = 16;
    static final RenderPipeline CAPTURE = capturePipeline();
    static final RenderPipeline DISPLAY = displayPipeline();
    static final RenderPipeline NATIVE_CAPTURE = capturePipeline(true);
    private boolean nativeTerrain=true;
    private net.minecraft.client.renderer.chunk.ChunkSectionsToRender nativeSubmissions;
    void setNativeTerrain(boolean value){nativeTerrain=value;surfaces.close();nativeSubmissions=null;}
    boolean nativeTerrain(){return nativeTerrain;}
    void setNativeSubmissions(net.minecraft.client.renderer.chunk.ChunkSectionsToRender value){nativeSubmissions=value;}
    private final MaterialSurfaceStore surfaces = new MaterialSurfaceStore();
    private final GpuTexture[] targets = new GpuTexture[4];
    private final GpuTextureView[] views = new GpuTextureView[4];
    private GpuBuffer settings;
    private long world, resources;
    private int width, height, draws;
    private String state = "waiting";

    boolean prepare(RenderTarget target) {
        var minecraft = Minecraft.getInstance(); var scene = VoxelLightClient.scene();
        if (!scene.isEnabled() || minecraft.level == null) { close(); state = "scene/world unavailable"; return false; }
        long targetBytes = MaterialEncoding.targetBytes(target.width, target.height);
        if (targetBytes > MaterialEncoding.TARGET_LIMIT) { close(); state = "material target budget exceeded; vanilla retained"; return false; }
        var device = RenderSystem.getDevice();
        if (!device.precompilePipeline(nativeTerrain?NATIVE_CAPTURE:CAPTURE, RenderProbe.SHADERS).isValid() || !device.precompilePipeline(DISPLAY, RenderProbe.SHADERS).isValid())
            throw new IllegalStateException("Material shader compilation failed");
        var bridge = scene.bridge(); var stats = bridge.stats();
        if (world != stats.worldGeneration() || resources != stats.resourceGeneration()) close();
        world = stats.worldGeneration(); resources = stats.resourceGeneration();
        var camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        var center = SectionKey.fromBlock((int)Math.floor(camera.x()), (int)Math.floor(camera.y()), (int)Math.floor(camera.z()));
        if(!nativeTerrain)surfaces.prepare(minecraft, bridge, center);
        draws = 0;
        if (targets[0] == null || width != target.width || height != target.height) allocate(target.width, target.height);
        state = nativeTerrain?"native visible terrain; borrowed geometry":"local reference material capture";
        return true;
    }
    private void allocate(int w,int h) {
        releaseTargets(); width=w; height=h;
        var formats = new GpuFormat[]{GpuFormat.RGBA8_UNORM,GpuFormat.RGBA16_FLOAT,GpuFormat.RGBA16_FLOAT,GpuFormat.D32_FLOAT};
        for (int i=0;i<4;i++) {
            targets[i]=RenderSystem.getDevice().createTexture("VoxelLight material target "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,formats[i],w,h,1,1);
            views[i]=RenderSystem.getDevice().createTextureView(targets[i]);
        }
        settings=RenderSystem.getDevice().createBuffer(() -> "VoxelLight material diagnostic settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,SETTINGS_BYTES);
    }
    void render(CommandEncoder encoder,RenderTarget output,GpuTextureView sceneColor,RenderProbe.Mode mode,com.mojang.blaze3d.textures.GpuSampler terrainSampler) {
        capture(encoder,terrainSampler);
        display(encoder,output,sceneColor,mode);
    }
    void display(CommandEncoder encoder,RenderTarget output,GpuTextureView sceneColor,RenderProbe.Mode mode) {
        display(encoder,output,sceneColor,mode,false);
    }
    void display(CommandEncoder encoder,RenderTarget output,GpuTextureView sceneColor,RenderProbe.Mode mode,boolean entities) {
        int diagnostic=switch(mode){case ALBEDO->0;case SURFACE_NORMAL->1;case EMISSION->2;case MATERIAL_FLAGS->3;case MATERIAL_COVERAGE->4;default->throw new IllegalArgumentException("Not a material mode");};
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,SETTINGS_BYTES).putVec4(diagnostic,entities?1:0,0,0).get());
        }
        try(var pass=encoder.createRenderPass(()->"VoxelLight material diagnostic display",output.getColorTextureView(),Optional.empty())) {
            pass.setPipeline(DISPLAY);
            pass.bindTexture("SceneColor",sceneColor,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            for(int i=0;i<4;i++)pass.bindTexture(new String[]{"MaterialAlbedo","MaterialNormal","MaterialEmission","MaterialDepth"}[i],views[i],RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("MaterialSettings",settings);pass.draw(3,1,0,0);
        }
    }
    void capture(CommandEncoder encoder,GpuSampler terrainSampler) {
        if(nativeTerrain){captureNative(encoder,terrainSampler);return;}
        var minecraft=Minecraft.getInstance();
        var camera=minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var atlas=minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var infos=new ArrayList<DynamicUniforms.ChunkSectionInfo>();
        var submissions=new ArrayList<RenderPass.Draw<GpuBufferSlice[]>>();
        int max=0;
        for (var entry:surfaces.entries()) {
            var mesh=entry.getValue(); if(mesh.vertices()==null)continue;
            int index=infos.size(); var key=entry.getKey();
            infos.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(camera.viewRotationMatrix),key.x()*16,key.y()*16,key.z()*16,1,atlas.getWidth(0),atlas.getHeight(0)));
            submissions.add(new RenderPass.Draw<>(0,mesh.vertices(),null,null,0,mesh.indices(),0,(ubos,uploader)->uploader.upload("ChunkSection",ubos[index])));
            max=Math.max(max,mesh.indices());
        }
        var ubos=RenderSystem.getDynamicUniforms().writeChunkSections(infos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        var sequence=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS); var indices=max==0?null:sequence.getBuffer(max);
        var descriptor=captureDescriptor(views,width,height);
        try(var pass=encoder.createRenderPass(descriptor)) {
            pass.setPipeline(CAPTURE);RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("Sampler0",atlas,terrainSampler);
            if(!submissions.isEmpty())pass.drawMultipleIndexed(submissions,indices,sequence.type(),List.of("ChunkSection"),ubos);
        }
        draws=submissions.size();
    }

    private void captureNative(CommandEncoder encoder,GpuSampler sampler) {
        var terrain=nativeSubmissions;
        var sequence=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var indices=terrain==null || terrain.maxIndicesRequired()==0?null:sequence.getBuffer(terrain.maxIndicesRequired());
        draws=0;
        try(var pass=encoder.createRenderPass(captureDescriptor(views,width,height))){
            pass.setPipeline(NATIVE_CAPTURE);RenderSystem.bindDefaultUniforms(pass);
            if(terrain==null)return;
            pass.bindTexture("Sampler0",terrain.textureView(),sampler);
            for(var layer:net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.OPAQUE.layers()){
                var groups=terrain.drawGroupsPerLayer().get(layer);if(groups==null)continue;
                for(var list:groups.values())if(!list.isEmpty()){
                    pass.drawMultipleIndexed(list,indices,sequence.type(),List.of("ChunkSection"),terrain.chunkSectionInfos());
                    draws+=list.size();
                }
            }
        }
    }

    GpuTextureView view(int index) { return views[index]; }

    static RenderPassDescriptor captureDescriptor(GpuTextureView[] views,int width,int height) {
        // Explicit MRT descriptors do not inherit the convenience overload's full-texture area.
        var descriptor=RenderPassDescriptor.create(()->"VoxelLight material MRT capture")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height));
        for(int i=0;i<3;i++)descriptor.withColorAttachment(views[i],Optional.of(new Vector4f(0,0,0,0)));
        return descriptor.withDepthAttachment(views[3],OptionalDouble.of(0));
    }
    String status() {
        return "material=" + state + ", materialSource="+(nativeTerrain?"native":"local reference") + (nativeTerrain?", materialDuplicateGeometryBytes=0, materialNativeExtraBytesPerVertex=8, indigoMaterialEmissionsTotal="+IndigoMaterials.emissions():surfaces.status())
                + ", materialTargetBytes=" + (targets[0] == null ? 0 : MaterialEncoding.targetBytes(width, height)) + ", materialDraws=" + draws;
    }
    private void releaseTargets() {
        for(int i=0;i<4;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(targets[i]!=null){targets[i].close();targets[i]=null;}}
        if(settings!=null){settings.close();settings=null;} width=height=0;
    }
    @Override public void close(){surfaces.close();releaseTargets();world=resources=0;draws=0;state="waiting";}
    private static RenderPipeline capturePipeline() {return capturePipeline(false);}
    private static RenderPipeline capturePipeline(boolean nativeGeometry) {
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",nativeGeometry?"pipeline/native_material_capture":"pipeline/material_capture"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight",nativeGeometry?"native_material_capture":"material_capture")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","material_capture"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Globals",UniformType.UNIFORM_BUFFER).withUniform("Projection",UniformType.UNIFORM_BUFFER)
                        .withUniform("ChunkSection",UniformType.UNIFORM_BUFFER).withSampler("Sampler0").build())
                .withVertexBinding(0,nativeGeometry?NativeTerrainAttributes.FORMAT:DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).withCull(true);
        if(nativeGeometry)builder.withShaderDefine("NATIVE_TERRAIN");
        builder.withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(2,new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL));
        return builder.build();
    }
    private static RenderPipeline displayPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/material_display"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","material_display"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneColor").withSampler("SceneDepth").withSampler("MaterialAlbedo").withSampler("MaterialNormal")
                        .withSampler("MaterialEmission").withSampler("MaterialDepth").withUniform("MaterialSettings",UniformType.UNIFORM_BUFFER).build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ColorTargetState.DEFAULT).withCull(false).build();
    }
}
