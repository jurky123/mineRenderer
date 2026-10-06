package com.voxellight.adapter;

import com.mojang.blaze3d.systems.*;
import com.voxellight.debug.PassMetrics;
import java.nio.file.Path;
import java.io.IOException;

/** Bounded delayed timestamps. A missing/slow query never stalls rendering. */
public final class RenderPassProfile {
    private static final int SLOTS=128;
    private static GpuQueryPool pool;
    private static boolean failed, enabled;
    private static final Scope OFF=new Scope((CommandEncoder)null,"",0,-1,0);
    public static boolean enabled(){return enabled;}
    public static void setEnabled(boolean value){enabled=value;}
    private static float period;
    private static long frame, serial, skipped;
    private static int width,height,spp;
    private static long sceneGeneration;
    private static final java.util.ArrayDeque<Scope> scopes=new java.util.ArrayDeque<>();
    private static final java.util.LinkedHashSet<Long> counterFrames=new java.util.LinkedHashSet<>();
    public static void markCounterFrame(){counterFrames.add(frame);while(counterFrames.size()>14400)counterFrames.removeFirst();}
    public static boolean counterFrame(long submissionFrame){return counterFrames.contains(submissionFrame);}
    public static long frameId(){return frame;}
    public static void workload(int w,int h,int samples,long generation){width=w;height=h;spp=samples;sceneGeneration=generation;}
    private static final long[] ids=new long[SLOTS], frames=new long[SLOTS];
    private static final boolean[] pending=new boolean[SLOTS];
    private static final com.voxellight.debug.RtWorkMetrics workMetrics=new com.voxellight.debug.RtWorkMetrics();
    private static final java.util.Set<java.util.function.Consumer<PassMetrics.Sample>> observers=new java.util.LinkedHashSet<>();
    public static void observe(java.util.function.Consumer<PassMetrics.Sample> observer){observers.add(observer);}
    public static void unobserve(java.util.function.Consumer<PassMetrics.Sample> observer){observers.remove(observer);}
    private static final java.util.Set<java.util.function.Consumer<com.voxellight.debug.RtWorkMetrics.Sample>> workObservers=new java.util.LinkedHashSet<>();
    public static void observeWork(java.util.function.Consumer<com.voxellight.debug.RtWorkMetrics.Sample> observer){workObservers.add(observer);}
    public static void unobserveWork(java.util.function.Consumer<com.voxellight.debug.RtWorkMetrics.Sample> observer){workObservers.remove(observer);}
    public static long skippedQueries(){return skipped;}
    private static final PassMetrics metrics=new PassMetrics(14400);
    private RenderPassProfile() { }
    public static void nextFrame() {
        frame++;width=height=spp=0;sceneGeneration=0;scopes.clear();
        if(pool==null){RtBenchmarkRunner.nextFrame(frame);return;}
        try {
            for(int i=0;i<SLOTS;i++)if(pending[i] && frame-frames[i]>=2) {
                var values=pool.getValues(i*2,2);
                if(values[0].isPresent() && values[1].isPresent()) {
                    long ticks=values[1].getAsLong()-values[0].getAsLong();
                    double ns=ticks*(double)period;
                    if(ticks>=0 && Double.isFinite(ns) && ns<Long.MAX_VALUE){metrics.completeGpu(ids[i],Math.round(ns));var sample=metrics.sample(ids[i]);if(sample!=null)for(var observer:observers)observer.accept(sample);}
                    pending[i]=false;
                }
            }
        } catch(RuntimeException e){disable();}
        RtBenchmarkRunner.nextFrame(frame);
    }
    public static Scope begin(CommandEncoder encoder,String name) {
        if(!enabled)return OFF;
        int slot=-1;long id=++serial;
        try {
            if(pool==null && !failed) {
                var device=com.mojang.blaze3d.systems.RenderSystem.getDevice();
                period=device.getDeviceInfo().timestampPeriod();
                if(!Float.isFinite(period)||period<=0)throw new UnsupportedOperationException("timestamp period");
                pool=((GpuBackendAccess)device).voxellight$backend() instanceof com.mojang.blaze3d.vulkan.VulkanDevice d?new NativePool(d,SLOTS*2):device.createTimestampQueryPool(SLOTS*2);
            }
            if(pool!=null)for(int i=0;i<SLOTS;i++)if(!pending[i]) {
                slot=i;ids[i]=id;frames[i]=frame;pending[i]=true;encoder.writeTimestamp(pool,i*2);break;
            }
        } catch(RuntimeException e){disable();slot=-1;}
        if(slot<0)skipped++;
        return new Scope(encoder,name,id,slot,System.nanoTime());
    }
    private static final class NativePool extends com.mojang.blaze3d.vulkan.VulkanQueryPool {
        NativePool(com.mojang.blaze3d.vulkan.VulkanDevice device,int size){super(device,size);}
        long handle(){return vkQueryPool();}
    }
    /** Timestamps inside the existing RT command buffer; no extra submissions/dispatches. */
    public static Scope beginNative(org.lwjgl.vulkan.VkCommandBuffer command,String name){
        if(!enabled)return OFF;
        if(pool==null&&!failed){var scope=begin(RenderSystem.getDevice().createCommandEncoder(),"rt_profile_setup");scope.close();}
        if(!(pool instanceof NativePool nativePool))return OFF;
        for(int i=0;i<SLOTS;i++)if(!pending[i]){
            long id=++serial;ids[i]=id;frames[i]=frame;pending[i]=true;
            org.lwjgl.vulkan.VK10.vkCmdResetQueryPool(command,nativePool.handle(),i*2,2);
            org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,nativePool.handle(),i*2);
            return new Scope(command,name,id,i,System.nanoTime());
        }
        skipped++;return OFF;
    }
    public static final class Scope implements AutoCloseable {
        private final CommandEncoder encoder;private org.lwjgl.vulkan.VkCommandBuffer command;private final String name;private final long id,start,parent,renderFrame,scene;private final int slot,w,h,samples;private boolean ended;
        Scope(CommandEncoder encoder,String name,long id,int slot,long start){this.encoder=encoder;this.name=name;this.id=id;this.slot=slot;this.start=start;
            parent=id==0||scopes.isEmpty()?0:scopes.peek().id;renderFrame=frame;scene=sceneGeneration;w=width;h=height;samples=spp;
            if(id>0)scopes.push(this);
        }
        Scope(org.lwjgl.vulkan.VkCommandBuffer command,String name,long id,int slot,long start){this((CommandEncoder)null,name,id,slot,start);this.command=command;}
        public void close(){
            if(ended)return;ended=true;if(id>0)scopes.remove(this);
            if(command!=null){metrics.recordScope(id,renderFrame,parent,name,w,h,samples,scene,System.nanoTime()-start);if(pool instanceof NativePool nativePool)org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,nativePool.handle(),slot*2+1);return;}
            if(encoder==null)return;
            metrics.recordScope(id,renderFrame,parent,name,w,h,samples,scene,System.nanoTime()-start);
            if(pool!=null && slot>=0)try{encoder.writeTimestamp(pool,slot*2+1);}catch(RuntimeException e){disable();}
        }
    }
    static void externalGpu(String name,long nanos){if(enabled){long id=++serial;metrics.recordScope(id,frame,scopes.isEmpty()?0:scopes.peek().id,name,width,height,spp,sceneGeneration,0);metrics.completeGpu(id,nanos);}}
    static void cpu(String name,long nanos){if(enabled)metrics.recordScope(++serial,frame,scopes.isEmpty()?0:scopes.peek().id,name,width,height,spp,sceneGeneration,nanos);}
    public static void rayWorkload(long frame,int w,int h,int samples,long scene,long[] active,long shadow,long anyHit){workMetrics.record(frame,w,h,samples,scene,active,shadow,anyHit);}
    public static void rayWorkload(long frame,int w,int h,int samples,long scene,long[] active,long shadow,long anyHit,long opaque,long replay,long mismatches){rayWorkload(frame,w,h,samples,scene,active,shadow,anyHit,opaque,replay,mismatches,new long[6][4]);}
    public static void rayWorkload(long frame,int w,int h,int samples,long scene,long[] active,long shadow,long anyHit,long opaque,long replay,long mismatches,long[][] direct){workMetrics.record(frame,w,h,samples,scene,active,shadow,anyHit,opaque,replay,mismatches,direct);var value=workMetrics.sample(frame);for(var observer:workObservers)observer.accept(value);}
    public static void export(Path path)throws IOException{metrics.export(path);com.voxellight.rt.vulkan.VulkanPipelineDiagnostics.export(path.resolveSibling(path.getFileName()+".pipelines.csv"));com.voxellight.rt.vulkan.VulkanPipelineDiagnostics.exportStatus(path.resolveSibling(path.getFileName()+".pipelines-status.json"));String name=path.getFileName().toString();workMetrics.export(path.resolveSibling(name.endsWith(".passes.csv")?name.substring(0,name.length()-11)+".rays.csv":name+".rays.csv"));}
    static String status(){return ", passProfile="+(!enabled?"off":failed?"CPU only":pool==null?"not observed":"delayed GPU timestamps")+", passProfileSamples="+metrics.size()+", passProfileSkipped="+skipped;}
    private static void disable(){failed=true;if(pool!=null){try{pool.close();}catch(RuntimeException ignored){}pool=null;}}
    static void clear(){disable();failed=false;metrics.clear();workMetrics.clear();counterFrames.clear();scopes.clear();java.util.Arrays.fill(pending,false);}
}
