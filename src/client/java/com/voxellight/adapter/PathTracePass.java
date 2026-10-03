package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.voxellight.VoxelLightClient;
import com.voxellight.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Experimental reprojected hybrid PT. GPU readback is asynchronous; CUDA waits only on the worker. */
final class PathTracePass implements AutoCloseable {
    static final RenderPipeline PT_CAPTURE=pipeline(true),PT_COMPOSITE=pipeline(false);
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"VoxelLight-OptiX");t.setDaemon(true);return t;});
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicReference<Result> completed=new AtomicReference<>();
    private volatile long generation;
    private volatile String state="off";
    private boolean enabled,denoise=true,debug,freeze,temporalHistory=true,delayUpload;
    private int rejectionDebug;
    private final PathTraceWindow window=new PathTraceWindow();
    private final Map<SectionKey,Long> materialVersions=new HashMap<>();
    private long materialRevision,frameNumber;
    private record Retired(long frame,Result result){}
    private final ArrayDeque<Retired> retired=new ArrayDeque<>();
    void endFrame(){frameNumber++;while(!retired.isEmpty()&&retired.peek().frame<=frameNumber)retired.remove().result.release();}
    void setFreeze(boolean value){freeze=value;}
    void setHistory(boolean value){temporalHistory=value;close();}
    void setUploadDelay(boolean value){delayUpload=value;}
    void setRejectionDebug(boolean value){rejectionDebug=value?1:0;}
    private volatile boolean failed;
    private long handle,lastSubmit;
    private Key workerKey,displayKey;
    private GpuBuffer uniform,historyUniform;
    private final GpuTexture[] textures=new GpuTexture[3];
    private final GpuTextureView[] views=new GpuTextureView[3];
    private GpuTexture composite;
    private GpuTextureView compositeView;
    private int width,height,samples;
    private long accepted,rejected;
    private long traceNs,sceneHash=Long.MIN_VALUE;
    private ByteBuffer scene;
    record Key(long generation,long scene,long history,double x,double y,double z,Matrix4f clip,int width,int height,int sun,int weather,ShadowLight.Source source){
        boolean matches(Key other){return surfaceMatches(other) && sun==other.sun && weather==other.weather;}
        boolean canReproject(Key other){return other!=null && generation==other.generation && history==other.history && width==other.width && height==other.height && source==other.source;}
        boolean surfaceMatches(Key other){return other!=null && generation==other.generation && scene==other.scene && width==other.width && height==other.height && source==other.source
            && Math.abs(x-other.x)<.002 && Math.abs(y-other.y)<.002 && Math.abs(z-other.z)<.002 && clip.equals(other.clip,1.e-5f);}
    }
    private record Result(Key key,ByteBuffer light,ByteBuffer positions,ByteBuffer normals,int samples,long nanos) {
        void release(){MemoryUtil.memFree(light);MemoryUtil.memFree(positions);MemoryUtil.memFree(normals);}
    }
    void setEnabled(boolean value){enabled=value;failed=false;close();state=value?"waiting for traced surfaces":"off";}
    void setDenoise(boolean value){denoise=value;generation++;displayKey=null;failed=false;}
    void setDebug(boolean value){debug=value;}
    String status(){return ", pathtrace="+state+", pathtraceDenoise="+(denoise?"OptiX HDR":"raw")+", pathtraceWorkerBatches="+samples+", pathtraceHistory="+temporalHistory+", pathtraceFrozen="+freeze+", pathtraceAccepted="+accepted+", pathtraceRejected="+rejected+", pathtraceSize="+width+"x"+height+", pathtraceWorkerNs="+traceNs;}
    GpuTextureView render(CommandEncoder encoder,RenderTarget target,MaterialCapture material,ShadowRenderer shadows,GpuTextureView hdr,Matrix4f projection,boolean observed){
        if(!enabled||failed||!observed)return hdr;
        try{
            int scale=Math.max(4,Math.max((target.width+639)/640,(target.height+359)/360));int w=(target.width+scale-1)/scale,h=(target.height+scale-1)/scale;
            var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            var skyState=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
            var pos=camera.pos;var center=window.admit(pos.x(),pos.y(),pos.z());
            var bridge=VoxelLightClient.scene().bridge();var stats=bridge.stats();var snapshots=bridge.snapshots();
            // Only local material content affects secondary rays. LIGHT revisions and distant caster churn do not.
            long hash=stats.worldGeneration()*31+stats.resourceGeneration();hash=hash*31+center.hashCode();
            for(var snapshot:snapshots) {var k=snapshot.request().key();if(Math.abs(k.x()-center.x())<=2&&Math.abs(k.y()-center.y())<=2&&Math.abs(k.z()-center.z())<=2){
                long fingerprint=snapshot.materialFingerprint();Long previous=materialVersions.put(k,fingerprint);
                if(previous!=null && previous!=fingerprint)materialRevision++;
                hash+=PathTraceAdmission.sectionHash(k,fingerprint);
            }}
            Set<SectionKey> available=new HashSet<>();for(var snapshot:snapshots)available.add(snapshot.request().key());
            for(var iterator=materialVersions.keySet().iterator();iterator.hasNext();){var k=iterator.next();boolean local=Math.abs(k.x()-center.x())<=2&&Math.abs(k.y()-center.y())<=2&&Math.abs(k.z()-center.z())<=2;
                if(!local || !available.contains(k)){iterator.remove();if(local)materialRevision++;}}
            var key=new Key(generation,hash,(materialRevision*31+stats.worldGeneration())*31+stats.resourceGeneration(),pos.x(),pos.y(),pos.z(),new Matrix4f(projection).mul(camera.viewRotationMatrix),w,h,(int)Math.floor(shadows.light().angleRadians()/.02),(int)(skyState.rainBrightness*32),shadows.light().source());
            if(width!=w||height!=h||composite==null||composite.getWidth(0)!=target.width||composite.getHeight(0)!=target.height){releaseTargets();width=w;height=h;displayKey=null;}
            prepare(target,w,h);
            var result=completed.getAndSet(null);
            if(result!=null){
                try{if(!freeze && result.key.canReproject(key)){
                    encoder.writeToTexture(textures[0],result.light,0,0,0,0,w,h);encoder.writeToTexture(textures[1],result.positions,0,0,0,0,w,h);encoder.writeToTexture(textures[2],result.normals,0,0,0,0,w,h);
                    accepted++;displayKey=result.key;samples=result.samples;traceNs=result.nanos;state="diffuse hybrid active; per-surface EMA";
                }else rejected++;}finally{
                    // MC 26.2 synchronously copies into owned uploadStaging memory; delay is an A/B diagnostic.
                    if(delayUpload){retired.add(new Retired(frameNumber+8,result));while(retired.size()>4)retired.remove().result.release();}else result.release();
                }
            }
            try(var stack=MemoryStack.stackPush()){
                encoder.writeToBuffer(uniform.slice(),Std140Builder.onStack(stack,144).putMat4f(new Matrix4f(projection).invert()).putMat4f(new Matrix4f(camera.viewRotationMatrix).invert()).putVec4(1,debug?1:0,rejectionDebug,key.canReproject(displayKey)?1:0).get());
            }
            if(!freeze&&!busy.get()&&System.nanoTime()-lastSubmit>100_000_000L){
                byte[] uuid=gpuUuid();ByteBuffer params=ByteBuffer.allocateDirect(164).order(ByteOrder.nativeOrder());
                params.putFloat((float)(pos.x()-(center.x()-2)*16)).putFloat((float)(pos.y()-(center.y()-2)*16)).putFloat((float)(pos.z()-(center.z()-2)*16));
                var light=shadows.light();var direction=light.direction();params.putFloat(direction.x).putFloat(direction.y).putFloat(direction.z);
                var sky=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;
                var env=LightingEnvironment.polished(light,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);
                params.putFloat(env.directR()*env.directStrength()).putFloat(env.directG()*env.directStrength()).putFloat(env.directB()*env.directStrength());
                params.putFloat(env.skyR()*env.skyStrength()).putFloat(env.skyG()*env.skyStrength()).putFloat(env.skyB()*env.skyStrength());
                var m=camera.viewRotationMatrix;params.putFloat(m.m00()).putFloat(m.m10()).putFloat(m.m20()).putFloat(m.m01()).putFloat(m.m11()).putFloat(m.m21()).putFloat(m.m02()).putFloat(m.m12()).putFloat(m.m22());
                for(int i=0;i<20;i++)params.putFloat(0);params.flip();
                submit(encoder,target,material,key,uuid,snapshots,center,params,denoise);lastSubmit=System.nanoTime();
            }
            if(!key.canReproject(displayKey)){state="waiting for traced surfaces; raster retained";if(rejectionDebug==0)return hdr;}
            state=freeze?"frozen observation; raster/reprojection active":key.matches(displayKey)?"diffuse hybrid active; per-surface EMA":"diffuse hybrid active; reprojected EMA";
            try(var stack=MemoryStack.stackPush()){
                encoder.writeToBuffer(historyUniform.slice(),Std140Builder.onStack(stack,80).putMat4f(displayKey==null?key.clip:displayKey.clip)
                    .putVec4((float)(key.x-(displayKey==null?key.x:displayKey.x)),(float)(key.y-(displayKey==null?key.y:displayKey.y)),(float)(key.z-(displayKey==null?key.z:displayKey.z)),0).get());
            }
            var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try(var profile=RenderPassProfile.begin(encoder,"pathtrace_composite");var pass=encoder.createRenderPass(descriptor(compositeView,target.width,target.height,"VoxelLight path traced indirect light"))){
                pass.setPipeline(PT_COMPOSITE);pass.setUniform("PathTraceHistory",historyUniform);pass.bindTexture("CurrentHdr",hdr,nearest);pass.bindTexture("PathRadiance",views[0],nearest);pass.bindTexture("PathPosition",views[1],nearest);pass.bindTexture("PathNormal",views[2],nearest);
                bindMaterial(pass,material,target,nearest);pass.setUniform("PathTraceSettings",uniform);pass.draw(3,1,0,0);
            }
            return compositeView;
        }catch(Exception|LinkageError ex){failure(ex);return hdr;}
    }
    private static int linearColor(int c){int r=(int)Math.round(PathTraceColor.linear(((c>>16)&255)/255.)*255),g=(int)Math.round(PathTraceColor.linear(((c>>8)&255)/255.)*255),b=(int)Math.round(PathTraceColor.linear((c&255)/255.)*255);return r<<16|g<<8|b;}
    private void prepare(RenderTarget target,int w,int h){
        var d=RenderSystem.getDevice();if(!d.precompilePipeline(PT_CAPTURE,RenderProbe.SHADERS).isValid()||!d.precompilePipeline(PT_COMPOSITE,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Path trace shader compilation failed");
        if(historyUniform==null)historyUniform=d.createBuffer(()->"VoxelLight PT reprojection",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,80);
        if(uniform==null)uniform=d.createBuffer(()->"VoxelLight PT settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,144);
        if(textures[0]==null){for(int i=0;i<3;i++){textures[i]=d.createTexture("VoxelLight PT result "+i,GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,w,h,1,1);views[i]=d.createTextureView(textures[i]);}
            composite=d.createTexture("VoxelLight PT HDR composite",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,target.width,target.height,1,1);compositeView=d.createTextureView(composite);
        }
    }
    private void submit(CommandEncoder encoder,RenderTarget target,MaterialCapture material,Key key,byte[] uuid,List<SectionSnapshot> snapshots,SectionKey center,ByteBuffer params,boolean denoiseJob){
        busy.set(true);var d=RenderSystem.getDevice();GpuTexture[] t=new GpuTexture[3];GpuTextureView[] v=new GpuTextureView[3];GpuBuffer[] read=new GpuBuffer[3];ByteBuffer[] cpu=new ByteBuffer[3];int bytes=key.width*key.height*16;
        try{
            for(int i=0;i<3;i++){t[i]=d.createTexture("VoxelLight PT capture "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA32_FLOAT,key.width,key.height,1,1);v[i]=d.createTextureView(t[i]);read[i]=d.createBuffer(()->"VoxelLight PT asynchronous readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,bytes);}
            var desc=descriptor(v[0],key.width,key.height,"VoxelLight PT primary guides").withColorAttachment(v[1],Optional.of(new Vector4f(0))).withColorAttachment(v[2],Optional.of(new Vector4f(0)));
            try(var profile=RenderPassProfile.begin(encoder,"pathtrace_capture");var pass=encoder.createRenderPass(desc)){pass.setPipeline(PT_CAPTURE);bindMaterial(pass,material,target,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.setUniform("PathTraceSettings",uniform);pass.draw(3,1,0,0);}
            // The sentinel prevents synchronous callbacks from dispatching before all copies are registered.
            AtomicInteger pending=new AtomicInteger(1);AtomicBoolean broken=new AtomicBoolean();
            Runnable finish=()->{
                if(pending.decrementAndGet()!=0)return;
                if(key.generation!=generation||broken.get()||failed||Arrays.stream(cpu).anyMatch(Objects::isNull)){for(var b:cpu)if(b!=null)MemoryUtil.memFree(b);busy.set(false);return;}
                worker.execute(()->trace(key,uuid,snapshots,center,params,cpu,denoiseJob));
            };
            int registered=0;
            try{
                for(int i=0;i<3;i++){final int index=i;pending.incrementAndGet();
                    try{encoder.copyTextureToBuffer(t[i],read[i],0,()->{
                        try(var mapped=read[index].map(true,false)){cpu[index]=MemoryUtil.memAlloc(bytes);cpu[index].put(mapped.data()).flip();}
                        catch(Exception ex){broken.set(true);if(key.generation==generation)failure(ex);}
                        finally{read[index].close();v[index].close();t[index].close();}
                        finish.run();
                    },0);registered++;}catch(Exception ex){pending.decrementAndGet();throw ex;}
                }
            }catch(Exception ex){
                broken.set(true);if(key.generation==generation)failure(ex);
                // Already submitted copies retain their own resources until the completion callback.
                for(int i=registered;i<3;i++){read[i].close();v[i].close();t[i].close();}
            }finally{finish.run();}

        }catch(Exception ex){for(int i=0;i<3;i++){if(read[i]!=null)read[i].close();if(v[i]!=null)v[i].close();if(t[i]!=null)t[i].close();}busy.set(false);throw ex;}
    }
    private void trace(Key key,byte[] uuid,List<SectionSnapshot> snapshots,SectionKey center,ByteBuffer params,ByteBuffer[] cpu,boolean denoiseJob){
        ByteBuffer output=null;boolean transferred=false;
        try{
            if(key.generation!=generation)return;
            if(scene==null||sceneHash!=key.scene){
                var colors=new HashMap<Integer,Integer>();
                scene=PathTraceScene.encode(snapshots,center,id->colors.computeIfAbsent(id,k->{int c=Block.stateById(k).getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO).col;if(c==0)c=0x999999;return linearColor(c);}));sceneHash=key.scene;
            }
            if(handle==0||workerKey==null||workerKey.width!=key.width||workerKey.height!=key.height){if(handle!=0)OptixBridge.destroy(handle);handle=0;handle=OptixBridge.create(uuid,OptixBridge.load(),key.width,key.height);workerKey=null;}
            output=MemoryUtil.memAlloc(key.width*key.height*16);long start=System.nanoTime();
            // Reuse history by surface/world validity, never camera equality or sun/weather bins.
            boolean reuse=temporalHistory && key.canReproject(workerKey);
            if(workerKey!=null){for(int i=0;i<16;i++)params.putFloat((21+i)*4,workerKey.clip.get(i/4,i%4));
                params.putFloat(37*4,(float)(key.x-workerKey.x));params.putFloat(38*4,(float)(key.y-workerKey.y));params.putFloat(39*4,(float)(key.z-workerKey.z));}
            params.putFloat(40*4,reuse?1:0);
            int n=OptixBridge.trace(handle,cpu[0],cpu[1],cpu[2],scene,params,output,!reuse,denoiseJob);workerKey=key;
            if(key.generation==generation){var old=completed.getAndSet(new Result(key,output,cpu[0],cpu[1],n,System.nanoTime()-start));if(old!=null)old.release();transferred=true;}
        }catch(Exception|LinkageError ex){if(key.generation==generation)failure(ex);}
        finally{MemoryUtil.memFree(cpu[2]);if(!transferred){MemoryUtil.memFree(cpu[0]);MemoryUtil.memFree(cpu[1]);if(output!=null)MemoryUtil.memFree(output);}busy.set(false);}
    }
    private void failure(Throwable ex){failed=true;state="unavailable; raster retained: "+ex.getMessage();org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("Experimental OptiX path tracing unavailable",ex);}
    private static byte[] gpuUuid(){
        var backend=((GpuBackendAccess)RenderSystem.getDevice()).voxellight$backend();if(!(backend instanceof VulkanDevice vk))throw new IllegalStateException("OptiX prototype requires native Vulkan");
        try(var stack=MemoryStack.stackPush()){var id=VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();var properties=VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address());VK11.vkGetPhysicalDeviceProperties2(vk.vkDevice().getPhysicalDevice(),properties);byte[] uuid=new byte[16];id.deviceUUID().get(uuid);return uuid;}
    }
    private static void bindMaterial(RenderPass pass,MaterialCapture material,RenderTarget target,GpuSampler sampler){pass.bindTexture("MaterialAlbedo",material.view(0),sampler);pass.bindTexture("MaterialNormal",material.view(1),sampler);pass.bindTexture("MaterialDepth",material.view(3),sampler);pass.bindTexture("SceneDepth",target.getDepthTextureView(),sampler);}
    private static RenderPassDescriptor descriptor(GpuTextureView v,int w,int h,String label){return RenderPassDescriptor.create(()->label).withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(v,Optional.of(new Vector4f(0)));}
    private static RenderPipeline pipeline(boolean capture){
        var layout=BindGroupLayout.builder().withSampler("MaterialAlbedo").withSampler("MaterialNormal").withSampler("MaterialDepth").withSampler("SceneDepth").withUniform("PathTraceSettings",UniformType.UNIFORM_BUFFER);
        if(!capture)layout.withUniform("PathTraceHistory",UniformType.UNIFORM_BUFFER).withSampler("CurrentHdr").withSampler("PathRadiance").withSampler("PathPosition").withSampler("PathNormal");
        var b=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",capture?"pipeline/pathtrace_capture":"pipeline/pathtrace_composite")).withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",capture?"pathtrace_capture":"pathtrace_composite")).withBindGroupLayout(layout.build()).withColorTargetState(new ColorTargetState(Optional.empty(),capture?GpuFormat.RGBA32_FLOAT:GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if(capture)for(int i=1;i<3;i++)b.withColorTargetState(i,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL));return b.build();
    }
    private void releaseTargets(){for(int i=0;i<3;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(textures[i]!=null){textures[i].close();textures[i]=null;}}if(compositeView!=null){compositeView.close();compositeView=null;}if(composite!=null){composite.close();composite=null;}}
    @Override public void close(){generation++;displayKey=null;samples=0;window.reset();materialVersions.clear();materialRevision++;while(!retired.isEmpty())retired.remove().result.release();releaseTargets();if(uniform!=null){uniform.close();uniform=null;}if(historyUniform!=null){historyUniform.close();historyUniform=null;}var result=completed.getAndSet(null);if(result!=null)result.release();worker.execute(()->{if(handle!=0){OptixBridge.destroy(handle);handle=0;}workerKey=null;scene=null;sceneHash=Long.MIN_VALUE;var pendingResult=completed.getAndSet(null);if(pendingResult!=null)pendingResult.release();});}
}
