package com.voxellight.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded CPU submission samples; GPU results may arrive several frames later. */
public final class PassMetrics {
    public record Sample(long frame, String mode, int width, int height, long cpuNanos, Long gpuNanos,
            long scopeId, long parentScopeId, int spp, long sceneGeneration) { }
    private boolean scoped;

    private final int capacity;
    private Sample newestGpu;
    public Sample newestGpuSample(){return newestGpu;}
    private final LinkedHashMap<Long, Sample> samples = new LinkedHashMap<>();

    public PassMetrics(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void record(long frame, String mode, int width, int height, long cpuNanos) {
        samples.put(frame, new Sample(frame, mode, width, height, cpuNanos, null, frame, 0, 0, 0));
        while (samples.size() > capacity) {
            samples.remove(samples.firstEntry().getKey());
        }
    }

    public void recordScope(long scopeId,long renderFrame,long parentScopeId,String mode,int width,int height,int spp,long sceneGeneration,long cpuNanos) {
        scoped=true;
        samples.put(scopeId,new Sample(renderFrame,mode,width,height,cpuNanos,null,scopeId,parentScopeId,spp,sceneGeneration));
        while(samples.size()>capacity)samples.remove(samples.firstEntry().getKey());
    }

    public void completeGpu(long frame, long gpuNanos) {
        if (gpuNanos < 0) {
            return;
        }
        samples.computeIfPresent(frame, (key, sample) -> {
            var completed=new Sample(sample.frame(),sample.mode(),sample.width(),sample.height(),sample.cpuNanos(),gpuNanos,sample.scopeId(),sample.parentScopeId(),sample.spp(),sample.sceneGeneration());
            if(newestGpu==null||completed.scopeId()>newestGpu.scopeId())newestGpu=completed;
            return completed;
        });
    }

    public int size(){return samples.size();}

    public List<Sample> snapshot() {
        return List.copyOf(samples.values());
    }

    public void clear() {
        samples.clear();newestGpu=null;scoped=false;
    }

    public void export(Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("frame,mode,width,height,pass_cpu_submission_ns,pass_gpu_ns"+(scoped?",schema_version,scope_id,parent_scope_id,spp,scene_generation":"")+"\n");
            for (Sample sample : samples.values()) {
                writer.write(sample.frame() + "," + sample.mode() + "," + sample.width() + ","
                        + sample.height() + "," + sample.cpuNanos() + ","
                        + (sample.gpuNanos() == null ? "" : sample.gpuNanos())
                        + (scoped?",2,"+sample.scopeId()+","+sample.parentScopeId()+","+sample.spp()+","+sample.sceneGeneration():"")+"\n");
            }
        }
    }
}
