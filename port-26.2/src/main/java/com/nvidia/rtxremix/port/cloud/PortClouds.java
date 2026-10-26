/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.cloud;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.PortRenderer;
import com.nvidia.rtxremix.port.PortPreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Official voxel and Perlin-Worley cloud geometry; only completed CPU payloads cross into JNI. */
public final class PortClouds implements AutoCloseable {
    public enum Mode { VANILLA,PROCEDURAL,OFF }
    private record Mesh(float[] vertices,int[] indices){}
    private record Completed(long epoch,long key,Mesh mesh,Throwable failure){}
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"Remix26 cloud geometry");t.setDaemon(true);return t;});
    private final ConcurrentLinkedQueue<Completed> completed=new ConcurrentLinkedQueue<>();
    private final Set<Long> pending=new HashSet<>(),empty=new HashSet<>();
    private final Map<Long,Long> meshes=new HashMap<>();
    private volatile long epoch;
    private Mode mode;
    private float size=1,smoothness=1.5f;
    private boolean[] occupied;
    private int width,height,color=-1;
    private long material;
    private final float[] transform={1,0,0,0,0,1,0,0,0,0,1,0};
    public static boolean replaced;
    public static int draws,meshCount;
    public void render(LevelRenderState state,float partialTick){
        var mc=Minecraft.getInstance();replaced=false;draws=0;
        var settings=PortPreferences.current;
        if(settings.clouds!=mode||settings.cloudSize!=size||settings.edgeSmoothness!=smoothness){clear();mode=settings.clouds;size=settings.cloudSize;smoothness=settings.edgeSmoothness;}
        if(mode==Mode.OFF){replaced=true;return;}
        if(!Float.isFinite(state.cloudHeight)||mc.options.cloudStatus().get()==CloudStatus.OFF)return;
        if(material==0)material=PortRenderer.api().createMaterial(materialInfo(state.cloudColor));
        if(color!=state.cloudColor){PortRenderer.api().updateMaterial(material,materialInfo(state.cloudColor));color=state.cloudColor;}
        publish();var camera=state.cameraRenderState.pos;
        double time=state.gameTime+partialTick;
        if(mode==Mode.VANILLA){
            ensureMask();
            VanillaCloudPlacement.forEachVisible(camera.x,camera.z,time,width,height,p->{
                Long mesh=meshes.get(p.textureKey);
                if(mesh==null){if(!empty.contains(p.textureKey)&&!pending.contains(p.textureKey)){var copy=p.snapshot();var mask=occupied;int w=width,h=height;float thickness=4*size,edge=smoothness;schedule(p.textureKey,()->{var m=VanillaCloudMeshGenerator.generate(mask,w,h,copy.textureX(),copy.textureZ(),copy.windowWidth(),copy.windowHeight(),copy.cellSizeX(),copy.cellSizeZ(),thickness,edge);return new Mesh(m.vertices(),m.indices());});}return;}
                transform[0]=transform[5]=transform[10]=1;transform[3]=(float)p.x;transform[7]=state.cloudHeight+settings.cloudHeight+.33f;transform[11]=(float)p.z;
                draw(mesh);
            });
        }else{
            for(int i=0;i<32;i++){final int index=i;if(!meshes.containsKey((long)i)&&!pending.contains((long)i))schedule(i,()->{var m=CloudMeshGenerator.generate(CloudPlacement.mix(0x434C4F5544505546L+index));return new Mesh(m.vertices(),m.indices());});}
            CloudPlacement.forEachVisible(camera.x,camera.z,time,p->{
                Long mesh=meshes.get(Math.floorMod(p.seed(),32L));if(mesh==null)return;
                double dx=p.x()-camera.x,dz=p.z()-camera.z;
                double drop=8000-Math.sqrt(8000*8000-Math.min(dx*dx+dz*dz,8000*8000));
                transform[0]=transform[5]=transform[10]=p.scale()*size;transform[3]=(float)p.x();transform[7]=state.cloudHeight+settings.cloudHeight+.33f+p.altitudeOffset()-(float)drop;transform[11]=(float)p.z();draw(mesh);
            });
        }
        meshCount=meshes.size();replaced=draws>0;
    }
    private void draw(long mesh){PortRenderer.api().drawInstance(mesh,transform,InstanceCategoryBit.IGNORE_BAKED_LIGHTING,0,0);draws++;}
    private void ensureMask(){
        if(occupied!=null)return;
        try(var stream=Minecraft.getInstance().getResourceManager().getResourceOrThrow(Identifier.withDefaultNamespace("textures/environment/clouds.png")).open();var image=NativeImage.read(stream)){
            width=image.getWidth();height=image.getHeight();occupied=new boolean[width*height];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++)occupied[y*width+x]=(image.getPixel(x,y)>>>24)>=128;
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException("Cannot load authored cloud texture",e);}
    }
    private void schedule(long key,java.util.function.Supplier<Mesh> factory){
        if(worker.getQueue().remainingCapacity()==0)return;
        long generation=epoch;pending.add(key);
        worker.execute(()->{
            try{if(generation==epoch){var mesh=factory.get();if(generation==epoch)completed.add(new Completed(generation,key,mesh,null));}}
            catch(RuntimeException|Error failure){if(generation==epoch)completed.add(new Completed(generation,key,null,failure));}
            finally{CloudMeshGenerator.releaseWorkspace();}
        });
    }
    private void publish(){
        for(int budget=0;budget<2;budget++){
            var item=completed.poll();if(item==null)return;if(item.epoch()!=epoch){budget--;continue;}
            pending.remove(item.key());if(item.failure()!=null)throw new IllegalStateException("Cloud geometry generation failed",item.failure());
            if(item.mesh().indices().length==0){empty.add(item.key());continue;}
            var data=item.mesh();int count=data.vertices().length/6;
            ByteBuffer vb=MemoryUtil.memAlloc(count*64).order(ByteOrder.nativeOrder()),ib=MemoryUtil.memAlloc(data.indices().length*4).order(ByteOrder.nativeOrder());
            try{
                for(int i=0;i<count;i++){for(int j=0;j<6;j++)vb.putFloat(data.vertices()[i*6+j]);vb.putFloat(0).putFloat(0).putInt(-1);for(int pad=0;pad<7;pad++)vb.putInt(0);}
                for(int index:data.indices())ib.putInt(index);vb.flip();ib.flip();
                var surface=new MeshInfoSurfaceTriangles();surface.vertexBuffer=vb;surface.vertexCount=count;surface.indexBuffer=ib;surface.indexCount=data.indices().length;surface.material=material;
                meshes.put(item.key(),PortRenderer.api().createMesh(MeshInfo.builder().hash(0x26280000L^item.key()^((long)Arrays.hashCode(data.vertices())<<16)^(mode==Mode.PROCEDURAL?0x100000:0)).surfaces(new MeshInfoSurfaceTriangles[]{surface}).build()));
            }finally{MemoryUtil.memFree(vb);MemoryUtil.memFree(ib);}
        }
    }
    private static MaterialInfo materialInfo(int packed){
        float red=tint((packed>>>16)&255),green=tint((packed>>>8)&255),blue=tint(packed&255);
        var sss=MaterialInfoOpaqueSubsurfaceEXT.builder().diffusionProfile(true).transmittanceColor(.999f,.999f,.999f).volumetricAnisotropy(0).radius(.98f,.98f,.98f).radiusScale(5000).maxSampleRadius(1).build();
        return MaterialInfo.builder().hash(0x26280001).opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(red,green,blue).roughnessConstant(1).metallicConstant(0).subsurfaceExt(sss).build()).build();
    }
    private static float tint(int value){return .99f*Math.round((1-.2*(1-value/255.0))*255)/255;}
    public void clear(){
        epoch++;worker.getQueue().clear();completed.clear();pending.clear();empty.clear();
        for(long mesh:meshes.values())PortRenderer.api().destroyMesh(mesh);meshes.clear();
        if(material!=0)PortRenderer.api().destroyMaterial(material);material=0;color=-1;occupied=null;replaced=false;draws=meshCount=0;
    }
    @Override public void close(){clear();worker.shutdownNow();}
}
