#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MaterialAlbedo;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialDepth;
uniform sampler2D SceneDepth;
layout(std140) uniform PathTraceSettings { mat4 PtInvProjection;mat4 PtViewToWorld;vec4 PtControls; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 ptPosition;
layout(location=1) out vec4 ptNormal;
layout(location=2) out vec4 ptAlbedo;
void main(){
 ptPosition=vec4(0);ptNormal=vec4(0,1,0,0);ptAlbedo=vec4(0);
 ivec2 size=textureSize(SceneDepth,0),pixel=min(ivec2(texCoord*vec2(size)),size-1);
 float d=texelFetch(MaterialDepth,pixel,0).r,scene=texelFetch(SceneDepth,pixel,0).r;
 vec4 a=texelFetch(MaterialAlbedo,pixel,0),n=texelFetch(MaterialNormal,pixel,0)*vec4(2,2,2,3)-vec4(1,1,1,0);
 int flags=int(round(a.a*255));
 if(d<=0 || scene<=0 || n.a<.5 || (flags&21)!=0 || abs(int(floatBitsToUint(d))-int(floatBitsToUint(scene)))>8)return;
 vec2 uv=(vec2(pixel)+.5)/vec2(size);vec4 p=PtInvProjection*vec4(uv*2-1,d,1);p/=p.w;
 vec3 world=(PtViewToWorld*vec4(p.xyz,0)).xyz;
 if(length(world)>24)return;
 ptPosition=vec4(world,1);ptNormal=vec4(normalize(n.xyz),1);ptAlbedo=vec4(a.rgb,1);
}
