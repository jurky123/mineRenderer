package com.voxellight.adapter;
import com.mojang.blaze3d.systems.*;
import com.voxellight.debug.PassMetrics;
import com.voxellight.world.*;
import java.nio.file.Path;
import java.io.IOException;
/** Delayed timestamps cover the world render region, including native terrain and translucency. */
final class AdaptiveBudget implements AutoCloseable  {
    private final AdaptiveQuality controller=new AdaptiveQuality();
    private final PassMetrics metrics=new PassMetrics(14400);
    private String mode="OFF";
    private GpuPassTimer timer;
    private long frame,start,observed=-1;
    private int query=-1;
    private boolean failed,measured;
    void measured(boolean value) {
        measured=value;
        if(!value&&!controller.enabled())close();
    }
    void enabled(boolean value) {
        failed=false;
        controller.setEnabled(value);
        if(!value)close();
    }
    void ceiling(VisualQuality value) {
        controller.setCeiling(value);
    }
    void target(float value) {
        controller.setTarget(value);
    }
    void begin(String mode) {
        this.mode=mode;
        if((!controller.enabled()&&!measured)||failed)return;
        try {
            var device=RenderSystem.getDevice();
            if(timer==null)timer=new GpuPassTimer(device);
            frame++;
            timer.poll(frame,metrics);
            start=System.nanoTime();
            query=timer.begin(device.createCommandEncoder(),frame);
        }
        catch(RuntimeException e) {
            failed=true;
            close();
        }
    }
    VisualQuality end(int width,int height) {
        if(timer!=null&&start!=0) {
            timer.end(RenderSystem.getDevice().createCommandEncoder(),query);
            metrics.record(frame,"WORLD_REGION_"+mode,width,height,System.nanoTime()-start);
            start=0;
            var sample=metrics.newestGpuSample();
            if(sample!=null&&sample.frame()>observed) {
                observed=sample.frame();
                if(sample.mode().equals("WORLD_REGION_FOUNDATION"))controller.observe(sample.gpuNanos());
            }
        }
        return controller.quality();
    }
    void export(Path path)throws IOException {
        metrics.export(path);
    }
    String status() {
        return ", adaptiveQuality="+(controller.enabled()?failed?"timestamp unavailable":controller.quality().name().toLowerCase():"off")+", gpuWorldTargetMs="+controller.targetMillis()+", gpuWorldEmaMs="+String.format(java.util.Locale.ROOT,"%.2f",controller.measuredMillis());
    }
    @Override public void close() {
        if(timer!=null) {
            timer.close();
            timer=null;
        }
        start=0;
        query=-1;
    }
}
