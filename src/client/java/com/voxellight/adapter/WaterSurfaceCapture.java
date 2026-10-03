package com.voxellight.adapter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.*;
/** Borrowed visible translucent geometry supplies a water-only receiver mask, not a sea-level heuristic. */
final class WaterSurfaceCapture implements AutoCloseable  {
    static final RenderPipeline WATER_MASK=pipeline();
    private GpuTexture mask,depth,neutral;
    private GpuTextureView maskView,depthView,neutralView;
    private GpuBuffer settings;
    private int width,height;
    private boolean active;
    void render(CommandEncoder encoder,RenderTarget output,MaterialCapture material,boolean enabled) {
        active=false;
        var device=RenderSystem.getDevice();
        if(neutral==null) {
            neutral=device.createTexture("VoxelLight no water surface",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.R32_FLOAT,1,1,1,1);
            neutralView=device.createTextureView(neutral);
            try(var pass=encoder.createRenderPass(descriptor(neutralView,null,1,1))) {
            }
        }
        var terrain=material.nativeSubmissions();
        int w=(output.width+1)/2,h=(output.height+1)/2;
        if(!enabled||terrain==null||(long)w*h*8>24L*1024*1024) {
            release();
            return;
        }
        if(!device.precompilePipeline(WATER_MASK,RenderProbe.SHADERS).isValid())return;
        if(mask==null||width!=w||height!=h) {
            release();
            width=w;
            height=h;
            mask=device.createTexture("VoxelLight water surface depth mask",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.R32_FLOAT,w,h,1,1);
            depth=device.createTexture("VoxelLight private water mask Z",GpuTexture.USAGE_RENDER_ATTACHMENT,GpuFormat.D32_FLOAT,w,h,1,1);
            maskView=device.createTextureView(mask);
            depthView=device.createTextureView(depth);
            settings=device.createBuffer(()->"VoxelLight water sprite admission",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,48);
        }
        var atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS);
        var still=atlas.getSprite(Identifier.withDefaultNamespace("block/water_still"));
        var flow=atlas.getSprite(Identifier.withDefaultNamespace("block/water_flow"));
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,48).putVec4(still.getU0(),still.getV0(),still.getU1(),still.getV1()).putVec4(flow.getU0(),flow.getV0(),flow.getU1(),flow.getV1()).putVec4(w,h,0,0).get());
        }
        var sequence=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var indices=terrain.maxIndicesRequired()==0?null:sequence.getBuffer(terrain.maxIndicesRequired());
        try(var profile=RenderPassProfile.begin(encoder,"water_surface_mask");var pass=encoder.createRenderPass(descriptor(maskView,depthView,w,h))) {
            pass.setPipeline(WATER_MASK);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("WaterMaskSettings",settings);
            pass.bindTexture("SceneDepth",output.getDepthTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            for(var layer:ChunkSectionLayerGroup.TRANSLUCENT.layers()) {
                var groups=terrain.drawGroupsPerLayer().get(layer);
                if(groups==null)continue;
                for(var list:groups.values())if(!list.isEmpty())pass.drawMultipleIndexed(list,indices,sequence.type(),List.of("ChunkSection"),terrain.chunkSectionInfos());
            }
        }
        active=true;
    }
    void bind(RenderPass pass) {
        pass.bindTexture("WaterSurfaceDepth",active?maskView:neutralView,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    String status() {
        return ", waterMaskBytes="+(mask==null?0:(long)width*height*8);
    }
    static RenderPassDescriptor descriptor(GpuTextureView color,GpuTextureView depth,int w,int h) {
        var d=RenderPassDescriptor.create(()->"VoxelLight water receiver admission").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(color,Optional.of(new Vector4f(0)));
        return depth==null?d:d.withDepthAttachment(depth,OptionalDouble.of(0));
    }
    private void release() {
        if(maskView!=null) {
            maskView.close();
            maskView=null;
        }
        if(depthView!=null) {
            depthView.close();
            depthView=null;
        }
        if(mask!=null) {
            mask.close();
            mask=null;
        }
        if(depth!=null) {
            depth.close();
            depth=null;
        }
        if(settings!=null) {
            settings.close();
            settings=null;
        }
        active=false;
        width=height=0;
    }
    @Override public void close() {
        release();
        if(neutralView!=null) {
            neutralView.close();
            neutralView=null;
        }
        if(neutral!=null) {
            neutral.close();
            neutral=null;
        }
    }
    private static RenderPipeline pipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/water_mask")).withVertexShader(Identifier.fromNamespaceAndPath("voxellight","native_material_capture")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","water_mask")).withBindGroupLayout(BindGroupLayout.builder().withUniform("Globals",UniformType.UNIFORM_BUFFER).withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ChunkSection",UniformType.UNIFORM_BUFFER).withUniform("WaterMaskSettings",UniformType.UNIFORM_BUFFER).withSampler("SceneDepth").build()).withVertexBinding(0,NativeTerrainAttributes.FORMAT).withPrimitiveTopology(PrimitiveTopology.QUADS).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.R32_FLOAT,ColorTargetState.WRITE_ALL)).withCull(false).build();
    }
}
