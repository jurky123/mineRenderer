package com.voxellight.world;

import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact registry IDs, shared by placed and held emissive blocks. Resource packs may replace the JSON. */
public final class LightMaterials {
    public record Color(float red,float green,float blue) {
        public Color {
            if(!Float.isFinite(red)||!Float.isFinite(green)||!Float.isFinite(blue)
                    ||red<0||green<0||blue<0||red>1||green>1||blue>1)throw new IllegalArgumentException("Light RGB must be finite and within 0..1");
        }
    }
    public static final Color FALLBACK=new Color(.85f,.82f,.75f);
    private final Map<String,Color> colors;
    private LightMaterials(Map<String,Color> colors){this.colors=Map.copyOf(colors);}
    public Color color(String id){return colors.getOrDefault(id,FALLBACK);}
    public int size(){return colors.size();}
    public static LightMaterials read(Reader reader) {
        var root=JsonParser.parseReader(reader).getAsJsonObject();
        if(root.size()>1024)throw new IllegalArgumentException("Too many light materials");
        var colors=new LinkedHashMap<String,Color>();
        for(var entry:root.entrySet()) {
            if(!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Invalid block registry ID");
            var rgb=entry.getValue().getAsJsonArray();
            if(rgb.size()!=3)throw new IllegalArgumentException("Expected three RGB components");
            colors.put(entry.getKey(),new Color(rgb.get(0).getAsFloat(),rgb.get(1).getAsFloat(),rgb.get(2).getAsFloat()));
        }
        return new LightMaterials(colors);
    }
    public static LightMaterials defaults() {
        try(var stream=LightMaterials.class.getResourceAsStream("/assets/voxellight/light_materials.json")) {
            if(stream==null)throw new IllegalStateException("Missing bundled light materials");
            return read(new InputStreamReader(stream,StandardCharsets.UTF_8));
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}
