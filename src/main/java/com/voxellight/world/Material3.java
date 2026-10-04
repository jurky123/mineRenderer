package com.voxellight.world;

import com.google.gson.*;
import java.util.*;

/** Authored values use perceptual roughness; GPU tables store microfacet alpha explicitly. */
public record Material3(Type type,float perceptualRoughness,float f0,int conductor,float porosity,
                        float ior,float transmission,float coatWeight,float coatRoughness,float coatIor,
                        float absorptionR,float absorptionG,float absorptionB,float scatteringR,float scatteringG,float scatteringB,float phaseG,float alphaV,float emission) {
    public enum Type { DIFFUSE,ROUGH_DIFFUSE,CONDUCTOR,DIELECTRIC,THIN_DIELECTRIC,COATED_DIFFUSE,COATED_CONDUCTOR,DIFFUSE_TRANSMISSION,EMISSIVE,WATER }
    public Material3 {
        if(!Float.isFinite(emission)||emission< -1||emission>1)throw new IllegalArgumentException("Invalid emission");Objects.requireNonNull(type);if(conductor!=0&&conductor!=255&&(conductor<230||conductor>237))throw new IllegalArgumentException("Unknown conductor ID");
        if(coatWeight>0){if(type==Type.CONDUCTOR)type=Type.COATED_CONDUCTOR;else if(type==Type.DIFFUSE||type==Type.ROUGH_DIFFUSE||type==Type.EMISSIVE)type=Type.COATED_DIFFUSE;else if(type==Type.DIFFUSE_TRANSMISSION)throw new IllegalArgumentException("Foliage coating requires a separate model");}
        for(float x:new float[]{perceptualRoughness,f0,porosity,transmission,coatWeight,coatRoughness,scatteringR,scatteringG,scatteringB,alphaV})if(!Float.isFinite(x)||x<0||x>1)throw new IllegalArgumentException("Material parameter outside [0,1]");
        for(float x:new float[]{absorptionR,absorptionG,absorptionB})if(!Float.isFinite(x)||x<0||x>8)throw new IllegalArgumentException("Absorption outside [0,8]");
        if(!Float.isFinite(ior)||ior<1||ior>3||!Float.isFinite(coatIor)||coatIor<1||coatIor>3||!Float.isFinite(phaseG)||Math.abs(phaseG)>.9f)throw new IllegalArgumentException("Invalid optical parameter");
    }
    public float microfacetAlpha(){return perceptualRoughness*perceptualRoughness;}
    public static Material3 fromProfile(String id,PbrMaterials.Profile p){
        Type type=p.metal()>=230?Type.CONDUCTOR:Type.ROUGH_DIFFUSE;float ior=1.5f,trans=0,coat=0,rough=p.roughness();
        float ar=0,ag=0,ab=0,sr=0,sg=0,sb=0,g=0;
        if(id.contains("glass")||id.contains("ice")){type=id.contains("pane")?Type.THIN_DIELECTRIC:Type.DIELECTRIC;trans=1;}
        if(id.contains("water_")){type=Type.WATER;ior=1.333f;trans=1;ar=.16f;ag=.060f;ab=.035f;sr=.018f;sg=.035f;sb=.045f;g=.7f;}
        if(id.contains("leaves")||id.contains("grass")&&!id.contains("grass_block")||id.contains("fern")||id.contains("vine")||id.contains("petal")||id.contains("crop")){type=Type.DIFFUSE_TRANSMISSION;trans=.4f;}
        if(id.contains("polished")||id.contains("glazed")||id.contains("waxed")||id.contains("smooth_")){coat=.6f;type=p.metal()>=230?Type.COATED_CONDUCTOR:Type.COATED_DIFFUSE;}
        return new Material3(type,rough,p.f0(),p.metal(),p.porosity(),ior,trans,coat,.18f,1.5f,ar,ag,ab,sr,sg,sb,g,rough,-1);
    }
    /** LabPBR channels are authoritative; unknown metal IDs use its documented albedo-F0 fallback. */
    public Material3 withLabPbr(int packed){
        int reflectance=(packed>>>8)&255,blue=(packed>>>16)&255;boolean metal=reflectance>=230;
        Type model=metal?Type.CONDUCTOR:type==Type.CONDUCTOR?Type.ROUGH_DIFFUSE:type==Type.COATED_CONDUCTOR?Type.COATED_DIFFUSE:type;
        float r=1-(packed&255)/255f;boolean foliage=blue>=65&&!metal&&model!=Type.DIELECTRIC&&model!=Type.THIN_DIELECTRIC&&model!=Type.WATER;if(foliage)model=Type.DIFFUSE_TRANSMISSION;
        return new Material3(model,r,metal?.04f:reflectance/255f,metal?(reflectance<=237?reflectance:255):0,blue<=64?blue/64f:0,ior,foliage?(blue-65)/190f*.5f:transmission,foliage?0:coatWeight,coatRoughness,coatIor,absorptionR,absorptionG,absorptionB,scatteringR,scatteringG,scatteringB,phaseG,r,emission);
    }
    public float[] mediumTable(){int[] t=table(0);int a=t[1],b=t[2],c=t[3];return new float[]{(b&255)*8/255f,(b>>>8&255)*8/255f,(b>>>16&255)*8/255f,(c&255)/255f,(c>>>8&255)/255f,(c>>>16&255)/255f,(b>>>24)*1.8f/255f-.9f,(a>>>8&255)*3/255f};}
    public static Material3 read(JsonObject o,Material3 base){
        Type type=o.has("type")?Type.valueOf(o.get("type").getAsString().toUpperCase(Locale.ROOT)):base.type;
        return new Material3(type,get(o,"roughness",base.perceptualRoughness),get(o,"f0",base.f0),o.has("metal")?o.get("metal").getAsInt():base.conductor,get(o,"porosity",base.porosity),get(o,"ior",base.ior),get(o,"transmission",base.transmission),get(o,"coat_weight",base.coatWeight),get(o,"coat_roughness",base.coatRoughness),get(o,"coat_ior",base.coatIor),get(o,"absorption_r",base.absorptionR),get(o,"absorption_g",base.absorptionG),get(o,"absorption_b",base.absorptionB),get(o,"scattering_r",base.scatteringR),get(o,"scattering_g",base.scatteringG),get(o,"scattering_b",base.scatteringB),get(o,"phase_g",base.phaseG),get(o,"v_roughness",base.alphaV),get(o,"emission",base.emission));
    }
    private static float get(JsonObject o,String name,float value){return o.has(name)?o.get(name).getAsFloat():value;}
    private static int rgba(float r,float g,float b,float a){return byteValue(r)|(byteValue(g)<<8)|(byteValue(b)<<16)|(byteValue(a)<<24);}
    private static int byteValue(float a){return Math.round(Math.clamp(a,0,1)*255);}
    public int[] table(int packed){return new int[]{packed,rgba(type.ordinal()/255f,ior/3,coatWeight,coatRoughness*coatRoughness),rgba(absorptionR/8,absorptionG/8,absorptionB/8,(phaseG+.9f)/1.8f),rgba(scatteringR,scatteringG,scatteringB,transmission),rgba(coatIor/3,alphaV*alphaV,0,0)};}
}
