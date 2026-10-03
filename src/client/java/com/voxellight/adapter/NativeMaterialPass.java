package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.NativeMaterialShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.util.*;

/** Replaces only the opaque terrain submission; all native geometry/UBO ownership stays native. */
final class NativeMaterialPass {
    static final RenderPipeline SOLID=pipeline(false),CUTOUT=pipeline(true);
    static RenderPipeline pipeline(boolean cutout){
        var b=RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("voxellight",cutout?"pipeline/native_mrt_cutout":"pipeline/native_mrt_solid"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","native_mrt"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","native_mrt"))
                .withVertexBinding(0,NativeTerrainAttributes.FORMAT).withShaderDefine("NATIVE_TERRAIN")
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("PbrIdsAtlas").withSampler("PbrNormalAtlas").build());
        if(cutout)b.withShaderDefine("ALPHA_CUTOUT",.1f);
        for(int i=0;i<5;i++)b.withColorTargetState(i,new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL));
        return b.build();
    }
    static String source(Identifier id,ShaderType type){
        return NativeMaterialShaders.wrap(Minecraft.getInstance().getShaderManager().getShader(Identifier.withDefaultNamespace("core/terrain"),type),type==ShaderType.VERTEX,
                RenderProbe.SHADERS.get(Identifier.fromNamespaceAndPath("voxellight",type==ShaderType.VERTEX?"native_material_capture":"material_capture"),type));
    }
    static boolean available(){var d=RenderSystem.getDevice();return d.precompilePipeline(SOLID,NativeMaterialPass::source).isValid()&&d.precompilePipeline(CUTOUT,NativeMaterialPass::source).isValid();}
    static RenderPassDescriptor descriptor(GpuTextureView color,GpuTextureView depth,GpuTextureView[] views,int width,int height){
        var d=RenderPassDescriptor.create(()->"VoxelLight native color/material MRT").withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(color,Optional.empty());
        for(int i:new int[]{0,1,2,4})d.withColorAttachment(views[i],Optional.of(new Vector4f(0)));
        return d.withDepthAttachment(depth,OptionalDouble.empty());
    }
    static int render(ChunkSectionsToRender terrain,RenderTarget output,GpuSampler sampler,MaterialCapture material){
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        var sequence=RenderSystem.getSequentialBuffer(com.mojang.blaze3d.PrimitiveTopology.QUADS);
        var indices=terrain.maxIndicesRequired()==0?null:sequence.getBuffer(terrain.maxIndicesRequired());int draws=0;
        try(var profile=RenderPassProfile.begin(encoder,"native_material_single");var pass=encoder.createRenderPass(descriptor(output.getColorTextureView(),output.getDepthTextureView(),new GpuTextureView[]{material.view(0),material.view(1),material.view(2),material.view(3),material.view(4)},output.width,output.height))){
            RenderSystem.bindDefaultUniforms(pass);pass.bindTexture("Sampler0",terrain.textureView(),sampler);
            pass.bindTexture("Sampler2",Minecraft.getInstance().gameRenderer.lightmap(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));material.bindPbr(pass);
            for(var layer:ChunkSectionLayerGroup.OPAQUE.layers()){
                pass.setPipeline(layer==ChunkSectionLayer.SOLID?SOLID:CUTOUT);
                var groups=terrain.drawGroupsPerLayer().get(layer);if(groups==null)continue;
                for(var list:groups.values())if(!list.isEmpty()){pass.drawMultipleIndexed(list,indices,sequence.type(),List.of("ChunkSection"),terrain.chunkSectionInfos());draws+=list.size();}
            }
        }
        try(var profile=RenderPassProfile.begin(encoder,"material_depth_copy")){encoder.copyTextureToTexture(output.getDepthTexture(),material.depthTexture(),0,0,0,0,0,output.width,output.height);}
        return draws;
    }
}
