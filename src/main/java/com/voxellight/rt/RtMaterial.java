package com.voxellight.rt;
/** Common linear BSDF semantics. LabPBR conductor IDs retain their authored optical constants. */
public record RtMaterial(float r,float g,float b,float roughness,float f0,int conductor,
                         float emission,float transmission,float ior,float absorptionR,float absorptionG,float absorptionB,
                         boolean thinSurface,int flags) {
 public RtMaterial {
  for(float value:new float[]{r,g,b,roughness,f0,emission,transmission,ior,absorptionR,absorptionG,absorptionB})if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid RT material");
  if(r>1||g>1||b>1||roughness>1||f0>1||transmission>1||ior<1||conductor<0||conductor>255)throw new IllegalArgumentException("Invalid dielectric");
 }
 public static float transmittance(float absorption,float distance){if(!Float.isFinite(absorption)||!Float.isFinite(distance)||absorption<0||distance<0)throw new IllegalArgumentException();return (float)Math.exp(-absorption*distance);}
 public static float dielectricF0(float ior){float r=(ior-1)/(ior+1);return r*r;}
 public static float snellCosine(float cosIncident,float fromIor,float toIor){float eta=fromIor/toIor,k=1-eta*eta*(1-cosIncident*cosIncident);return k<0?Float.NaN:(float)Math.sqrt(k);}
}
