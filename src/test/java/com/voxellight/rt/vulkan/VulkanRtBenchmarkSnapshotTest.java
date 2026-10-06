package com.voxellight.rt.vulkan;
import com.voxellight.world.SectionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VulkanRtBenchmarkSnapshotTest {
    @Test void staticSnapshotReservesDynamicHeadroomAndSelectsWholeNearestPagesDeterministically(){
        long mib=1024L*1024;var near=new SectionKey(0,0,0);var middle=new SectionKey(1,0,0);var far=new SectionKey(2,0,0);
        var sizes=new LinkedHashMap<SectionKey,Long>();sizes.put(far,8*mib);sizes.put(middle,32*mib);sizes.put(near,32*mib);
        assertEquals(List.of(near,far),VulkanRtScene.benchmarkSelection(sizes,8,8,8));
        var shuffled=new LinkedHashMap<SectionKey,Long>();shuffled.put(near,32*mib);shuffled.put(middle,32*mib);shuffled.put(far,8*mib);
        assertEquals(VulkanRtScene.benchmarkSelection(sizes,8,8,8),VulkanRtScene.benchmarkSelection(shuffled,8,8,8));
        assertEquals(List.of(),VulkanRtScene.benchmarkSelection(Map.of(near,61*mib),8,8,8));
    }
    @Test void snapshotSignatureIgnoresInsertionOrderButDetectsMissingPagesAndVersionChanges(){
        var a=new com.voxellight.adapter.RtGeometryStream.Section(new SectionKey(0,0,0),7,new byte[120]);
        var b=new com.voxellight.adapter.RtGeometryStream.Section(new SectionKey(1,0,0),8,new byte[120]);
        assertEquals(VulkanRtScene.benchmarkSignature(List.of(a,b)),VulkanRtScene.benchmarkSignature(List.of(b,a)));
        assertNotEquals(VulkanRtScene.benchmarkSignature(List.of(a,b)),VulkanRtScene.benchmarkSignature(List.of(a)));
        var changed=new com.voxellight.adapter.RtGeometryStream.Section(b.key(),9,b.triangles());
        assertNotEquals(VulkanRtScene.benchmarkSignature(List.of(a,b)),VulkanRtScene.benchmarkSignature(List.of(a,changed)));
    }
    @Test void missingCompilerStatisticsAreExportedAsUnavailableInsteadOfZero(@TempDir Path directory)throws Exception{
        VulkanPipelineDiagnostics.unavailable("test-unavailable","unsupported device feature");var file=directory.resolve("status.json");VulkanPipelineDiagnostics.exportStatus(file);
        var report=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();var stage=report.getAsJsonObject("stages").getAsJsonObject("test-unavailable");
        assertFalse(stage.get("captureEnabled").getAsBoolean());assertEquals("unsupported device feature",stage.get("reason").getAsString());assertTrue(report.get("limits").getAsString().contains("missing values are not zero"));
    }
}
