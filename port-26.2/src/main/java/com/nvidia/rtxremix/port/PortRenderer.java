/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.minecraft.gl.GlInterop;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL45.*;

/** Prototype scene adapter using the unchanged published JNI/Runtime ABI. */
public final class PortRenderer {
    record Asset(long mesh,long instance) {}
    private static RemixAPI api;
    private static GlInterop interop;
    private static long hwnd;
    private static com.nvidia.rtxremix.port.sky.PortSky sky;
    private static PortDepth depth;
    private static PortSceneConfig sceneConfig;
    private static PortViewModel viewModel;
    private static PortParticles particles;
    private static com.nvidia.rtxremix.port.cloud.PortClouds clouds;
    private static InstanceInfoBlendEXT vertexColorBlend,decalBlend;
    private static int sourceFbo, destinationFbo;
    private static boolean submitted;
    private static boolean closing;
    private static Object level;
    private static long frames;
    private static final Map<Long,List<Asset>> assets = new HashMap<>();
    private static final List<Asset> features = new ArrayList<>();
    private PortRenderer() {}
    public static RemixAPI api() { return closing?null:api; }
    public static PortViewModel viewModel(){return viewModel;}
    public static PortSceneConfig sceneConfig(){return sceneConfig;}
    public static void beginLevel(){FeatureCapture.begin();PortEntities.begin();}
    public static void clearCapturedScene(){if(api!=null) clearScene();TerrainCapture.clear();FeatureCapture.clear();}
    public static void changeLevel(Object next) {
        if(level==next) return;
        if(api!=null && !closing) clearScene();
        TerrainCapture.clear();FeatureCapture.clear();level=next;
    }

    private static void initialize() {
        RenderSystem.assertOnRenderThread();
        if (!(Minecraft.getInstance().getWindow().backend() instanceof GlBackend)) {
            throw new IllegalStateException("The Remix 26.2 prototype requires the OpenGL backend");
        }
        PortHud.prepare(Minecraft.getInstance().gameDirectory.toPath());
        String sdkProperty = System.getProperty("remix.port.sdk",Minecraft.getInstance().gameDirectory.toPath().resolve("remix-sdk").toString());
        Path sdk = Path.of(sdkProperty).toAbsolutePath();
        hwnd = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().handle());
        RemixAPI.redactCommandLineOptions(sdk.resolve("d3d9.dll").toString(),List.of("--username","--accessToken","--uuid","--xuid","--clientId"));
        api = RemixAPI.load(sdk.resolve("d3d9.dll").toString());
        api.startup(StartupInfo.builder().hwnd(hwnd).interopImage(true).build());
        // The shipped baseline and native Graphics Settings own DLSS, RR and frame generation.
        vertexColorBlend=InstanceInfoBlendEXT.textureTimesVertexColor();vertexColorBlend.allocNative(0);
        decalBlend=InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(1);decalBlend.allocNative(0);
        interop = new GlInterop(api.dxvkGetInteropInfo());
        depth = new PortDepth();
        sceneConfig = new PortSceneConfig(api);
        viewModel = new PortViewModel();
        particles = new PortParticles();
        clouds = new com.nvidia.rtxremix.port.cloud.PortClouds();
        sourceFbo = glCreateFramebuffers(); destinationFbo = glCreateFramebuffers();
        LoggerFactory.getLogger("Remix26Port").info("26.2 Remix startup and external semaphore import succeeded");
    }

    public static void renderWorld(GameRenderer renderer,net.minecraft.client.renderer.state.level.LevelRenderState state) {
        RenderSystem.assertOnRenderThread();
        if (api == null) initialize();
        PortSampling.apply(api);
        Minecraft mc = Minecraft.getInstance();
        if (level != mc.level) { clearScene(); level = mc.level; }
        if(sceneConfig==null)sceneConfig=new PortSceneConfig(api);
        for(var iterator=assets.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!mc.level.getChunkSource().hasChunk(net.minecraft.core.SectionPos.x(entry.getKey()),net.minecraft.core.SectionPos.z(entry.getKey()))){for(var asset:entry.getValue())removeAsset(asset);com.nvidia.rtxremix.port.light.PortBlockLights.remove(entry.getKey());iterator.remove();}}
        for (TerrainCapture.Section section : TerrainCapture.drain(8)) publish(section);
        var camera = renderer.mainCamera();
        var p = camera.position();
        for(Asset feature:features){api.sceneInstanceRemove(feature.instance());api.destroyMesh(feature.mesh());}
        features.clear();
        var batches=FeatureCapture.finish();
        int worldVertices=FeatureCapture.lastVertices,worldBatches=FeatureCapture.lastBatches;
        sceneConfig.update(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false),state.cameraRenderState.fogType);
        com.nvidia.rtxremix.port.light.PortItemLights.render();
        var hands=viewModel.capture(renderer,state.cameraRenderState,mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        PortEntities.publish();
        PortViewItems.publish();
        particles.render(state.particlesRenderState,p);
        PortTextures.retireUnused();
        Map<Integer,List<MeshBatch>> categorized=new LinkedHashMap<>();for(var batch:batches)categorized.computeIfAbsent(batch.categories(),key->new ArrayList<>()).add(batch);
        for(var entry:categorized.entrySet()){
            var offset=(entry.getKey()&InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL)!=0?com.nvidia.rtxremix.port.light.PortItemLights.shadowOffset():net.minecraft.world.phys.Vec3.ZERO;
            features.add(createAsset(entry.getValue(),new float[]{1,0,0,(float)(p.x+offset.x),0,1,0,(float)(p.y+offset.y),0,0,1,(float)(p.z+offset.z)},0x26210000L^entry.getKey(),entry.getKey(),vertexColorBlend.nativePtr));
        }
        if(!hands.isEmpty()) features.add(createAsset(hands,matrix3x4(PortViewModel.cameraToWorld()),0x26260000L,InstanceCategoryBit.VIEW_MODEL,vertexColorBlend.nativePtr));
        FeatureCapture.lastVertices=worldVertices;FeatureCapture.lastBatches=worldBatches;
        PortTextures.flushAnimation();
        float yaw = (float)Math.toRadians(-camera.yRot());
        float pitch = (float)Math.toRadians(camera.xRot());
        float fx=(float)(Math.sin(yaw)*Math.cos(pitch)), fy=-(float)Math.sin(pitch), fz=(float)(Math.cos(yaw)*Math.cos(pitch));
        float rx=-(float)Math.cos(yaw), rz=(float)Math.sin(yaw);
        var params=CameraInfoParameterizedEXT.builder().position((float)p.x,(float)p.y,(float)p.z)
                .forward(fx,fy,fz).right(rx,0,rz).up(-rz*fy,rz*fx-rx*fz,rx*fy)
                .fovYInDegrees((float)Math.toDegrees(2*Math.atan(1/state.cameraRenderState.projectionMatrix.m11()))).aspect((float)renderer.mainRenderTarget().width/renderer.mainRenderTarget().height)
                .nearPlane(0.05f).farPlane(state.cameraRenderState.depthFar).build();
        api.setupCamera(CameraInfo.builder().type(CameraType.WORLD).parameterizedExt(params).build());
        api.setupCamera(CameraInfo.builder().type(CameraType.VIEW_MODEL).parameterizedExt(params).build());
        if(sky==null)sky=new com.nvidia.rtxremix.port.sky.PortSky();
        sky.render(api,state.skyRenderState);
        clouds.render(state,mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        com.nvidia.rtxremix.port.light.PortBlockLights.render();
        api.submit(null); submitted = true;
        InteropInfo info = api.dxvkGetInteropInfo(); interop.recreateImagesIf(info); interop.waitForVk(info);
        copyColor(renderer.mainRenderTarget(),info.width,info.height);
        depth.copy(renderer.mainRenderTarget(),interop.getDepthTexture(),0.05f,state.cameraRenderState.depthFar,state.cameraRenderState.projectionMatrix,RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        var weather=Minecraft.getInstance().levelRenderer.weatherTarget();
        if(weather!=null && weather!=renderer.mainRenderTarget())weather.copyDepthFrom(renderer.mainRenderTarget());
        frames++;
        if (frames==1 || frames==120) LoggerFactory.getLogger("Remix26Port").info(
                "26.2 frame={} retainedSections={} sharedColor={}x{} materials={} featureVertices={}",frames,assets.size(),info.width,info.height,PortTextures.materialCount(),FeatureCapture.lastVertices);
    }

    /** Capture proof after the remaining vanilla raster passes and hand rendering. */
    public static void finishLevel(GameRenderer renderer){
        if(api==null||closing)return;
        InteropInfo info=api.dxvkGetInteropInfo();
        String proofDirectory=System.getProperty("remix.port.proofDirectory");
        if(frames==240 && proofDirectory!=null) {
            Path proof=Path.of(proofDirectory);
            try {
                Files.createDirectories(proof);
                Files.writeString(proof.resolve("port-validation.json"),
                        "{\"minecraft\":\"26.2\",\"frames\":"+frames+",\"retainedSections\":"+assets.size()
                        +",\"sharedWidth\":"+info.width+",\"sharedHeight\":"+info.height
                        +",\"sprites\":"+PortTextures.spriteCount()+",\"materials\":"+PortTextures.materialCount()
                        +",\"featureVertices\":"+FeatureCapture.lastVertices+",\"featureBatches\":"+FeatureCapture.lastBatches
                        +",\"animationPatches\":"+PortTextures.animationPatches
                        +",\"analyticBlockLights\":"+com.nvidia.rtxremix.port.light.PortBlockLights.count
                        +",\"skyUploads\":"+(sky==null?0:sky.uploads)+",\"depthCopies\":"+depth.copies
                        +",\"entityParts\":"+PortEntities.liveParts+",\"entityMeshCreates\":"+PortEntities.meshCreates
                        +",\"entityInstanceUpdates\":"+PortEntities.instanceUpdates+",\"entityReused\":"+PortEntities.reused+",\"offscreenEntities\":"+PortEntities.offscreen+",\"featureScope\":\"textured terrain and captured world features; parity pending\"}");
                Files.writeString(proof.resolve("phase4-features.json"),"{\"viewModelParts\":"+PortEntities.viewModelParts+",\"viewModelVertices\":"+PortViewModel.vertices+",\"shadowParts\":"+PortEntities.shadowParts+",\"particleQuads\":"+PortParticles.quads+",\"cloudMeshes\":"+com.nvidia.rtxremix.port.cloud.PortClouds.meshCount+",\"cloudDraws\":"+com.nvidia.rtxremix.port.cloud.PortClouds.draws+",\"configLayers\":"+sceneConfig.snapshot().size()+",\"dlssQuality\":\""+api.getConfigVariableResolved("rtx.qualityDLSS")+"\",\"rayReconstruction\":\""+api.getConfigVariableResolved("rtx.enableRayReconstruction")+"\",\"frameGeneration\":\""+api.getConfigVariableResolved("rtx.dlfg.enable")+"\"}");
            } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            Screenshot.takeScreenshot(renderer.mainRenderTarget(),image->{
                try(image) { image.writeToFile(proof.resolve("remix-world.png")); }
                catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
        }
    }

    private static void publish(TerrainCapture.Section section) {
        com.nvidia.rtxremix.port.light.PortBlockLights.replace(section.key(),section.lights());
        var old=assets.remove(section.key());
        if(old!=null)for(var asset:old)removeAsset(asset);
        if(section.batches().isEmpty()) return;
        float[] transform={1,0,0,section.x()*16,0,1,0,section.y()*16,0,0,1,section.z()*16};
        Map<Integer,List<MeshBatch>> grouped=new LinkedHashMap<>();
        for(var batch:section.batches())grouped.computeIfAbsent(batch.categories(),key->new ArrayList<>()).add(batch);
        List<Asset> next=new ArrayList<>();
        try{for(var e:grouped.entrySet())next.add(createAsset(e.getValue(),transform,section.key()^0x26200000L^e.getKey(),e.getKey(),(e.getKey()&InstanceCategoryBit.DECAL_STATIC)!=0?decalBlend.nativePtr:vertexColorBlend.nativePtr));}
        catch(RuntimeException error){for(var asset:next)removeAsset(asset);throw error;}
        assets.put(section.key(),List.copyOf(next));
    }
    static Asset createAsset(List<MeshBatch> batches,float[] transform,long seed) {
        return createAsset(batches,transform,seed,0,vertexColorBlend.nativePtr);
    }
    static Asset createAsset(List<MeshBatch> batches,float[] transform,long seed,int categories,long blend) {
        if(blend==0)blend=vertexColorBlend.nativePtr;
        List<ByteBuffer> allocations=new ArrayList<>();List<MeshInfoSurfaceTriangles> surfaces=new ArrayList<>();
        long hash=seed;
        try {
            for(MeshBatch batch:batches) {
                int count=batch.vertices().length/64;int indices=batch.quads()?count/4*6:count;
                ByteBuffer vb=MemoryUtil.memAlloc(batch.vertices().length).order(ByteOrder.nativeOrder()).put(batch.vertices()).flip();allocations.add(vb);
                ByteBuffer ib=MemoryUtil.memAlloc(indices*4).order(ByteOrder.nativeOrder());allocations.add(ib);
                if(batch.quads()) for(int q=0;q<count;q+=4) ib.putInt(q).putInt(q+1).putInt(q+2).putInt(q+2).putInt(q+3).putInt(q);
                else for(int i=0;i<count;i++) ib.putInt(i);
                ib.flip();var surface=new MeshInfoSurfaceTriangles();surface.vertexBuffer=vb;surface.vertexCount=count;
                surface.indexBuffer=ib;surface.indexCount=indices;surface.material=PortTextures.material(batch.material());surfaces.add(surface);
                hash=Long.rotateLeft(hash,13)^Arrays.hashCode(batch.vertices())^surface.material;
            }
            long mesh=api.createMesh(MeshInfo.builder().hash(hash==0?1:hash).surfaces(surfaces.toArray(MeshInfoSurfaceTriangles[]::new)).build());
            try {return new Asset(mesh,api.sceneInstanceAdd(mesh,transform,categories,1,blend));}
            catch(RuntimeException e){api.destroyMesh(mesh);throw e;}
        } finally {for(ByteBuffer buffer:allocations) MemoryUtil.memFree(buffer);}
    }

    private static void copyColor(RenderTarget target,int width,int height) {
        if (!(target.getColorTexture() instanceof GlTexture texture)) throw new IllegalStateException("Expected GL target");
        glNamedFramebufferTexture(sourceFbo,GL_COLOR_ATTACHMENT0,interop.getTexture(),0);
        glNamedFramebufferTexture(destinationFbo,GL_COLOR_ATTACHMENT0,texture.glId(),0);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST);glDisable(GL_SCISSOR_TEST);
        glBlitNamedFramebuffer(sourceFbo,destinationFbo,0,height,width,0,0,0,target.width,target.height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
        if(scissor) glEnable(GL_SCISSOR_TEST);
        int error=glGetError();if(error!=GL_NO_ERROR) throw new IllegalStateException("Remix target blit GL error="+error);
    }

    /** Called after MC's surface blit, while the default GL framebuffer contains the UI. */
    public static boolean present() {
        if(api==null || closing) return false;
        if(!submitted) api.submit(null);
        InteropInfo info=api.dxvkGetInteropInfo();interop.recreateImagesIf(info);
        // Menu-only frames still need a timeline wait before touching the shared image.
        if(!submitted) interop.waitForVk(info);
        interop.copyFromFramebuffer(0);interop.signalDone();
        var present=new PresentInfo();present.hwndOverride=hwnd;present.interopFlipY=1;
        present.clientDoneSemaphore_ValueToWait=interop.getClientDoneSemaphoreValue();
        api.present(present);submitted=false;return true;
    }
    static long addInstance(long mesh,float[] transform){return api.sceneInstanceAdd(mesh,transform,0,1,vertexColorBlend.nativePtr);}
    static void updateInstance(long instance,long mesh,float[] transform){api.sceneInstanceUpdate(instance,mesh,transform,0,1,vertexColorBlend.nativePtr);}
    static long addInstance(long mesh,float[] transform,int categories){return api.sceneInstanceAdd(mesh,transform,categories,1,vertexColorBlend.nativePtr);}
    static void updateInstance(long instance,long mesh,float[] transform,int categories){api.sceneInstanceUpdate(instance,mesh,transform,categories,1,vertexColorBlend.nativePtr);}
    static float[] matrix3x4(org.joml.Matrix4fc m){return new float[]{m.m00(),m.m10(),m.m20(),m.m30(),m.m01(),m.m11(),m.m21(),m.m31(),m.m02(),m.m12(),m.m22(),m.m32()};}
    static void removeAsset(Asset value){api.sceneInstanceRemove(value.instance());api.destroyMesh(value.mesh());}
    private static void clearScene() {
        PortEntities.clear();
        PortViewItems.clear();
        if(particles!=null)particles.clear();
        if(clouds!=null)clouds.clear();
        if(sceneConfig!=null){sceneConfig.close();sceneConfig=null;}
        com.nvidia.rtxremix.port.light.PortBlockLights.clear();
        com.nvidia.rtxremix.port.light.PortItemLights.clear();
        if(sky!=null){sky.close(api);sky=null;}
        for(Asset asset:features){api.sceneInstanceRemove(asset.instance());api.destroyMesh(asset.mesh());}
        features.clear();
        for(var section:assets.values())for(var asset:section)removeAsset(asset);
        assets.clear();
    }
    public static void close() {
        if(api==null || closing) return;
        PortInput.close();
        clearScene();interop.destroy();glDeleteFramebuffers(sourceFbo);glDeleteFramebuffers(destinationFbo);
        particles.close();viewModel.close();clouds.close();
        depth.close();PortTextures.releaseNative();vertexColorBlend.freeNative();decalBlend.freeNative();TerrainCapture.clear();closing=true;
        LoggerFactory.getLogger("Remix26Port").info("GL/scene released; deferring Runtime shutdown until GLFW window destruction");
    }
    public static void shutdownNative() {
        if(api==null) return;
        api.shutdown();api=null;
        LoggerFactory.getLogger("Remix26Port").info("Native Runtime shutdown complete after Minecraft close");
    }
}
