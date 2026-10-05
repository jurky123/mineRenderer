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
    private static final RenderPipeline MATERIAL_DISPLAY=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/vulkan_rt_material_display"))
        .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","vulkan_rt_material_display"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("RtNormal").build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ColorTargetState.DEFAULT).withCull(false).build();
    private final com.voxellight.rt.StationaryAccumulation history=new com.voxellight.rt.StationaryAccumulation();
    private final com.voxellight.rt.RtLightingChange lightingChange=new com.voxellight.rt.RtLightingChange();
    void accumulateFreeze(boolean value){history.frozen(value);}
    private final VulkanRtAccumulation accumulation=new VulkanRtAccumulation();
    void accumulate(boolean value){history.enabled(value);}
    void accumulateSpp(int value){history.target(value);}
    void accumulateReset(){history.reset("manual");}
    private final RtTerrainWarmup warmup=new RtTerrainWarmup();
    private VulkanRtContext context;
    private GpuTexture texture;
    private GpuTextureView view;
    private boolean enabled,failed,transport,materials;
    private final VulkanRtMaterialAssets assets=new VulkanRtMaterialAssets();
    private int diagnosticFrames;
    void probeLighting(){diagnosticFrames=0;history.reset("lighting probe");}
    private volatile String diagnostic="pending";
    private long world=-1,resources=-1,startupMs;
    private String state="off";
    void enable(boolean value) {
        close();transport=false;materials=false;enabled=value;failed=false;RtGeometryStream.enable(value);state=value?"waiting for Vulkan RT":"off";
    }
    void enableTransport() { enable(true);transport=true; }
    void enableMaterials(){enableTransport();materials=true;}
    boolean enabled() {return enabled;}
    void render(CommandEncoder encoder,RenderTarget target,Matrix4f projection,boolean observed,MaterialCapture material,EnvironmentPass weather,ShadowRenderer shadows) {
        if(!enabled||failed)return;
        if(!(((GpuBackendAccess)RenderSystem.getDevice()).voxellight$backend() instanceof VulkanDevice device)) {state="Vulkan unavailable; raster retained";return;}
        if(!observed){state="waiting for camera projection";return;}
        if(materials&&weather.settings()==null){state="waiting for shared environment settings";return;}
        try {
            var stats=com.voxellight.VoxelLightClient.scene().bridge().stats();
            if(context!=null&&(world!=stats.worldGeneration()||resources!=stats.resourceGeneration())) {close();RtGeometryStream.enable(true);}
            if(context==null) {
                if(!RtGeometryStream.enabled()){RtGeometryStream.enable(true);}
                long start=System.nanoTime();context=new VulkanRtContext(device,transport,materials);
                if(!RenderSystem.getDevice().precompilePipeline(materials?MATERIAL_DISPLAY:DISPLAY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan RT debug display pipeline unavailable");
                startupMs=(System.nanoTime()-start)/1_000_000;
                world=stats.worldGeneration();resources=stats.resourceGeneration();
                org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT {} pipeline ready in {} ms; no OptiX tracing",materials?"material transport":transport?"geometry transport test":"normal POC",startupMs);
            }
            int scale=Math.max(4,Math.max((target.width+639)/640,(target.height+359)/360));
            int width=Math.max(1,(target.width+scale-1)/scale),height=Math.max(1,(target.height+scale-1)/scale);
            if(texture==null||texture.getWidth(0)!=width||texture.getHeight(0)!=height) {
                releaseTexture();texture=device.createTexture("VoxelLight Vulkan RT normal",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,width,height,1,1);view=device.createTextureView(texture);
            }
            var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;var pos=camera.pos;
            warmup.prepare(context.scene.resident(),pos.x(),pos.y(),pos.z());
            var inverse=new Matrix4f(projection).mul(camera.viewRotationMatrix).invert();
            context.prepareScene(encoder,RtGeometryStream.drain(16),pos.x(),pos.y(),pos.z());
            double[] pose=new double[19];pose[0]=pos.x();pose[1]=pos.y();pose[2]=pos.z();
            float[] matrix=new float[16];inverse.get(matrix);for(int i=0;i<16;i++)pose[i+3]=matrix[i];
            if(materials&&!history.frozen()&&lightingChange.changed(assets.lightingSignature(shadows)))history.reset("lighting");
            history.begin(pose,context.scene.generation()+com.voxellight.rt.RtInvalidationQueue.generation(),width,height);
            boolean useHistory=transport&&history.enabled();
            GpuTextureView displayed=useHistory&&history.samples()>0?accumulation.view():view;
            if(!useHistory||history.needsSample()){
                com.voxellight.rt.vulkan.VulkanRtBuffer materialAssets=null;
                // Live mode updates lights/animations every frame; explicit frozen mode snapshots them.
                if(materials)try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_material_assets")){
                    materialAssets=useHistory&&history.frozen()&&history.samples()>0?assets.buffer():assets.prepare(encoder,device,material,weather,shadows,context.scene);
                }
                if(materials&&materialAssets==null){state="waiting for Material 3 atlases";return;}
                if(!context.render(encoder,java.util.List.of(),inverse,pos.x(),pos.y(),pos.z(),texture,width,height,materialAssets)){state="waiting for terrain BLAS";return;}
                displayed=useHistory?accumulation.add(encoder,view,history.samples(),history.target(),width,height):view;
                if(useHistory)history.accepted();
            }
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_debug_composite");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan RT normal bringup").withRenderArea(new RenderPass.RenderArea(0,0,target.width,target.height)).withColorAttachment(target.getColorTextureView(),Optional.empty()))) {
                pass.setPipeline(materials?MATERIAL_DISPLAY:DISPLAY);pass.bindTexture("RtNormal",displayed,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
            }
            if(++diagnosticFrames==30)diagnose(encoder,width,height);
            state=materials?"material transport; shared environment; sun/environment/held/emissive NEE + MIS; reconstruction NONE":transport?"geometry transport test; grey diffuse; no reconstruction":"normal/debug only";
        } catch(RuntimeException error) {
            close();failed=true;state="failed; raster retained";org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Vulkan RT POC failed; raster remains available",error);
        }
    }
    private void diagnose(CommandEncoder encoder,int width,int height) {
        var read=RenderSystem.getDevice().createBuffer(()->"VoxelLight POC two-pixel diagnostic",com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST|com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ,materials?96:32);
        if(materials)context.copyLightingDiagnostic(encoder,read);
        encoder.copyTextureToBuffer(texture,read,0,()->{},0,0,0,1,1);
        encoder.copyTextureToBuffer(texture,read,16,()->{
            try(var mapped=read.map(true,false)) {
                var b=mapped.data().order(java.nio.ByteOrder.nativeOrder());
                diagnostic="marker="+b.getFloat(0)+"/"+b.getFloat(4)+"/"+b.getFloat(8)+"/"+b.getFloat(12)+",center="+b.getFloat(16)+"/"+b.getFloat(20)+"/"+b.getFloat(24)+"/"+b.getFloat(28);
                if(materials){String[] fields={"heldIncident","heldBsdf","heldHemisphere","heldVisibility"};for(int i=0;i<4;i++){int o=32+i*16;diagnostic+=","+fields[i]+"="+b.getFloat(o)+"/"+b.getFloat(o+4)+"/"+b.getFloat(o+8)+"/"+b.getFloat(o+12);}}
                org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT POC GPU diagnostic: {}",diagnostic);
            }catch(RuntimeException error){diagnostic="readback failed";org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("POC two-pixel diagnostic failed",error);}
            finally{read.close();}
        },0,width/2,height/2,1,1);
    }
    String status() {return ", vulkanRtPoc="+enabled+", vulkanRtState="+state+", vulkanRtGpuDiagnostic="+diagnostic+", vulkanRtMaterialAssetBytes="+assets.bytes()+assets.status()+", stationaryAccumulation="+history.enabled()+", accumulationFrozen="+history.frozen()+", accumulatedSpp="+history.samples()+"/"+history.target()+", accumulationReset="+history.reason()+", vulkanRtPipelineStartupMs="+startupMs+(context==null?"":", "+context.status());}
    private void releaseTexture() {if(view!=null)view.close();if(texture!=null)texture.close();view=null;texture=null;}
    @Override public void close() {lightingChange.reset();history.reset("world/resources/backend");accumulation.close();assets.close();if(context!=null)context.close();context=null;releaseTexture();warmup.close();diagnosticFrames=0;diagnostic="pending";}
}
