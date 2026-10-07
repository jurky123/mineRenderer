package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.voxellight.rt.vulkan.VulkanRtBuffer;
import com.voxellight.world.LightingEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Shared sky and exact solid-angle proposals; all radiance/CDF pixels stay on GPU. */
final class VulkanRtEnvironmentAssets implements AutoCloseable {
    static final int WIDTH=256,HEIGHT=128,BYTES=(WIDTH*HEIGHT*2+HEIGHT)*16;
    static final RenderPipeline MAP=pipeline("rt_environment",BindGroupLayout.builder()
        .withUniform("EnvironmentSettings",UniformType.UNIFORM_BUFFER).withUniform("RtEnvironmentSettings",UniformType.UNIFORM_BUFFER).build());
    static final RenderPipeline CELLS=pipeline("vulkan_rt_environment_cells",BindGroupLayout.builder().withSampler("EnvironmentMap").build());
    static final RenderPipeline ROWS=pipeline("vulkan_rt_environment_rows",BindGroupLayout.builder().withSampler("EnvironmentCells").build());
    private GpuTexture map,cells,rows;
    private GpuTextureView mapView,cellsView,rowsView;
    private GpuBuffer palette;
    private static RenderPipeline pipeline(String shader,BindGroupLayout layout) {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/vulkan_"+shader))
            .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",shader))
            .withBindGroupLayout(layout).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).withCull(false).build();
    }
    void prepare(CommandEncoder encoder,VulkanDevice device,VulkanRtBuffer destination,int offset,EnvironmentPass weather,ShadowRenderer shadows) {
        if(map==null) {
            for(var pipeline:new RenderPipeline[]{MAP,CELLS,ROWS})if(!RenderSystem.getDevice().precompilePipeline(pipeline,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan environment pipeline unavailable");
            int usage=GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_TEXTURE_BINDING;
            map=device.createTexture("VoxelLight Vulkan shared HDR sky",usage,GpuFormat.RGBA32_FLOAT,WIDTH,HEIGHT,1,1);mapView=device.createTextureView(map);
            cells=device.createTexture("VoxelLight Vulkan environment cell CDF",usage,GpuFormat.RGBA32_FLOAT,WIDTH,HEIGHT,1,1);cellsView=device.createTextureView(cells);
            rows=device.createTexture("VoxelLight Vulkan environment row CDF",usage,GpuFormat.RGBA32_FLOAT,HEIGHT,1,1,1);rowsView=device.createTextureView(rows);
            palette=device.createBuffer(()->"VoxelLight Vulkan environment palette",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,64);
        }
        var sky=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        var light=shadows.light();var env=LightingEnvironment.polished(light,sky.skybox==DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);var sun=light.direction();
        var frozen=RtBenchmarkRunner.freeze("skyPalette",new float[]{env.directR(),env.directG(),env.directB(),env.directStrength(),
            env.skyR(),env.skyG(),env.skyB(),env.skyStrength(),env.horizonR(),env.horizonG(),env.horizonB(),env.lowerHemisphere(),sun.x,sun.y,sun.z,0});
        try(var stack=MemoryStack.stackPush()) {
            var data=stack.malloc(64).order(java.nio.ByteOrder.nativeOrder());for(float value:frozen)data.putFloat(value);data.flip();encoder.writeToBuffer(palette.slice(),data);
        }
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_environment_map");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan shared environment")
                .withRenderArea(new RenderPass.RenderArea(0,0,WIDTH,HEIGHT)).withColorAttachment(mapView,Optional.empty()))) {
            pass.setPipeline(MAP);pass.setUniform("EnvironmentSettings",weather.settings());pass.setUniform("RtEnvironmentSettings",palette);pass.draw(3,1,0,0);
        }
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_environment_distribution")) {
            draw(encoder,CELLS,cellsView,mapView,"EnvironmentMap",WIDTH,HEIGHT);
            draw(encoder,ROWS,rowsView,cellsView,"EnvironmentCells",HEIGHT,1);
            encoder.copyTextureToBuffer(map,destination,offset,()->{},0);
            encoder.copyTextureToBuffer(cells,destination,offset+WIDTH*HEIGHT*16,()->{},0);
            encoder.copyTextureToBuffer(rows,destination,offset+WIDTH*HEIGHT*32,()->{},0);
        }
    }
    private static void draw(CommandEncoder encoder,RenderPipeline pipeline,GpuTextureView target,GpuTextureView input,String sampler,int width,int height) {
        try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan environment CDF")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height)).withColorAttachment(target,Optional.empty()))) {
            pass.setPipeline(pipeline);pass.bindTexture(sampler,input,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
        }
    }
    @Override public void close() {
        if(mapView!=null)mapView.close();if(cellsView!=null)cellsView.close();if(rowsView!=null)rowsView.close();
        if(map!=null)map.close();if(cells!=null)cells.close();if(rows!=null)rows.close();if(palette!=null)palette.close();
        map=cells=rows=null;mapView=cellsView=rowsView=null;palette=null;
    }
}
