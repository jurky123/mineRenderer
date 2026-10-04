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
        assertTrue(prefs.record("voxellight preset rtx_quality",true));
        prefs.record("voxellight water off",true);prefs.record("voxellight quality balanced",true);
        prefs.save(directory.resolve("settings.json"));var loaded=new RendererPreferences();loaded.load(directory.resolve("settings.json"));
        assertEquals(List.of("preset","water","quality"),List.copyOf(loaded.snapshot().keySet()));assertEquals("off",loaded.value("water"));
        prefs.record("voxellight preset performance",true);assertEquals(1,prefs.snapshot().size());
    }
    @Test void referenceAndDiagnosticsDoNotEnableThemselvesOnNextLaunch(){
        var prefs=new RendererPreferences();assertFalse(prefs.record("voxellight rt_reference on",true));
        assertFalse(prefs.record("voxellight rt_debug specular",true));assertFalse(prefs.record("voxellight profile on",true));
        assertEquals("on",prefs.value("rt_reference"));
        assertTrue(prefs.record("voxellight rt_reference spp 512",true));prefs.record("voxellight rt_reference reset",true);
        assertEquals("on",prefs.value("rt_reference"));assertEquals("512",prefs.value("rt_reference spp"));
        assertEquals(List.of("rt_reference spp"),List.copyOf(prefs.snapshot().keySet()));
        assertFalse(prefs.record("time set day",true));
    }
    @Test void replayDoesNotRewritePreferences(){var prefs=new RendererPreferences();assertFalse(prefs.record("voxellight bloom off",false));assertEquals("off",prefs.value("bloom"));assertTrue(prefs.snapshot().isEmpty());}
}
