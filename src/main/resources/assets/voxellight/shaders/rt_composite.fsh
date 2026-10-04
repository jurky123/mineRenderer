#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentHdr;
uniform sampler2D RtDiffuse;
uniform sampler2D RtSpecular;
uniform sampler2D RtTransmission;
uniform sampler2D RtPosition;
uniform sampler2D RtNormal;
uniform sampler2D RtMaterial;
uniform sampler2D RtSurfaceKey;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialPbr;
uniform sampler2D MaterialTable;
uniform sampler2D SceneDepth;
layout(std140) uniform RtFrame {mat4 RtInvProjection;mat4 RtViewToWorld;vec4 RtControls;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 fragColor=texture(CurrentHdr,texCoord);if(RtControls.w>.5){vec4 result=texture(RtDiffuse,texCoord);if(result.a>.5)fragColor=vec4(result.rgb,1);return;}float d=texture(SceneDepth,texCoord).r;if(d<=0)return;
 vec4 p=RtInvProjection*vec4(texCoord*2-1,d,1);p/=p.w;vec3 world=(RtViewToWorld*vec4(p.xyz,0)).xyz;
 vec3 n=normalize(texture(MaterialNormal,texCoord).xyz*2-1);vec4 packed=texture(MaterialPbr,texCoord);ivec2 id=ivec2(round(packed.rg*255));float materialId=float(id.x+256*id.y);float perceptualRoughness=1-texelFetch(MaterialTable,id,0).r;
 ivec2 size=textureSize(RtPosition,0),base=clamp(ivec2(texCoord*vec2(size)),ivec2(0),size-1);vec3 gi=vec3(0),spec=vec3(0);float diffuseWeight=0,specWeight=0;
 float tolerance=max(.025,.0008*length(world));float footprint=max(length(dFdx(world)),length(dFdy(world)))*4;
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
  ivec2 q=clamp(base+ivec2(x,y),ivec2(0),size-1);vec4 h=texelFetch(RtPosition,q,0),key=texelFetch(RtSurfaceKey,q,0),hn=texelFetch(RtNormal,q,0);vec4 properties=texelFetch(RtMaterial,q,0);
  if(h.a<.5||hn.a>1.5||key.x!=materialId||abs(dot(n,h.xyz-world))>tolerance||dot(n,hn.xyz)<.95||length(h.xyz-world)>max(.15,footprint*2))continue;
  float plane=abs(dot(n,h.xyz-world)),w=exp(-float(x*x+y*y)-plane*plane/(tolerance*tolerance));vec4 diffuse=texelFetch(RtDiffuse,q,0),glossy=texelFetch(RtSpecular,q,0);
  if(diffuse.a>.5){gi+=diffuse.rgb*w;diffuseWeight+=w;}
  // A sharp mirror uses one admitted observation, never an across-edge spatial average.
  bool sharp=perceptualRoughness<.15;float alpha=perceptualRoughness*perceptualRoughness;
  if(glossy.a>0&&abs(properties.x-alpha)<.035&&(!sharp||x==0&&y==0)){
   vec4 center=texelFetch(RtSpecular,base,0);if(!sharp&&center.a>0&&abs(center.a-glossy.a)>max(.1,alpha*max(center.a,glossy.a)))continue;
   spec+=glossy.rgb*w;specWeight+=w;
  }
 }
 if(diffuseWeight>0)gi/=diffuseWeight;if(specWeight>0)spec/=specWeight;
 if(RtControls.y>43.5&&RtControls.y<44.5){fragColor=vec4(diffuseWeight>0?vec3(.1,1,.1):vec3(1,.1,.05),1);return;}
 if(RtControls.y>0.5){fragColor=vec4(RtControls.y<1.5?gi:RtControls.y<2.5?spec:RtControls.y<3.5?texelFetch(RtTransmission,base,0).rgb:gi,1);return;}
 vec4 optic=texelFetch(MaterialTable,ivec2(id.x,id.y+256),0);int materialClass=int(round(optic.r*255));bool opaqueDielectric=materialClass==3||materialClass==4||materialClass==9;
 if(RtControls.z>.5||opaqueDielectric){vec4 t=texelFetch(RtTransmission,base,0),key=texelFetch(RtSurfaceKey,base,0),h=texelFetch(RtPosition,base,0);if(t.a>0&&h.a>.5&&key.x==materialId&&dot(n,texelFetch(RtNormal,base,0).xyz)>.95&&length(h.xyz-world)<max(.15,footprint*2)&&abs(dot(n,h.xyz-world))<tolerance){fragColor=vec4(t.rgb,1);return;}}
 fragColor.rgb+=gi+spec;
 // Dielectric signals are applied by the native translucent fragment, where the actual
 // full-resolution first interface is known. Never paint water/glass over opaque pixels here.
}
