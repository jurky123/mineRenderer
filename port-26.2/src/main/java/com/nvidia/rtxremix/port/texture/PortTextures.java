/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.opengl.GlTexture;
import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;

/** Render-thread texture/material owner. Resource reloads invalidate scene references first. */
public final class PortTextures {
    public enum Kind { SOLID,CUTOUT,BLEND,GLASS,STAINED_GLASS,WATER,TEXT,GEL,DECAL }
    public record Key(SpritePixels pixels,Kind kind) {}
    public record Region(SpritePixels pixels,float u0,float u1,float v0,float v1) {}
    private record SourceVersion(AbstractTexture texture,long revision){}
    public static Object version(Identifier id){var atlas=atlases.get(id);if(atlas!=null)return atlas;var texture=Minecraft.getInstance().getTextureManager().getTexture(id);return new SourceVersion(texture,sourceRevisions.getOrDefault(texture,0L));}
    private record NativeSheet(long[] textures,Map<Kind,Long> materials) {}
    private static volatile Map<Identifier,List<Region>> atlases=Map.of();
    private static final Map<Identifier,SpritePixels> standalone=new HashMap<>();
    private static final Map<Identifier,AbstractTexture> standaloneSources=new HashMap<>();
    private static final Map<AbstractTexture,Long> sourceRevisions=new IdentityHashMap<>();
    private static final Map<Identifier,Long> sampledRevisions=new HashMap<>();
    private static final Set<SpritePixels> retired=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<SpritePixels,NativeSheet> nativeSheets=new IdentityHashMap<>();
    private static final Map<SpriteContents,SpritePixels> sprites=new IdentityHashMap<>();
    private static final Map<SpriteContents.AnimationState,SpritePixels> animations=new IdentityHashMap<>();
    private static final Map<SpriteContents.AnimationState,SpriteAnimationAccess> animationInfo=new IdentityHashMap<>();
    private static final Set<SpritePixels> dirty=Collections.newSetFromMap(new IdentityHashMap<>());
    private static LabPBRConverter.VariantCache variants;
    private static PortWater water;
    public static long animationPatches;
    private PortTextures() {}
    public static void atlas(TextureAtlas atlas,SpriteLoader.Preparations prep) {
        if(variants==null) variants=LabPBRConverter.buildVariantCache(Minecraft.getInstance().getResourceManager());
        Identifier id=atlas.location();
        if(atlases.containsKey(id)) {
            PortRenderer.clearCapturedScene();releaseNative();standalone.clear();standaloneSources.clear();sourceRevisions.clear();sampledRevisions.clear();
            Set<SpritePixels> old=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Region region:atlases.get(id)) old.add(region.pixels());
            sprites.entrySet().removeIf(e->old.contains(e.getValue()));
            animations.entrySet().removeIf(e->old.contains(e.getValue()));
            animationInfo.entrySet().removeIf(e->!sprites.containsKey(e.getValue().remix$contents()));
            dirty.removeAll(old);
            variants=LabPBRConverter.buildVariantCache(Minecraft.getInstance().getResourceManager());
        }
        List<Region> regions=new ArrayList<>();
        for(TextureAtlasSprite sprite:prep.regions().values()) {
            SpriteContents c=sprite.contents();
            NativeImage image=((SpriteContentsAccess)c).remix$image();
            SpritePixels pixels=new SpritePixels(c.name(),image,c.width(),c.height(),variants);
            sprites.put(c,pixels);
            regions.add(new Region(pixels,sprite.getU0(),sprite.getU1(),sprite.getV0(),sprite.getV1()));
        }
        var next=new HashMap<>(atlases);next.put(id,List.copyOf(regions));atlases=Map.copyOf(next);
        org.slf4j.LoggerFactory.getLogger("Remix26Port").info("Captured texture atlas {}: {} sprites",id,regions.size());
    }
    public static void animation(SpriteContents contents,SpriteContents.AnimationState state) {
        SpritePixels pixels=sprites.get(contents);if(pixels!=null && state!=null) animations.put(state,pixels);
    }
    public static void animationInfo(SpriteContents contents,List<?> frames,boolean interpolate) {
        SpritePixels pixels=sprites.get(contents);
        if(pixels!=null) configure(pixels,frames,interpolate);
    }
    public static void animationInfo(SpriteContents.AnimationState state,SpriteAnimationAccess info) {
        if(state!=null) animationInfo.put(state,info);
        animationInfo(info.remix$contents(),info.remix$frames(),info.remix$interpolate());
    }
    public static void tick(SpriteContents.AnimationState state,int frame,int subFrame) {
        SpriteAnimationAccess a=animationInfo.get(state);if(a!=null)
            tick(state,a.remix$contents(),a.remix$frames(),a.remix$interpolate(),frame,subFrame);
    }
    private static void configure(SpritePixels pixels,List<?> frames,boolean interpolate) {
        pixels.frames=new int[frames.size()];pixels.times=new int[frames.size()];pixels.interpolate=interpolate;
        for(int i=0;i<frames.size();i++) {
            AnimationFrameAccess f=(AnimationFrameAccess)frames.get(i);pixels.frames[i]=f.remix$index();pixels.times[i]=f.remix$time();
        }
        pixels.currentFrame=pixels.frames[0];pixels.nextFrame=pixels.frames[(1)%frames.size()];pixels.duration=pixels.times[0];
    }
    public static void tick(SpriteContents.AnimationState state,SpriteContents contents,List<?> frames,boolean interpolate,int frame,int subFrame) {
        SpritePixels pixels=sprites.get(contents);if(pixels==null) return;
        if(pixels.frames.length!=frames.size()) configure(pixels,frames,interpolate);
        else if(!animations.containsKey(state)) configure(pixels,frames,interpolate);
        animations.put(state,pixels);
        int index=pixels.frames[frame];
        if(pixels.currentFrame!=index || interpolate && pixels.subFrame!=subFrame) {
            pixels.currentFrame=index;pixels.nextFrame=pixels.frames[(frame+1)%pixels.frames.length];
            pixels.duration=pixels.times[frame];pixels.subFrame=subFrame;pixels.revision++;dirty.add(pixels);
        }
    }
    public static Region region(Identifier atlas,float u,float v) {
        List<Region> list=atlases.get(atlas);if(list==null) return null;
        for(Region r:list) if(u>=r.u0()&&u<=r.u1()&&v>=r.v0()&&v<=r.v1()) return r;
        return null;
    }
    public static SpritePixels standalone(Identifier id) {
        var texture=Minecraft.getInstance().getTextureManager().getTexture(id);
        long revision=sourceRevisions.getOrDefault(texture,0L);
        if(standalone.containsKey(id) && standaloneSources.get(id)==texture && sampledRevisions.getOrDefault(id,-1L)==revision) return standalone.get(id);
        NativeImage image=null;boolean owned=false;
        try {
            if(texture instanceof DynamicTexture d) image=d.getPixels();
            else if(texture instanceof ReloadableTexture r) {image=r.loadContents(Minecraft.getInstance().getResourceManager()).image();owned=true;}
            else {image=readGpuTexture(texture);owned=true;}
            if(image==null) throw new IllegalStateException("Unsupported texture source: "+id+" ("+texture.getClass()+")");
            if(variants==null) variants=LabPBRConverter.buildVariantCache(Minecraft.getInstance().getResourceManager());
            String path=id.getPath();Identifier sprite=id.withPath(path.startsWith("textures/")&&path.endsWith(".png")?path.substring(9,path.length()-4):path);
            SpritePixels pixels=new SpritePixels(sprite,image,image.getWidth(),image.getHeight(),variants);
            SpritePixels old=standalone.put(id,pixels);if(old!=null) retired.add(old);
            standaloneSources.put(id,texture);sampledRevisions.put(id,revision);return pixels;
        } catch(java.io.IOException e) {throw new java.io.UncheckedIOException(e);}
        finally {if(owned&&image!=null) image.close();}
    }
    /** Font sheets have no retained CPU image in 26.2. Read only on first use or glyph upload. */
    private static NativeImage readGpuTexture(AbstractTexture source) {
        if(!(source.getTexture() instanceof GlTexture texture)) throw new IllegalStateException("Expected GL texture: "+source);
        int width=texture.getWidth(0),height=texture.getHeight(0);
        ByteBuffer data=MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width,height),4));
        int packBuffer=org.lwjgl.opengl.GL45.glGetInteger(org.lwjgl.opengl.GL45.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] packNames={org.lwjgl.opengl.GL45.GL_PACK_ALIGNMENT,org.lwjgl.opengl.GL45.GL_PACK_ROW_LENGTH,
                org.lwjgl.opengl.GL45.GL_PACK_SKIP_ROWS,org.lwjgl.opengl.GL45.GL_PACK_SKIP_PIXELS,
                org.lwjgl.opengl.GL45.GL_PACK_IMAGE_HEIGHT,org.lwjgl.opengl.GL45.GL_PACK_SKIP_IMAGES};
        int[] saved=new int[packNames.length];for(int i=0;i<saved.length;i++) saved[i]=org.lwjgl.opengl.GL45.glGetInteger(packNames[i]);
        try {
            org.lwjgl.opengl.GL45.glBindBuffer(org.lwjgl.opengl.GL45.GL_PIXEL_PACK_BUFFER,0);
            // Minecraft readback/upload passes can leave a non-default pack stride/offset.
            for(int name:packNames) org.lwjgl.opengl.GL45.glPixelStorei(name,name==org.lwjgl.opengl.GL45.GL_PACK_ALIGNMENT?1:0);
            org.lwjgl.opengl.GL45.glGetTextureImage(texture.glId(),0,org.lwjgl.opengl.GL45.GL_RGBA,org.lwjgl.opengl.GL45.GL_UNSIGNED_BYTE,data);
            int error=org.lwjgl.opengl.GL45.glGetError();if(error!=0) throw new IllegalStateException("Texture snapshot GL error="+error+" source="+source);
            NativeImage image=new NativeImage(width,height,true);
            boolean coverage=source instanceof net.minecraft.client.gui.font.FontTexture && texture.getFormat()==com.mojang.blaze3d.GpuFormat.R8_UNORM;
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                int i=(y*width+x)*4,r=data.get(i)&255,g=data.get(i+1)&255,b=data.get(i+2)&255,a=data.get(i+3)&255;
                image.setPixel(x,y,coverage?(r<<24|0xffffff):(a<<24|r<<16|g<<8|b));
            }
            if(Boolean.getBoolean("remix.port.inputTrace") && source instanceof net.minecraft.client.gui.font.FontTexture) {
                String proof=System.getProperty("remix.port.proofDirectory");
                if(proof!=null) try {
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of(proof));
                    image.writeToFile(java.nio.file.Path.of(proof,"glyph-snapshot.png"));
                } catch(java.io.IOException e){org.slf4j.LoggerFactory.getLogger("Remix26Port").warn("Cannot save optional glyph proof",e);}
            }
            return image;
        } finally {
            for(int i=0;i<saved.length;i++) org.lwjgl.opengl.GL45.glPixelStorei(packNames[i],saved[i]);
            org.lwjgl.opengl.GL45.glBindBuffer(org.lwjgl.opengl.GL45.GL_PIXEL_PACK_BUFFER,packBuffer);MemoryUtil.memFree(data);
        }
    }
    public static void changed(AbstractTexture source) {if(standaloneSources.containsValue(source)) sourceRevisions.put(source,sourceRevisions.getOrDefault(source,0L)+1);}
    public static long waterMediumMaterial(){
        return water().medium();
    }
    private static PortWater water(){if(water==null)water=new PortWater(PortRenderer.api(),Minecraft.getInstance().gameDirectory.toPath());return water;}
    /** Called after previous-frame feature meshes are removed, before publishing replacements. */
    public static void retireUnused() {
        for(SpritePixels pixels:retired) {
            NativeSheet sheet=nativeSheets.remove(pixels);dirty.remove(pixels);
            if(sheet!=null) release(sheet);
        }
        retired.clear();
    }
    public static Key key(SpritePixels pixels,Kind kind) {
        String path=pixels.id.getPath();
        if(path.endsWith("water_still")||path.endsWith("water_flow")||path.endsWith("water_overlay"))return new Key(pixels,Kind.WATER);
        if(kind==Kind.BLEND) {
            if(BlockMaterialDefaults.gel(pixels.id)!=null)kind=Kind.GEL;
            else if(path.endsWith("/glass")||path.endsWith("/glass_pane_top")) kind=Kind.GLASS;
            else if(path.contains("stained_glass")||path.endsWith("/ice")) kind=Kind.STAINED_GLASS;
        }
        return new Key(pixels,kind);
    }
    public static long material(Key key) {
        if(key.kind()==Kind.WATER)return water().surface();
        SpritePixels p=key.pixels();NativeSheet sheet=nativeSheets.computeIfAbsent(p,PortTextures::upload);
        return sheet.materials().computeIfAbsent(key.kind(),kind->createMaterial(p,sheet.textures(),kind));
    }
    private static NativeSheet upload(SpritePixels p) {
        long[] handles=new long[8];RemixAPI api=PortRenderer.api();
        for(int i=0;i<handles.length;i++) if(p.channels[i]!=null) {
            byte[] bytes=p.frame(i);ByteBuffer data=MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            try { handles[i]=api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(hash(p,i,0))
                    .size(p.frameWidth(i),p.frameHeight(i)).pixelData(data).format(format(i)).purpose(purpose(i)).build()); }
            finally {MemoryUtil.memFree(data);}
        }
        return new NativeSheet(handles,new EnumMap<>(Kind.class));
    }
    private static long createMaterial(SpritePixels p,long[] t,Kind kind) {
        var m=MaterialInfo.builder().hash(hash(p,20+kind.ordinal(),0)).filterMode(0).albedoTexture(t[0]);
        if(t[1]!=0) m.normalTexture(t[1]);
        if(t[5]!=0){LampAppearance.observe(p.id,p.channels[5]);m.emissiveTexture(t[5]).emissiveIntensity(LampAppearance.surfaceIntensity(p.id));}
        if(kind==Kind.GEL){
            var gel=BlockMaterialDefaults.gel(p.id);
            return PortRenderer.api().createMaterial(m.translucentExt(MaterialInfoTranslucentEXT.builder()
                    .refractiveIndex(gel.ior()).transmittanceColor(gel.red(),gel.green(),gel.blue())
                    .transmittanceMeasurementDistance(gel.distance()).useDiffuseLayer(true).build()).build());
        }
        if(kind==Kind.GLASS||kind==Kind.STAINED_GLASS) {
            var trans=MaterialInfoTranslucentEXT.builder().refractiveIndex(1.52f)
                    .transmittanceColor(0.97f,0.97f,0.97f).transmittanceMeasurementDistance(kind==Kind.STAINED_GLASS?0.012f:0.35f)
                    .useDiffuseLayer(kind==Kind.GLASS);
            if(kind==Kind.STAINED_GLASS) trans.transmittanceTexture(t[0]);
            return PortRenderer.api().createMaterial(m.translucentExt(trans.build()).build());
        }
        var defaults=BlockMaterialDefaults.resolve(p.id,t[3]!=0,t[4]!=0);
        var opaque=MaterialInfoOpaqueEXT.builder().albedoConstant(1,1,1).roughnessConstant(defaults.roughness())
                .metallicConstant(defaults.metallic()).roughnessTexture(t[3]).metallicTexture(t[4]);
        if(kind==Kind.CUTOUT) opaque.alphaTestType(4).alphaReferenceValue(128);
        if(kind==Kind.TEXT) opaque.alphaTestType(4).alphaReferenceValue(26);
        if(kind==Kind.BLEND) opaque.alphaTestType(4).alphaReferenceValue(0).blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
        if(kind==Kind.DECAL) opaque.alphaTestType(4).alphaReferenceValue(1).useDrawCallAlphaState(true);
        // A thin coplanar coating must not independently displace behind its base.
        if(t[2]!=0&&kind!=Kind.DECAL) opaque.heightTexture(t[2]).displaceIn(0.25f);
        if(t[6]!=0) {
            var sss=MaterialInfoOpaqueSubsurfaceEXT.builder().transmittanceTexture(t[0]);
            if(kind==Kind.SOLID) sss.diffusionProfile(true).radiusTexture(t[7]!=0?t[7]:t[0])
                    .radiusScale(LabPBRConverter.DIFFUSION_SSS_RADIUS_SCALE).maxSampleRadius(LabPBRConverter.DIFFUSION_SSS_MAX_SAMPLE_RADIUS);
            else {
                sss.diffusionProfile(false).thicknessTexture(t[6]).singleScatteringAlbedo(0.5f,0.5f,0.5f).measurementDistance(0.75f);
                if(t[7]!=0) sss.singleScatteringAlbedoTexture(t[7]);
            }
            opaque.subsurfaceExt(sss.build());
        }
        return PortRenderer.api().createMaterial(m.opaqueExt(opaque.build()).build());
    }
    public static void flushAnimation() {
        if(PortRenderer.api()==null) return;
        List<TextureBatchEntry> entries=new ArrayList<>();List<ByteBuffer> buffers=new ArrayList<>();
        try {
            for(SpritePixels p:dirty) {
                NativeSheet sheet=nativeSheets.get(p);if(sheet==null) continue;
                for(int i=0;i<8;i++) if(sheet.textures()[i]!=0&&p.animated(i)) {
                    byte[] bytes=p.frame(i);ByteBuffer data=MemoryUtil.memAlloc(bytes.length).put(bytes).flip();buffers.add(data);
                    var patch=new TexturePatch();patch.width=p.frameWidth(i);patch.height=p.frameHeight(i);patch.format=format(i);patch.rowPitch=bytes.length/p.frameHeight(i);patch.pixelData=data;
                    var entry=new TextureBatchEntry();entry.handle=sheet.textures()[i];entry.patches=new TexturePatch[]{patch};entries.add(entry);
                }
            }
            if(!entries.isEmpty()) {var info=new TexturePatchInfo();info.entries=entries.toArray(TextureBatchEntry[]::new);PortRenderer.api().updateTextureFromMemory(info);animationPatches+=entries.size();}
        } finally {for(ByteBuffer data:buffers) MemoryUtil.memFree(data);dirty.clear();}
    }
    public static int materialCount(){return nativeSheets.values().stream().mapToInt(s->s.materials().size()).sum()+(water==null?0:2);}
    public static int spriteCount(){return sprites.size();}
    private static int format(int i){return i==2||i==3||i==4?TextureFormat.R8_UNORM:TextureFormat.RGBA8_UNORM;}
    private static int purpose(int i){return i==1?TexturePurpose.NORMAL_DX:i==2?TexturePurpose.HEIGHTMAP:i==3||i==4||i==6?TexturePurpose.DATA:TexturePurpose.COLOR;}
    private static long hash(SpritePixels p,int channel,int unused){
        long h=((long)p.id.hashCode()<<32)^channel*0x9e3779b97f4a7c15L;
        for(byte[] data:p.channels) h=Long.rotateLeft(h,7)^Arrays.hashCode(data);
        return h==0?1:h;
    }
    public static void releaseNative() {
        if(water!=null){water.close();water=null;}
        if(PortRenderer.api()!=null) for(NativeSheet sheet:nativeSheets.values()) release(sheet);
        nativeSheets.clear();retired.clear();LampAppearance.clear();GrassComposites.clear();
    }
    private static void release(NativeSheet s) {
        RemixAPI api=PortRenderer.api();
            for(long m:s.materials().values()) api.destroyMaterial(m);
            for(long t:s.textures()) if(t!=0) api.destroyTexture(t);
    }
}
