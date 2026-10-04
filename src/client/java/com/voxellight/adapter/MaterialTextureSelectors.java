package com.voxellight.adapter;

import com.google.gson.*;
import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resolve block/tag overrides through active blockstate/model assets, including inherited texture aliases. */
final class MaterialTextureSelectors {
 private final ResourceManager resources;
 private final Map<String,Set<String>> selectors=new HashMap<>();
 private final Map<Identifier,Map<String,String>> models=new HashMap<>();
 MaterialTextureSelectors(ResourceManager resources){this.resources=resources;}
 boolean matches(Identifier texture,String selector){return selectors.computeIfAbsent(selector,this::textures).contains(texture.toString());}
 private Set<String> textures(String selector){
  var result=new HashSet<String>();var registry=net.minecraft.core.registries.BuiltInRegistries.BLOCK;var id=Identifier.tryParse(selector.startsWith("block=")?selector.substring(6):selector.substring(1));if(id==null)return result;
  if(selector.startsWith("block="))blockTextures(id,result);
  else{var tag=net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,id);for(var block:registry)if(block.defaultBlockState().is(tag))blockTextures(registry.getKey(block),result);}
  return result;
 }
 private JsonElement json(Identifier id){try(var reader=resources.getResource(id).orElseThrow().openAsReader()){return JsonParser.parseReader(reader);}catch(Exception ignored){return JsonNull.INSTANCE;}}
 private void blockTextures(Identifier id,Set<String> result){var modelNames=new HashSet<Identifier>();collectModels(json(Identifier.fromNamespaceAndPath(id.getNamespace(),"blockstates/"+id.getPath()+".json")),modelNames);
  for(var model:modelNames){var values=model(model,new HashSet<>());for(String value:values.values()){var seen=new HashSet<String>();while(value.startsWith("#")&&seen.add(value))value=values.getOrDefault(value.substring(1),"#missing");if(!value.startsWith("#")){var texture=Identifier.tryParse(value);if(texture!=null)result.add(texture.toString());}}}
  if(modelNames.isEmpty())result.add(id.getNamespace()+":block/"+id.getPath());
 }
 private void collectModels(JsonElement value,Set<Identifier> result){if(value.isJsonArray())for(var e:value.getAsJsonArray())collectModels(e,result);else if(value.isJsonObject())for(var e:value.getAsJsonObject().entrySet()){if(e.getKey().equals("model")&&e.getValue().isJsonPrimitive()){var id=Identifier.tryParse(e.getValue().getAsString());if(id!=null)result.add(id);}else collectModels(e.getValue(),result);}}
 private Map<String,String> model(Identifier id,Set<Identifier> visiting){var cached=models.get(id);if(cached!=null)return cached;if(!visiting.add(id))return Map.of();var result=new HashMap<String,String>();var data=json(Identifier.fromNamespaceAndPath(id.getNamespace(),"models/"+id.getPath()+".json"));
  if(data.isJsonObject()){var o=data.getAsJsonObject();if(o.has("parent")){var parent=Identifier.tryParse(o.get("parent").getAsString());if(parent!=null)result.putAll(model(parent,visiting));}if(o.has("textures"))for(var e:o.getAsJsonObject("textures").entrySet())result.put(e.getKey(),e.getValue().getAsString());}
  visiting.remove(id);models.put(id,result);return result;
 }
}
