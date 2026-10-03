package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class PbrMaterialsTest {
    @Test void vanillaMaterialsHaveDistinctPhysicalProfilesAndSafeFallback() {
        var profiles=PbrMaterials.defaults();
        assertEquals(230,profiles.profile("minecraft:block/iron_block").metal());
        assertEquals(231,profiles.profile("minecraft:block/gold_block").metal());
        assertTrue(profiles.profile("minecraft:block/stone").roughness()>profiles.profile("minecraft:block/ice").roughness());
        assertEquals(PbrMaterials.FALLBACK,profiles.profile("mod:unknown"));
    }
    @Test void packOverridesUseExactTextureIdsAndRejectInvalidParameters() {
        var profiles=PbrMaterials.read(new StringReader("{\"custom:block/tile\":{\"roughness\":0.2,\"f0\":0.04,\"metal\":0,\"porosity\":0.5}}"));
        assertEquals(.2f,profiles.profile("custom:block/tile").roughness());
        assertEquals(PbrMaterials.FALLBACK,profiles.profile("custom:block/tile_top"));
        assertThrows(IllegalArgumentException.class,()->new PbrMaterials.Profile(Float.NaN,.04f,0,0));
        assertThrows(IllegalArgumentException.class,()->new PbrMaterials.Profile(.5f,1,0,0));
        assertThrows(IllegalArgumentException.class,()->new PbrMaterials.Profile(.5f,.04f,240,0));
    }
    @Test void labPbrChannelsKeepMetalPorosityAoAndIgnoredEmissionDistinct() {
        int packed=new PbrMaterials.Profile(.25f,.04f,231,.5f).packed(127);
        assertEquals(191,packed&255);assertEquals(231,(packed>>>8)&255);
        assertEquals(32,(packed>>>16)&255);assertEquals(127,packed>>>24);
        assertEquals(1,PbrMaterials.linearRoughness(0));assertEquals(0,PbrMaterials.linearRoughness(255));
        assertEquals(1,PbrMaterials.emission(254));assertEquals(0,PbrMaterials.emission(255));
    }
    @Test void paletteSupportsSixteenBitIdsDeduplicatesAndBoundsOverflow() {
        var palette=new PbrMaterials.Palette();
        assertEquals(0,palette.id(PbrMaterials.FALLBACK.packed(255)));
        for(int i=0;i<65535;i++)assertEquals(i+1,palette.id(i));
        assertEquals(301,palette.id(300));assertEquals(300,palette.value(301));
        assertEquals(65536,palette.size());assertEquals(0,palette.id(1000000));
        assertEquals(1,palette.overflow());assertEquals(301,palette.id(300));
    }
}
