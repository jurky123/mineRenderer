package com.voxellight.rt.vulkan;

import com.voxellight.adapter.RenderPassProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Diagnostic CPU-recorded AS commands; these counts do not imply GPU completion. */
public final class VulkanRtAsWorkProfile {
    private static final boolean ENABLED=Boolean.getBoolean("voxellight.rt.workProfile");
    private static final LinkedHashMap<Long,long[]> COUNTS=new LinkedHashMap<>();
    private static long dropped;
    private VulkanRtAsWorkProfile() {}
    public static void record(int type,int mode) {
        if (!ENABLED) return;
        synchronized (COUNTS) {
        long frame=RenderPassProfile.frameId();
        if (!COUNTS.containsKey(frame)&&COUNTS.size()>=250_000) { dropped++;return; }
        int index=(type==VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR?2:0)+(mode==VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR?1:0);
        COUNTS.computeIfAbsent(frame,ignored->new long[4])[index]++;
        }
    }
    public static void export(Path path) throws IOException {
        if (!ENABLED) return;
        synchronized (COUNTS) {
        try(var writer=Files.newBufferedWriter(path)) {
            writer.write("frame,blas_builds_recorded,blas_updates_recorded,tlas_builds_recorded,tlas_updates_recorded\n");
            for(var entry:COUNTS.entrySet()) { writer.write(Long.toString(entry.getKey()));for(long count:entry.getValue())writer.write(","+count);writer.write("\n"); }
        }
        Files.writeString(path.resolveSibling(path.getFileName()+".status.json"),"{\"enabled\":true,\"dropped_events\":"+dropped+",\"semantics\":\"CPU recording frame; not GPU completion\"}");
        }
    }
}
