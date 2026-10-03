package com.voxellight.world;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightMaterialsTest {
    @Test void bundledProfilesGivePlacedAndHeldBlocksTheSameAuthoredColor() {
        var profiles=LightMaterials.defaults();
        var torch=profiles.color("minecraft:torch");var soul=profiles.color("minecraft:soul_torch");
        assertTrue(torch.red()>torch.blue());assertTrue(soul.blue()>soul.red());
        assertEquals(torch,profiles.color("minecraft:wall_torch"));
        assertEquals(LightMaterials.FALLBACK,profiles.color("example:soul_torch"),"Full IDs, not name substrings");
        assertTrue(profiles.size()>20);
    }
    @Test void packsCanAuthorModdedColorsAndInvalidDataIsRejectedAtomically() {
        var profiles=LightMaterials.read(new StringReader("{\"example:lamp\":[0.1,0.4,0.9]}"));
        assertEquals(new LightMaterials.Color(.1f,.4f,.9f),profiles.color("example:lamp"));
        for(String json:new String[]{"{\"bad ID\":[1,1,1]}","{\"example:lamp\":[1,2,1]}","{\"example:lamp\":[1,1]}","{\"example:lamp\":[1e100,0,1]}"})
            assertThrows(RuntimeException.class,()->LightMaterials.read(new StringReader(json)));
        assertThrows(IllegalArgumentException.class,()->new LightMaterials.Color(Float.NaN,0,1));
    }
    @Test void reservingADynamicSlotKeepsTheCombinedLightBudgetBoundedAndRestoresIt() {
        var volume=new LocalLightVolume();var key=new SectionKey(0,4,0);
        var emitters=new java.util.ArrayList<LocalLightVolume.Emitter>();
        for(int i=0;i<32;i++)emitters.add(new LocalLightVolume.Emitter(i%16,64,i/16,14,1,.6f,.2f));
        volume.rebuild(java.util.List.of(new LocalLightVolume.Section(key,new int[4096],emitters)),key);
        assertEquals(16,volume.select(0,64,0,.1f).size());
        assertEquals(15,volume.select(0,64,0,.1f,15).size());
        assertEquals(16,volume.select(0,64,0,.1f).size());
        assertEquals(0,volume.select(0,64,0,.1f,0).size());
        assertThrows(IllegalArgumentException.class,()->volume.select(0,64,0,.1f,17));
    }
}
