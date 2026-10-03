package com.voxellight.world;

import com.google.gson.JsonParser;
import java.io.*;
import java.util.*;

/** Texture-ID profiles; LabPBR maps override these authored vanilla defaults. */
public final class PbrMaterials {
    public record Profile(float roughness,float f0,int metal,float porosity) {
        public Profile {
            for(float x:new float[]{roughness,f0,porosity})if(!Float.isFinite(x)||x<0||x>1)throw new IllegalArgumentException("Invalid PBR parameter");
            if(f0>229/255f || (metal!=0 && metal!=255 && (metal<230||metal>237)))throw new IllegalArgumentException("Invalid LabPBR reflectance");
        }
        public int packed(int ao) {
            return Math.round((1-roughness)*255) | ((metal==0?Math.round(f0*255):metal)<<8)
                    | (Math.round(porosity*64)<<16) | (Math.clamp(ao,0,255)<<24);
        }
    }
    public static final Profile FALLBACK=new Profile(.85f,.04f,0,.4f);
    private final Map<String,Profile> profiles;
    private PbrMaterials(Map<String,Profile> profiles){this.profiles=Map.copyOf(profiles);}
    public Profile profile(String id){return profiles.getOrDefault(id,FALLBACK);}
    public static PbrMaterials read(Reader reader) {
        var root=JsonParser.parseReader(reader).getAsJsonObject();if(root.size()>4096)throw new IllegalArgumentException("PBR profile limit exceeded");
        var profiles=new HashMap<String,Profile>();
        for(var e:root.entrySet()) {
            if(!e.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Invalid texture ID");
            var p=e.getValue().getAsJsonObject();profiles.put(e.getKey(),new Profile(p.get("roughness").getAsFloat(),p.get("f0").getAsFloat(),p.get("metal").getAsInt(),p.get("porosity").getAsFloat()));
        }
        return new PbrMaterials(profiles);
    }
    public static PbrMaterials defaults() {
        try(var in=PbrMaterials.class.getResourceAsStream("/assets/voxellight/pbr_materials.json")) {
            if(in==null)throw new IllegalStateException("Missing PBR profiles");return read(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public static float linearRoughness(int smoothness){float r=1-Math.clamp(smoothness,0,255)/255f;return r*r;}
    public static float emission(int alpha){return alpha==255?0:Math.clamp(alpha,0,254)/254f;}
    public static final class Palette {
        private final Map<Integer,Integer> ids=new LinkedHashMap<>();
        private final int[] values=new int[65536];
        private int overflow;
        public Palette(){id(FALLBACK.packed(255));}
        public int id(int packed){var old=ids.get(packed);if(old!=null)return old;if(ids.size()==values.length){overflow++;return 0;}int id=ids.size();ids.put(packed,id);values[id]=packed;return id;}
        public int value(int id){return values[id];}
        public int size(){return ids.size();}
        public int overflow(){return overflow;}
    }
}
