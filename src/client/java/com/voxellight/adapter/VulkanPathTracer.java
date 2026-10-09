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

/** Vulkan camera-path transport, with separate realtime and reference reconstruction. */
final class VulkanPathTracer implements AutoCloseable {
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
    private int samplesPerFrame=1;
    private boolean realtime=true;
    private final RtReconstruction reconstruction=new RtReconstruction();
    void optix(boolean value){reconstruction.optix(value);}
    void dlss(){reconstruction.dlss();history.reset("reconstruction backend");}
    void realtime(boolean value){if(realtime!=value)close();realtime=value;history.reset("render mode");reconstruction.close();}
    private int internalScale=0;
    void internalScale(int value){if(value<0||value>8)throw new IllegalArgumentException("Scale must be 0..8");internalScale=value;history.reset("resolution");}
    void samplesPerFrame(int value){if(value<1||value>8)throw new IllegalArgumentException("Samples per frame must be 1..8");samplesPerFrame=value;}
    void accumulateSpp(int value){history.target(value);}
    void accumulateReset(){history.reset("manual");}
    private final RtDynamicScene dynamic=new RtDynamicScene();
    private final RtTerrainWarmup warmup=new RtTerrainWarmup();
    private VulkanRtContext context;
    private long executionRevision;
    private boolean benchmarkTerrainLoaded;
    private GpuTexture texture;
    private GpuTextureView view;
    private boolean enabled,failed,transport,materials,displayedThisFrame;
    boolean replacesHands(){return displayedThisFrame&&materials&&dynamic.hasHands();}
    private final VulkanRtMaterialAssets assets=new VulkanRtMaterialAssets();
    private final RtDiagnostics diagnostics=new RtDiagnostics();
    void probeLighting(){diagnostics.reset();history.reset("lighting probe");}
    private long world=-1,resources=-1,startupMs;
    private String state="off";
    void enable(boolean value) {
        close();transport=false;materials=false;enabled=value;failed=false;RtGeometryStream.enable(value);state=value?"waiting for Vulkan RT":"off";
    }
    void enableTransport() { enable(true);transport=true; }
    void enableMaterials(){enableTransport();materials=true;}
    public java.util.List<RtGeometryStream.Section> benchmarkSnapshot(){var p=Minecraft.getInstance().gameRenderer.mainCamera().position();return context==null?java.util.List.of():context.scene.benchmarkSnapshot(p.x,p.y,p.z);}
    com.voxellight.rt.RtBenchmarkState benchmarkState(){return context==null||!materials||failed||executionRevision!=com.voxellight.rt.RtExecutionOptions.revision()?null:context.benchmarkState(realtime,history.frozen());}
    boolean enabled() {return enabled;}
    boolean active(){return enabled&&!failed;}
    boolean displayed(){return displayedThisFrame;}
    void beginWorldFrame(){displayedThisFrame=false;}
    void render(CommandEncoder encoder,RenderTarget target,Matrix4f projection,boolean observed,MaterialCapture material,EnvironmentPass weather,ShadowRenderer shadows) {
        displayedThisFrame=false;
        if(!enabled||failed)return;
        if(!(((GpuBackendAccess)RenderSystem.getDevice()).voxellight$backend() instanceof VulkanDevice device)) {state="Vulkan unavailable; raster retained";return;}
        if(!observed){state="waiting for camera projection";return;}
        if(materials&&weather.settings()==null){state="waiting for shared environment settings";return;}
        try {
            var stats=com.voxellight.VoxelLightClient.scene().bridge().stats();
            if(context!=null&&(world!=stats.worldGeneration()||resources!=stats.resourceGeneration()||executionRevision!=com.voxellight.rt.RtExecutionOptions.revision())) {close();RtGeometryStream.enable(true);}
            if(context==null) {
                if(!RtGeometryStream.enabled()){RtGeometryStream.enable(true);}
                long start=System.nanoTime();context=new VulkanRtContext(device,transport,materials,realtime);executionRevision=com.voxellight.rt.RtExecutionOptions.revision();
                if(!RenderSystem.getDevice().precompilePipeline(materials?MATERIAL_DISPLAY:DISPLAY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Vulkan RT debug display pipeline unavailable");
                startupMs=(System.nanoTime()-start)/1_000_000;
                world=stats.worldGeneration();resources=stats.resourceGeneration();
                org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT {} pipeline ready in {} ms; no OptiX tracing",materials?"material transport":transport?"geometry transport test":"normal POC",startupMs);
            }
            int scale=internalScale==0?Math.max(4,Math.max((target.width+639)/640,(target.height+359)/360)):internalScale;
            int width=Math.max(1,(target.width+scale-1)/scale),height=Math.max(1,(target.height+scale-1)/scale);
            long maxPixels=context.maxPixels(transport?samplesPerFrame:1);
            int[] rrSize=materials&&realtime?reconstruction.optimalSize(target.width,target.height,maxPixels):null;
            if(rrSize!=null){width=rrSize[0];height=rrSize[1];}
            context.rrJitter(rrSize!=null);
            if((long)width*height>maxPixels){double reduction=Math.sqrt((double)width*height/maxPixels);width=Math.max(1,(int)(width/reduction));height=Math.max(1,(int)(height/reduction));}
            if(texture==null||texture.getWidth(0)!=width||texture.getHeight(0)!=height) {
                releaseTexture();texture=device.createTexture("VoxelLight Vulkan RT normal",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,width,height,1,1);view=device.createTextureView(texture);
            }
            RenderPassProfile.workload(width,height,samplesPerFrame,context.scene.generation());
            var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;var pos=camera.pos;
            var benchmarkTerrain=RtBenchmarkRunner.terrainSnapshot();
            var inverse=new Matrix4f(projection).mul(camera.viewRotationMatrix).invert();
            if(benchmarkTerrain!=null){
                context.drainPageRequests();
                if(!benchmarkTerrainLoaded){context.scene.benchmarkTerrain(benchmarkTerrain.stream().map(RtGeometryStream.Section::key).collect(java.util.stream.Collectors.toSet()));context.prepareScene(encoder,benchmarkTerrain,pos.x(),pos.y(),pos.z());benchmarkTerrainLoaded=true;}
            }else{
                benchmarkTerrainLoaded=false;
                context.scene.benchmarkTerrain(java.util.Set.of());
                context.scene.requestPages(warmup.requestPages(context.drainPageRequests()));
                warmup.prepare(context.scene.resident(),pos.x(),pos.y(),pos.z());
                context.prepareScene(encoder,RtGeometryStream.drain(16),pos.x(),pos.y(),pos.z());
            }
            var benchmarkUpdates=RtBenchmarkRunner.sceneUpdates(pos.x(),pos.y(),pos.z());
            if(!benchmarkUpdates.isEmpty())context.prepareScene(encoder,benchmarkUpdates,pos.x(),pos.y(),pos.z());
            if(materials&&(realtime||!history.frozen()||history.samples()==0))dynamic.prepare(encoder,context,pos.x(),pos.y(),pos.z());
            context.commitScene(encoder,pos.x(),pos.y(),pos.z());
            RenderPassProfile.workload(width,height,samplesPerFrame,context.scene.generation());
            double[] pose=new double[19];pose[0]=pos.x();pose[1]=pos.y();pose[2]=pos.z();
            float[] matrix=new float[16];inverse.get(matrix);for(int i=0;i<16;i++)pose[i+3]=matrix[i];
            if(materials&&realtime)reconstruction.lighting(assets.lightingSignature(shadows));
            if(materials&&!history.frozen()&&lightingChange.changed(assets.lightingSignature(shadows)))history.reset("lighting");
            history.begin(pose,context.scene.generation()+com.voxellight.rt.RtInvalidationQueue.generation(),width,height);
            boolean useHistory=transport&&history.enabled()&&(!materials||!realtime);
            GpuTextureView displayed=useHistory&&history.samples()>0?accumulation.view():view;
            if(!useHistory||history.needsSample()){
                com.voxellight.rt.vulkan.VulkanRtBuffer materialAssets=null;
                // Live mode updates lights/animations every frame; explicit frozen mode snapshots them.
                if(materials)try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_material_assets")){
                    materialAssets=useHistory&&history.frozen()&&history.samples()>0?assets.buffer():assets.prepare(encoder,device,material,weather,shadows,context.scene,dynamic);
                }
                if(materials)context.runtimeLighting(assets.lightingSignature(shadows));
                if(materials&&materialAssets==null){state="waiting for Material 3 atlases";return;}
                context.reconstructionGuides(materials&&realtime);
                int budget=transport?samplesPerFrame:1;
                if(useHistory&&history.frozen())budget=Math.min(budget,history.target()-history.samples());
                if(!context.renderBatch(encoder,java.util.List.of(),inverse,pos.x(),pos.y(),pos.z(),texture,width,height,materialAssets,budget)){state="waiting for terrain BLAS";return;}
                displayed=useHistory?accumulation.add(encoder,view,history.samples(),history.target(),width,height):view;
                if(useHistory)history.accepted(budget);
                if(materials&&realtime)displayed=reconstruction.resolve(encoder,context,view,new Matrix4f(inverse).invert(),pos.x(),pos.y(),pos.z(),context.scene.historyGeneration(),width,height,target.width,target.height,camera.viewRotationMatrix);
            }
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_pt_composite");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight Vulkan PT composite").withRenderArea(new RenderPass.RenderArea(0,0,target.width,target.height)).withColorAttachment(target.getColorTextureView(),Optional.empty()))) {
                pass.setPipeline(materials?MATERIAL_DISPLAY:DISPLAY);pass.bindTexture("RtNormal",displayed,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
            }
            displayedThisFrame=true;
            diagnostics.observe(encoder,context,texture,materials,width,height);
            state=materials?"material transport; shared environment; sun/environment/held/emissive NEE + MIS; reconstruction "+(realtime?reconstruction.status():"reference progressive") :transport?"geometry transport test; grey diffuse; no reconstruction":"normal/debug only";
        } catch(RuntimeException error) {
            close();failed=true;state="failed; raster retained";org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Vulkan RT POC failed; raster remains available",error);
        }
    }
    String status() {return ", vulkanRtPoc="+enabled+", vulkanRtState="+state+", vulkanRtGpuDiagnostic="+diagnostics.value()+", vulkanRtMaterialAssetBytes="+assets.bytes()+assets.status()+RtGeometryStream.status()+dynamic.status()+", renderMode="+(realtime?"realtime":"reference")+", internalScale="+internalScale+", requestedSppPerFrame="+samplesPerFrame+", stationaryAccumulation="+history.enabled()+", accumulationFrozen="+history.frozen()+", accumulatedSpp="+history.samples()+"/"+history.target()+", accumulationReset="+history.reason()+", vulkanRtPipelineStartupMs="+startupMs+(context==null?"":", "+context.status());}
    private void releaseTexture() {if(view!=null)view.close();if(texture!=null)texture.close();view=null;texture=null;}
    @Override public void close() {benchmarkTerrainLoaded=false;displayedThisFrame=false;lightingChange.reset();history.reset("world/resources/backend");accumulation.close();dynamic.close();assets.close();reconstruction.close();if(context!=null)context.close();context=null;releaseTexture();warmup.close();diagnostics.reset();}
}
