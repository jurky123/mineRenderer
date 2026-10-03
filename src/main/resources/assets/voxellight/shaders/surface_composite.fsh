#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentHdr;uniform sampler2D ReflectionDelta;uniform sampler2D MaterialNormal;uniform sampler2D MaterialAlbedo;uniform sampler2D SceneDepth;
layout(std140) uniform SurfaceSettings{vec4 ReflectionControls;vec4 ReflectionTrace;};
// VOXELLIGHT_MOTION_FUNCTIONS
#ifdef COLOR_TEMPORAL
uniform sampler2D ColorHistory;
// VOXELLIGHT_MOTION_REUSE
#endif
layout(location=0) in vec2 texCoord;layout(location=0) out vec4 fragColor;
vec3 reflectionDelta(vec3 position,vec3 normal){
 vec2 size=vec2(textureSize(ReflectionDelta,0)), pixel=texCoord*size-.5;ivec2 center=ivec2(floor(pixel));vec2 phase=fract(pixel);vec3 sum=vec3(0);float total=0;
 for(int y=0;y<2;y++)for(int x=0;x<2;x++){
  ivec2 samplePixel=clamp(center+ivec2(x,y),ivec2(0),ivec2(size)-1);vec2 uv=(vec2(samplePixel)+.5)/size;
  vec4 n=texture(MaterialNormal,uv);float d=texture(SceneDepth,uv).r;if(n.a<.16||d<=0.0)continue;
  vec3 p=motionPosition(uv,d,CurrentClipToWorld);float weight=(x==0?1.0-phase.x:phase.x)*(y==0?1.0-phase.y:phase.y)*pow(max(dot(normal,normalize(n.xyz*2.0-1.0)),0.0),16.0)*exp(-abs(dot(p-position,normal))/max(.035,length(position)*.0015));
  sum+=texelFetch(ReflectionDelta,samplePixel,0).rgb*weight;total+=weight;
 }
 return total>.001?sum/total:vec3(0);
}
void main(){
 vec4 current=texture(CurrentHdr,texCoord);fragColor=current;if(current.a<.5)return;
 vec4 guide=texture(MaterialNormal,texCoord);float depth=texture(SceneDepth,texCoord).r;vec3 normal=normalize(guide.xyz*2.0-1.0);
 vec3 delta=ReflectionControls.z>.5?reflectionDelta(motionPosition(texCoord,depth,CurrentClipToWorld),normal):vec3(0);current.rgb=max(current.rgb+delta,vec3(0));fragColor=current;
#ifdef COLOR_TEMPORAL
 vec2 previousUv;int flags=int(round(texture(MaterialAlbedo,texCoord).a*255.0));
 if(ReflectionControls.y<.5||guide.a<.16||(flags&24)!=0||!motionPrevious(texCoord,depth,normal,previousUv))return;
 vec4 old=texture(ColorHistory,previousUv);if(old.a<.5)return;
 vec3 lo=current.rgb,hi=current.rgb;vec2 pixel=1.0/vec2(textureSize(CurrentHdr,0));
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){vec4 c=texture(CurrentHdr,texCoord+vec2(x,y)*pixel);if(c.a<.5)continue;vec3 value=max(c.rgb+delta,vec3(0));lo=min(lo,value);hi=max(hi,value);}
 float speed=length(texture(MotionVectors,texCoord).rg*vec2(textureSize(CurrentHdr,0)));
 fragColor.rgb=mix(current.rgb,clamp(old.rgb,lo,hi),.85*exp(-speed*.025));
#endif
}
