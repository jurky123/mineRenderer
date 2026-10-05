package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.voxellight.rt.vulkan.VulkanRtContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.util.Optional;

/** Explicit POC view, independently selected from production lighting. Failures retain raster. */
final class VulkanRtDebugPass implements AutoCloseable {
    private static final RenderPipeline DISPLAY=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/vulkan_rt_debug"))
        .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","vulkan_rt_debug"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("RtNormal").build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ColorTargetState.DEFAULT).withCull(false).build();
    private final RtTerrainWarmup warmup=new RtTerrainWarmup();
    private VulkanRtContext context;
    private GpuTexture texture;
    private GpuTextureView view;
    private boolean enabled,failed;
    private long world=-1,resources=-1,startupMs;
    private String state="off";
    void enable(boolean value) {
        close();enabled=value;failed=false;RtGeometryStream.enable(value);state=value?"waiting for Vulkan RT":"off";
        recompile();
    }
    private static void recompile() {var mc=Minecraft.getInstance();if(mc.level!=null)mc.levelRenderer.invalidateCompiledGeometry(mc.level,mc.options,mc.gameRenderer.mainCamera(),mc.getBlockColors());}
    boolean enabled() {return enabled;}
    void render(CommandEncoder encoder,RenderTarget target,Matrix4f projection,boolean observed) {
        if(!enabled||failed||!observed)return;
        if(!(((GpuBackendAccess)RenderSystem.getDevice()).voxellight$backend() instanceof VulkanDevice device)) {state="Vulkan unavailable; raster retained";return;}
        try {
            var stats=com.voxellight.VoxelLightClient.scene().bridge().stats();
            if(context!=null&&(world!=stats.worldGeneration()||resources!=stats.resourceGeneration())) {close();RtGeometryStream.enable(true);recompile();}
            if(context==null) {
                if(!RtGeometryStream.enabled()){RtGeometryStream.enable(true);recompile();}
                long start=System.nanoTime();context=new VulkanRtContext(device);
                if(!RenderSystem.getDevice().precompilePipeline(DISPLAY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan RT debug display pipeline unavailable");
                startupMs=(System.nanoTime()-start)/1_000_000;
                world=stats.worldGeneration();resources=stats.resourceGeneration();
                org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT normal POC pipeline ready in {} ms; no OptiX tracing",startupMs);
            }
            int scale=Math.max(4,Math.max((target.width+639)/640,(target.height+359)/360));
            int width=Math.max(1,(target.width+scale-1)/scale),height=Math.max(1,(target.height+scale-1)/scale);
            if(texture==null||texture.getWidth(0)!=width||texture.getHeight(0)!=height) {
                releaseTexture();texture=device.createTexture("VoxelLight Vulkan RT normal",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,width,height,1,1);view=device.createTextureView(texture);
            }
            var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;var pos=camera.pos;
            warmup.prepare(context.scene.resident(),pos.x(),pos.y(),pos.z());
            var inverse=new Matrix4f(projection).mul(camera.viewRotationMatrix).invert();
            if(!context.render(encoder,RtGeometryStream.drain(16),inverse,pos.x(),pos.y(),pos.z(),texture,width,height)) {state="waiting for terrain BLAS";return;}
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_debug_composite");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan RT normal bringup").withColorAttachment(target.getColorTextureView(),Optional.empty()))) {
                pass.setPipeline(DISPLAY);pass.bindTexture("RtNormal",view,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
            }
            state="normal/debug only";
        } catch(RuntimeException error) {
            close();failed=true;state="failed; raster retained";org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Vulkan RT POC failed; legacy/raster remain available",error);
        }
    }
    String status() {return ", vulkanRtPoc="+enabled+", vulkanRtState="+state+", vulkanRtPipelineStartupMs="+startupMs+(context==null?"":", "+context.status());}
    private void releaseTexture() {if(view!=null)view.close();if(texture!=null)texture.close();view=null;texture=null;}
    @Override public void close() {if(context!=null)context.close();context=null;releaseTexture();warmup.close();}
}
