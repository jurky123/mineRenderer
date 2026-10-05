package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.PbrMaterials;
import com.voxellight.world.Material3;
import com.voxellight.world.MaterialOverrides;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;

/** Reload-owned atlas-aligned ID/normal maps. No changes to native vertices or geometry ownership. */
final class PbrAtlas implements AutoCloseable {
    private final GpuTexture[] textures=new GpuTexture[3];
    private final GpuTextureView[] views=new GpuTextureView[3];
    private Material3 waterMedium=Material3.fromProfile("minecraft:block/water_still",PbrMaterials.FALLBACK);
    private int maps,profiles,overflows;private long buildNs,bytes;
    void prepare(){
        if(textures[0]!=null)return;
        long started=System.nanoTime();var mc=Minecraft.getInstance();var manager=mc.getResourceManager();
        var atlas=(TextureAtlas)mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        int w=Math.min(2048,atlas.getTextureView().getWidth(0)),h=Math.min(2048,atlas.getTextureView().getHeight(0));
        var bundled=PbrMaterials.defaults();var materials=bundled;
        try(var reader=manager.getResource(Identifier.fromNamespaceAndPath("voxellight","pbr_materials.json")).orElseThrow().openAsReader()){materials=PbrMaterials.read(reader);}
        catch(Exception e){org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("PBR profiles invalid; bundled defaults retained",e);}
        var curated=new HashMap<String,com.google.gson.JsonObject>();
        try(var in=PbrAtlas.class.getResourceAsStream("/assets/voxellight/materials/vanilla/blocks.json");var reader=new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)){for(var entry:com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().entrySet())curated.put(entry.getKey(),entry.getValue().getAsJsonObject());}catch(java.io.IOException e){throw new IllegalStateException("Bundled Material 3 database unavailable",e);}
        var overrides=new MaterialOverrides();var selectors=new MaterialTextureSelectors(manager);
        for(var entry:manager.listResources("material_overrides",id->id.getPath().endsWith(".json")).entrySet())try(var reader=entry.getValue().openAsReader()){overrides.read(reader);}catch(Exception e){org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("Invalid material override {}",entry.getKey(),e);}
        var user=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("voxellight-materials.json");
        if(java.nio.file.Files.isRegularFile(user))try(var reader=java.nio.file.Files.newBufferedReader(user)){overrides.read(reader);}catch(Exception e){org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("Invalid user material overrides",e);}
        var rows=new LinkedHashMap<List<Integer>,Integer>();var values=new ArrayList<int[]>();
        int[] defaultRow=Material3.fromProfile("",PbrMaterials.FALLBACK).table(PbrMaterials.FALLBACK.packed(255));values.add(defaultRow);rows.put(Arrays.stream(defaultRow).boxed().toList(),0);
        ByteBuffer ids=MemoryUtil.memCalloc(w*h*4).order(ByteOrder.LITTLE_ENDIAN),normal=MemoryUtil.memAlloc(w*h*4),lut=MemoryUtil.memCalloc(256*1280*4).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for(int i=0;i<w*h;i++){normal.put(i*4,(byte)128);normal.put(i*4+1,(byte)128);normal.put(i*4+2,(byte)255);normal.put(i*4+3,(byte)255);}
            for(var resource:manager.listResources("textures",id->id.getPath().endsWith(".png")&&!id.getPath().endsWith("_s.png")&&!id.getPath().endsWith("_n.png")).keySet()) {
                var name=Identifier.fromNamespaceAndPath(resource.getNamespace(),resource.getPath().substring(9,resource.getPath().length()-4));
                var sprite=atlas.getSprite(name);if(!sprite.contents().name().equals(name))continue;
                var fallback=materials.profile(name.toString());
                // Animated maps need atlas animation synchronization; use stable profiles in this phase.
                NativeImage spec=null,norm=null;
                try {
                    if(!sprite.contents().isAnimated()) {
                        var specResource=manager.getResource(Identifier.fromNamespaceAndPath(resource.getNamespace(),resource.getPath().replace(".png","_s.png")));
                        if(specResource.isPresent())try(var in=specResource.get().open()){spec=NativeImage.read(NativeImage.Format.RGBA,in);}
                        var normalResource=manager.getResource(Identifier.fromNamespaceAndPath(resource.getNamespace(),resource.getPath().replace(".png","_n.png")));
                        if(normalResource.isPresent())try(var in=normalResource.get().open()){norm=NativeImage.read(NativeImage.Format.RGBA,in);}
                    }
                    if(spec!=null||norm!=null)maps++;
                    int x0=Math.clamp(Math.round(sprite.getU0()*w),0,w),x1=Math.clamp(Math.round(sprite.getU1()*w),0,w);
                    int y0=Math.clamp(Math.round(sprite.getV0()*h),0,h),y1=Math.clamp(Math.round(sprite.getV1()*h),0,h);
                    var preset=Material3.fromProfile(name.toString(),fallback);if(curated.containsKey(name.toString()))preset=Material3.read(curated.get(name.toString()),preset);
                    if(!fallback.equals(bundled.profile(name.toString())))preset=Material3.fromProfile(name.toString(),fallback);
                    var authored=overrides.resolve(name.toString(),preset,selector->selectors.matches(name,selector));if(name.toString().equals("minecraft:block/water_still"))waterMedium=authored;var spriteProfiles=new HashMap<Integer,Integer>();
                    for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
                        int n=norm==null?0xff8080ff:norm.getPixel(Math.min(norm.getWidth()-1,(x-x0)*norm.getWidth()/Math.max(1,x1-x0)),Math.min(norm.getHeight()-1,(y-y0)*norm.getHeight()/Math.max(1,y1-y0)));
                        int s=spec==null?0:spec.getPixel(Math.min(spec.getWidth()-1,(x-x0)*spec.getWidth()/Math.max(1,x1-x0)),Math.min(spec.getHeight()-1,(y-y0)*spec.getHeight()/Math.max(1,y1-y0)));
                        int ao=n&255,packed=spec==null?fallback.packed(ao):((s>>16)&255)|(((s>>8)&255)<<8)|((s&255)<<16)|(ao<<24);
                        var m3=authored;Integer index=spriteProfiles.get(packed);if(index==null){
                        if(spec!=null)m3=m3.withLabPbr(packed);
                        else packed=new PbrMaterials.Profile(m3.perceptualRoughness(),m3.f0(),m3.conductor(),m3.porosity()).packed(ao);
                        int[] row=m3.table(packed);var key=Arrays.stream(row).boxed().toList();index=rows.get(key);if(index==null){if(values.size()>=65536){overflows++;index=0;}else{index=values.size();rows.put(key,index);values.add(row);}}
                        spriteProfiles.put(spec==null?fallback.packed(ao):((s>>16)&255)|(((s>>8)&255)<<8)|((s&255)<<16)|(ao<<24),index);}
                        int offset=(y*w+x)*4;ids.putShort(offset,(short)(int)index);ids.put(offset+2,(byte)(spec==null?(authored.emission()<0?0:Math.round(authored.emission()*254)):(s>>>24)==255?0:s>>>24));
                        ids.put(offset+3,(byte)((spec!=null||authored.emission()>=0?4:0)|(authored.type()==Material3.Type.WATER?2:authored.transmission()>0&&authored.type()!=Material3.Type.DIFFUSE_TRANSMISSION?1:0)));
                        normal.put(offset,(byte)(n>>16));normal.put(offset+1,(byte)(n>>8));normal.put(offset+2,(byte)ao);normal.put(offset+3,(byte)((n>>>24)&255));
                    }
                }catch(Exception e){org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("PBR texture fallback: {}",name,e);}
                finally{if(spec!=null)spec.close();if(norm!=null)norm.close();}
            }
            for(int i=0;i<values.size();i++)for(int plane=0;plane<5;plane++)lut.putInt((plane*65536+i)*4,values.get(i)[plane]);profiles=values.size();bytes=(long)w*h*8+256*1280*4;
            ByteBuffer[] data={ids,normal,lut};var d=RenderSystem.getDevice();var encoder=d.createCommandEncoder();
            for(int i=0;i<3;i++){int tw=i==2?256:w,th=i==2?1280:h;textures[i]=d.createTexture("VoxelLight PBR atlas "+i,GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,tw,th,1,1);views[i]=d.createTextureView(textures[i]);encoder.writeToTexture(textures[i],data[i],0,0,0,0,tw,th);}
            buildNs=System.nanoTime()-started;
        }finally{MemoryUtil.memFree(ids);MemoryUtil.memFree(normal);MemoryUtil.memFree(lut);}
    }
    float[] waterMedium(){return waterMedium.mediumTable();}
    GpuTextureView view(int i){return views[i];}
    String status(){return ", pbrProfiles="+profiles+", labPbrSprites="+maps+", pbrPaletteOverflow="+overflows+", pbrAtlasBytes="+bytes+", pbrAtlasBuildNs="+buildNs;}
    @Override public void close(){for(int i=0;i<3;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(textures[i]!=null){textures[i].close();textures[i]=null;}}maps=profiles=overflows=0;bytes=0;}
}
