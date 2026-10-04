package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.voxellight.world.*;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class PhysicalTransportTest {
 @TempDir Path temporary;
 @Test void productionNativeBsdfPassesMonteCarloRegressions()throws Exception{
  Path executable=temporary.resolve("bsdf-test"),log=temporary.resolve("results.log");
  var compile=new ProcessBuilder("g++","-std=c++17","-O2","native/rt/tests/bsdf_test.cpp","-o",executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();assertEquals(0,compile.waitFor(),Files.readString(log));
  var run=new ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();assertEquals(0,run.waitFor(),Files.readString(log));assertTrue(Files.readString(log).contains("regressions passed"));
 }
 @Test void materialOverridesHaveExplicitSourcePrecedence(){
  var rules=new MaterialOverrides();rules.read(new StringReader("[{\"material\":\"minecraft:*\",\"roughness\":0.8},{\"material\":\"#minecraft:stone\",\"roughness\":0.7},{\"material\":\"block=minecraft:stone\",\"roughness\":0.6},{\"material\":\"minecraft:block/stone\",\"type\":\"coated_diffuse\",\"roughness\":0.3,\"coat_weight\":0.7}]"));
  var base=Material3.fromProfile("minecraft:block/stone",PbrMaterials.FALLBACK);var result=rules.resolve("minecraft:block/stone",base,s->true);assertEquals(Material3.Type.COATED_DIFFUSE,result.type());assertEquals(.09,result.microfacetAlpha(),1e-6);assertEquals(.7f,result.coatWeight());assertEquals(.8f,rules.resolve("minecraft:block/unlisted",base,s->false).perceptualRoughness());
 }
 @Test void waterMediumAndLabPbrRoughnessKeepUnits(){
  var water=Material3.fromProfile("minecraft:block/water_still",PbrMaterials.FALLBACK);assertEquals(Material3.Type.WATER,water.type());assertEquals(1.333f,water.ior());assertTrue(water.scatteringB()>0&&water.absorptionR()>water.absorptionB());
  for(int s=0;s<256;s++){float perceptual=1-s/255f;assertEquals(perceptual*perceptual,PbrMaterials.linearRoughness(s),1e-6);}
  assertThrows(IllegalArgumentException.class,()->Material3.read(com.google.gson.JsonParser.parseString("{\"ior\":0}").getAsJsonObject(),water));
 }
 @Test void labPbrChannelsOverrideCuratedMetalAndKeepMediumQuantization(){
  var gold=Material3.fromProfile("minecraft:block/gold_block",new PbrMaterials.Profile(.3f,.04f,231,0));
  var dielectric=gold.withLabPbr(128|(10<<8)|(64<<16));assertEquals(Material3.Type.ROUGH_DIFFUSE,dielectric.type());assertEquals(10/255f,dielectric.f0());assertEquals(1,dielectric.porosity());assertEquals(PbrMaterials.linearRoughness(128),dielectric.microfacetAlpha());
  for(int metal=230;metal<256;metal++){var m=dielectric.withLabPbr(metal<<8);assertEquals(Material3.Type.CONDUCTOR,m.type());assertEquals(metal<=237?metal:255,m.conductor());}
  var foliage=dielectric.withLabPbr(128|(10<<8)|(255<<16));assertEquals(Material3.Type.DIFFUSE_TRANSMISSION,foliage.type());assertEquals(.5f,foliage.transmission());
  var water=Material3.fromProfile("minecraft:block/water_still",PbrMaterials.FALLBACK);int[] t=water.table(0);float[] medium=water.mediumTable();assertEquals((t[2]&255)*8/255f,medium[0]);assertEquals((t[1]>>>8&255)*3/255f,medium[7]);
 }
 @Test void curatedVanillaDatabaseHasBroadCoverage()throws Exception{
  try(var in=getClass().getResourceAsStream("/assets/voxellight/materials/vanilla/blocks.json")){var json=com.google.gson.JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();assertTrue(json.size()>1200);for(String name:new String[]{"gold_block","copper_block","frosted_ice_0","oak_leaves","water_still","white_wool"})assertTrue(json.has("minecraft:block/"+name),name);}
 }
}
