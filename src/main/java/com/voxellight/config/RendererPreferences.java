package com.voxellight.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Ordered overrides replay presets before subsequent individual edits; diagnostics remain session-only. */
public final class RendererPreferences {
    private final LinkedHashMap<String,String> saved=new LinkedHashMap<>();
    private final Map<String,String> current=new HashMap<>();
    public String value(String key){return current.getOrDefault(key,saved.getOrDefault(key,""));}
    public Map<String,String> snapshot(){return Collections.unmodifiableMap(new LinkedHashMap<>(saved));}
    public boolean record(String input,boolean persist){
        if(!input.startsWith("voxellight "))return false;
        String[] parts=input.substring(11).trim().split(" +",2);
        if(parts.length!=2)return false;
        String key=parts[0],value=parts[1];
        if(obsolete(key))return false;
        if(key.equals("rt_accumulate")){
            if(value.startsWith("spp ")){key="rt_accumulate spp";value=value.substring(4);}
            else if(value.startsWith("freeze ")){key="rt_accumulate freeze";value=value.substring(7);}
            else if(value.equals("reset"))return false;
        }
        if(key.equals("preset"))current.clear();
        current.put(key,value);
        if(!persist||!persistent(key))return false;
        if(key.equals("preset"))saved.clear();
        saved.remove(key);saved.put(key,value);return true;
    }
    private static boolean obsolete(String key){return key.startsWith("pathtrace")||key.startsWith("rt_reference")||Set.of("rt_gi","rt_reflections","rt_transmission","rt_denoiser","radiance_cache","rt_caustics","rt_primary_glossy_nee","rt_environment","rt_multiscatter","rt_firefly_clamp","rt_benchmark","rt_debug").contains(key);}
    private static boolean persistent(String key){return !obsolete(key)&&!key.contains("debug")&&!Set.of("scene","profile","export","settings","status","rt_lighting_probe").contains(key);}
    public void load(Path file)throws IOException{
        if(!Files.exists(file))return;
        Map<String,String> loaded=new Gson().fromJson(Files.readString(file),new TypeToken<LinkedHashMap<String,String>>(){}.getType());
        if(loaded!=null)for(var entry:loaded.entrySet()){
            String key=entry.getKey(),value=entry.getValue();if(key==null||value==null)continue;
            if(key.equals("rt_reference spp"))key="rt_accumulate spp";
            if(key.equals("preset")&&value.equals("rtx_quality"))value="vulkan_quality";
            if(key.equals("rt_backend")&&(value.equals("optix_rt")||value.equals("cuda_voxel_reference")))value="vulkan_pt";
            if(persistent(key))saved.put(key,value);
        }
    }
    public void save(Path file)throws IOException{
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"settings-",".json");
        try{Files.writeString(temporary,new Gson().toJson(saved));try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temporary);}
    }
}
