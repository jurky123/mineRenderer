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
        if(key.equals("rt_reference")){
            if(value.startsWith("spp ")){key="rt_reference spp";value=value.substring(4);}
            else if(value.equals("reset"))return false;
        }
        if(key.equals("rt_reference_full")&&value.startsWith("scale ")){key="rt_reference_full scale";value=value.substring(6);}
        if(key.equals("preset"))current.clear();
        current.put(key,value);
        if(!persist||!persistent(key))return false;
        if(key.equals("preset"))saved.clear();
        saved.remove(key);saved.put(key,value);return true;
    }
    private static boolean persistent(String key){return key.equals("rt_reference spp")||(!key.contains("debug")&&!key.equals("rt_reference")&&!key.equals("rt_reference_full")&&!Set.of("scene","profile","export","settings","status","rt_benchmark","pathtrace_freeze").contains(key));}
    public void load(Path file)throws IOException{
        if(!Files.exists(file))return;
        Map<String,String> loaded=new Gson().fromJson(Files.readString(file),new TypeToken<LinkedHashMap<String,String>>(){}.getType());
        if(loaded!=null)for(var entry:loaded.entrySet())if(entry.getKey()!=null&&entry.getValue()!=null&&persistent(entry.getKey()))saved.put(entry.getKey(),entry.getValue());
    }
    public void save(Path file)throws IOException{
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"settings-",".json");
        try{Files.writeString(temporary,new Gson().toJson(saved));try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temporary);}
    }
}
