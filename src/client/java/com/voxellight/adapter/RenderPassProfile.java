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
    public static void setEnabled(boolean value){enabled=value;}
    private static float period;
    private static long frame, serial, skipped;
    private static final long[] ids=new long[SLOTS], frames=new long[SLOTS];
    private static final boolean[] pending=new boolean[SLOTS];
    private static final PassMetrics metrics=new PassMetrics(14400);
    private RenderPassProfile() { }
    public static void nextFrame() {
        frame++;
        if(pool==null)return;
        try {
            for(int i=0;i<SLOTS;i++)if(pending[i] && frame-frames[i]>=2) {
                var values=pool.getValues(i*2,2);
                if(values[0].isPresent() && values[1].isPresent()) {
                    long ticks=values[1].getAsLong()-values[0].getAsLong();
                    double ns=ticks*(double)period;
                    if(ticks>=0 && Double.isFinite(ns) && ns<Long.MAX_VALUE)metrics.completeGpu(ids[i],Math.round(ns));
                    pending[i]=false;
                }
            }
        } catch(RuntimeException e){disable();}
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
        private final CommandEncoder encoder;private org.lwjgl.vulkan.VkCommandBuffer command;private final String name;private final long id,start;private final int slot;
        Scope(CommandEncoder encoder,String name,long id,int slot,long start){this.encoder=encoder;this.name=name;this.id=id;this.slot=slot;this.start=start;}
        Scope(org.lwjgl.vulkan.VkCommandBuffer command,String name,long id,int slot,long start){this((CommandEncoder)null,name,id,slot,start);this.command=command;}
        public void close(){
            if(command!=null){metrics.record(id,name,0,0,System.nanoTime()-start);if(pool instanceof NativePool nativePool)org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,nativePool.handle(),slot*2+1);return;}
            if(encoder==null)return;
            metrics.record(id,name,0,0,System.nanoTime()-start);
            if(pool!=null && slot>=0)try{encoder.writeTimestamp(pool,slot*2+1);}catch(RuntimeException e){disable();}
        }
    }
    static void externalGpu(String name,long nanos){if(enabled){long id=++serial;metrics.record(id,name,0,0,0);metrics.completeGpu(id,nanos);}}
    static void cpu(String name,long nanos){if(enabled)metrics.record(++serial,name,0,0,nanos);}
    public static void export(Path path)throws IOException{metrics.export(path);}
    static String status(){return ", passProfile="+(!enabled?"off":failed?"CPU only":pool==null?"not observed":"delayed GPU timestamps")+", passProfileSamples="+metrics.size()+", passProfileSkipped="+skipped;}
    private static void disable(){failed=true;if(pool!=null){try{pool.close();}catch(RuntimeException ignored){}pool=null;}}
    static void clear(){disable();failed=false;metrics.clear();java.util.Arrays.fill(pending,false);}
}
