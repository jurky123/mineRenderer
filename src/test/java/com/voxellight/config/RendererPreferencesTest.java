package com.voxellight.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RendererPreferencesTest {
    @TempDir Path directory;
    @Test void savedSettingsReplayInOrderAndSurviveReload()throws Exception{
        var prefs=new RendererPreferences();
        assertTrue(prefs.record("voxellight preset vulkan_quality",true));
        prefs.record("voxellight water off",true);prefs.record("voxellight quality balanced",true);
        prefs.save(directory.resolve("settings.json"));var loaded=new RendererPreferences();loaded.load(directory.resolve("settings.json"));
        assertEquals(List.of("preset","water","quality"),List.copyOf(loaded.snapshot().keySet()));assertEquals("off",loaded.value("water"));
        prefs.record("voxellight preset performance",true);assertEquals(1,prefs.snapshot().size());
    }
    @Test void accumulationSettingsPersistWithoutResetCommand(){
        var prefs=new RendererPreferences();assertTrue(prefs.record("voxellight rt_accumulate on",true));
        assertTrue(prefs.record("voxellight rt_accumulate spp 512",true));
        assertFalse(prefs.record("voxellight rt_accumulate reset",true));
        assertEquals("512",prefs.value("rt_accumulate spp"));assertEquals("on",prefs.value("rt_accumulate"));
        assertFalse(prefs.record("voxellight pathtrace on",true));assertFalse(prefs.record("voxellight rt_reference on",true));
        assertFalse(prefs.record("voxellight profile on",true));
    }
    @Test void legacyConfigurationMigratesWithoutReplayingRemovedCommands()throws Exception{
        java.nio.file.Files.writeString(directory.resolve("legacy.json"),"{\"preset\":\"rtx_quality\",\"rt_backend\":\"optix_rt\",\"rt_reference spp\":\"512\",\"rt_reference_full scale\":\"2\",\"pathtrace\":\"on\",\"rt_caustics\":\"on\",\"water\":\"off\"}");
        var prefs=new RendererPreferences();prefs.load(directory.resolve("legacy.json"));
        assertEquals(List.of("preset","rt_backend","rt_accumulate spp","water"),List.copyOf(prefs.snapshot().keySet()));
        assertEquals("vulkan_quality",prefs.value("preset"));assertEquals("vulkan_pt",prefs.value("rt_backend"));assertEquals("512",prefs.value("rt_accumulate spp"));
    }
    @Test void replayDoesNotRewritePreferences(){var prefs=new RendererPreferences();assertFalse(prefs.record("voxellight bloom off",false));assertEquals("off",prefs.value("bloom"));assertTrue(prefs.snapshot().isEmpty());}
}
