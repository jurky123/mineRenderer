package com.voxellight.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded CPU submission samples; GPU results may arrive several frames later. */
public final class PassMetrics {
    public record Sample(long frame, String mode, int width, int height, long cpuNanos, Long gpuNanos) { }

    private final int capacity;
    private final LinkedHashMap<Long, Sample> samples = new LinkedHashMap<>();

    public PassMetrics(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void record(long frame, String mode, int width, int height, long cpuNanos) {
        samples.put(frame, new Sample(frame, mode, width, height, cpuNanos, null));
        while (samples.size() > capacity) {
            samples.remove(samples.firstEntry().getKey());
        }
    }

    public void completeGpu(long frame, long gpuNanos) {
        if (gpuNanos < 0) {
            return;
        }
        samples.computeIfPresent(frame, (key, sample) -> new Sample(sample.frame(), sample.mode(),
                sample.width(), sample.height(), sample.cpuNanos(), gpuNanos));
    }

    public int size(){return samples.size();}

    public List<Sample> snapshot() {
        return List.copyOf(samples.values());
    }

    public void clear() {
        samples.clear();
    }

    public void export(Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("frame,mode,width,height,pass_cpu_submission_ns,pass_gpu_ns\n");
            for (Sample sample : samples.values()) {
                writer.write(sample.frame() + "," + sample.mode() + "," + sample.width() + ","
                        + sample.height() + "," + sample.cpuNanos() + ","
                        + (sample.gpuNanos() == null ? "" : sample.gpuNanos()) + "\n");
            }
        }
    }
}
