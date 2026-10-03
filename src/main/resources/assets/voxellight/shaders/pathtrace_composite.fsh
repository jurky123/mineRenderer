#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentHdr;
uniform sampler2D PathRadiance;
uniform sampler2D PathPosition;
uniform sampler2D PathNormal;
uniform sampler2D MaterialDepth;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialAlbedo;
uniform sampler2D SceneDepth;
layout(std140) uniform PathTraceSettings {mat4 PtInvProjection;mat4 PtViewToWorld;vec4 PtControls;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 result;
void main(){
 result=texture(CurrentHdr,texCoord);
 float d=texture(MaterialDepth,texCoord).r,scene=texture(SceneDepth,texCoord).r;
 vec4 n=texture(MaterialNormal,texCoord)*vec4(2,2,2,3)-vec4(1,1,1,0);
 int flags=int(round(texture(MaterialAlbedo,texCoord).a*255));
 if(d<=0 || n.a<.5 || (flags&21)!=0 || abs(int(floatBitsToUint(d))-int(floatBitsToUint(scene)))>8)return;
 vec4 p=PtInvProjection*vec4(texCoord*2-1,d,1);p/=p.w;vec3 world=(PtViewToWorld*vec4(p.xyz,0)).xyz;
 ivec2 size=textureSize(PathRadiance,0);vec2 uv=texCoord*vec2(size)-.5;ivec2 base=ivec2(floor(uv));vec3 light=vec3(0);float total=0;
 for(int y=0;y<2;y++)for(int x=0;x<2;x++){
  ivec2 q=clamp(base+ivec2(x,y),ivec2(0),size-1);vec4 guide=texelFetch(PathPosition,q,0);vec3 normal=texelFetch(PathNormal,q,0).xyz;
  vec3 delta=guide.xyz-world;float plane=abs(dot(normal,delta));float tolerance=.12+.005*length(world);
  float weight=guide.w>.5 && dot(normal,normalize(n.xyz))>.9 && plane<tolerance && length(delta)<2.0 ? exp(-plane*plane/(tolerance*tolerance)) : 0;
  vec2 b=1-abs(uv-vec2(base+ivec2(x,y)));weight*=max(b.x,0)*max(b.y,0);
  light+=texelFetch(PathRadiance,q,0).rgb*weight;total+=weight;
 }
 if(total>0){light/=total;float fade=1-smoothstep(16,24,length(world));result.rgb=PtControls.y>.5?light*fade:result.rgb+light*fade*PtControls.x;}
}
