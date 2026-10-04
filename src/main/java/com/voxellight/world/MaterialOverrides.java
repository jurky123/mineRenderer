package com.voxellight.world;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.function.Predicate;
/** Exact texture > block > tag > namespace wildcard. Last equal-priority rule wins. LabPBR channels overlay afterward. */
public final class MaterialOverrides {
    private record Rule(String selector,JsonObject data,int rank){}
    private final List<Rule> rules=new ArrayList<>();
    public void read(Reader reader){JsonElement root=JsonParser.parseReader(reader);if(root.isJsonArray())for(var e:root.getAsJsonArray())add(e.getAsJsonObject());else add(root.getAsJsonObject());if(rules.size()>4096)throw new IllegalArgumentException("Material override limit");}
    private void add(JsonObject o){String key=o.get("material").getAsString();rules.add(new Rule(key,o,key.startsWith("#")?2:key.endsWith(":*")?1:key.startsWith("block=")?3:4));}
    public Material3 resolve(String texture,Material3 base,Predicate<String> blockOrTag){Rule selected=null;for(var rule:rules){boolean match=rule.rank==4?rule.selector.equals(texture):rule.rank==1?texture.startsWith(rule.selector.substring(0,rule.selector.length()-1)):blockOrTag.test(rule.selector);if(match&&(selected==null||rule.rank>=selected.rank))selected=rule;}return selected==null?base:Material3.read(selected.data,base);}
}
