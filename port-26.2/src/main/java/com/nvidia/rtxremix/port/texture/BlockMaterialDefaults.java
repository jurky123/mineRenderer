/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;

import net.minecraft.resources.Identifier;
import java.util.Set;

/** Conservative art defaults for vanilla block sprites without authored PBR channels. */
public final class BlockMaterialDefaults {
    public record Surface(float roughness,float metallic,String family){}
    public record Gel(float ior,float red,float green,float blue,float distance){}
    private static final Gel SLIME=new Gel(1.36f,.82f,.98f,.72f,1);
    private static final Gel HONEY=new Gel(1.47f,.98f,.88f,.55f,1);
    private static final Surface STONE=new Surface(.82f,0,"stone");
    private static final Surface OTHER=new Surface(.7f,0,"unclassified");
    private static final Set<String> METALS=Set.of("iron_block","gold_block","netherite_block","iron_bars","iron_door_top","iron_door_bottom","iron_trapdoor","chain","anvil","anvil_top","chipped_anvil_top","damaged_anvil_top","heavy_core","heavy_weighted_pressure_plate","light_weighted_pressure_plate");
    private static final Set<String> GEMS=Set.of("diamond_block","emerald_block","lapis_block","quartz_block_top","quartz_block_side","quartz_block_bottom","chiseled_quartz_block","chiseled_quartz_block_top","quartz_pillar","quartz_pillar_top");
    private BlockMaterialDefaults(){}
    public static Gel gel(Identifier id){
        if(!id.getNamespace().equals("minecraft"))return null;
        String name=id.getPath();
        if(name.equals("block/slime_block"))return SLIME;
        return name.startsWith("block/honey_block_")?HONEY:null;
    }
    public static Surface forSprite(Identifier id){
        String path=id.getPath();
        if(!id.getNamespace().equals("minecraft")||!path.startsWith("block/"))return OTHER;
        String name=path.substring(6);
        if(METALS.contains(name))return new Surface(name.equals("netherite_block")?.42f:.27f,1,"metal");
        if(name.equals("raw_iron_block")||name.equals("raw_gold_block")||name.equals("raw_copper_block"))return new Surface(.66f,.8f,"raw_metal");
        if(name.contains("copper")&&!name.contains("ore")){
            if(name.startsWith("waxed_"))name=name.substring(6);
            if(name.startsWith("oxidized_"))return new Surface(.8f,0,"oxidized_copper");
            if(name.startsWith("weathered_"))return new Surface(.65f,.25f,"weathered_copper");
            if(name.startsWith("exposed_"))return new Surface(.48f,.6f,"exposed_copper");
            return new Surface(.28f,1,"copper");
        }
        if(name.endsWith("_ore")||name.equals("gilded_blackstone"))return STONE;
        if(GEMS.contains(name)||name.startsWith("amethyst_block")||name.equals("budding_amethyst"))return new Surface(.22f,0,"mineral");
        if(name.contains("obsidian"))return new Surface(.32f,0,"volcanic_glass");
        if(name.contains("glazed_terracotta"))return new Surface(.28f,0,"glaze");
        if(name.contains("polished_")||name.contains("tiles")||name.equals("smooth_stone")||name.equals("smooth_stone_slab_side"))return new Surface(.46f,0,"polished_stone");
        if(name.contains("wool")||name.contains("carpet")||name.contains("moss")||name.contains("sponge"))return new Surface(.96f,0,"fiber");
        if(name.contains("sand")&&!name.contains("sandstone")||name.equals("gravel")||name.contains("concrete_powder")||name.equals("soul_soil"))return new Surface(.98f,0,"granular");
        if(name.contains("leaves")||name.contains("grass")||name.contains("fern")||name.contains("vine")||name.contains("sapling"))return new Surface(.87f,0,"vegetation");
        if(name.startsWith("stripped_"))return new Surface(.68f,0,"stripped_wood");
        if(name.contains("planks")||name.contains("bamboo")||name.contains("bookshelf")||name.contains("crafting_table")||name.contains("barrel"))return new Surface(.8f,0,"wood");
        if(name.contains("_log")||name.contains("_wood")||name.contains("_stem")||name.contains("hyphae"))return new Surface(.9f,0,"bark");
        if(name.equals("slime_block")||name.startsWith("honey_block"))return new Surface(.18f,0,"gel");
        if(name.contains("snow")||name.contains("dirt")||name.contains("mud")||name.contains("terracotta"))return new Surface(.93f,0,"earth");
        if(name.contains("sea_lantern")||name.contains("glowstone")||name.contains("froglight"))return new Surface(.55f,0,"luminous_mineral");
        return STONE;
    }
    /** A supplied map replaces its fallback channel instead of multiplying by the guessed value. */
    public static Surface resolve(Identifier id,boolean roughnessMap,boolean metallicMap){
        Surface fallback=forSprite(id);
        return new Surface(roughnessMap?1:fallback.roughness(),metallicMap?1:fallback.metallic(),fallback.family());
    }
}
