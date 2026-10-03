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
layout(std140) uniform PathTraceHistory {mat4 PreviousClip;vec4 CameraDelta;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 result;
float viewDepth(vec2 uv,float d){vec4 p=PtInvProjection*vec4(uv*2-1,d,1);return -p.z/p.w;}
void reject(vec3 color){if(PtControls.z>.5)result=vec4(color,1);}
void main(){
 result=texture(CurrentHdr,texCoord);
 float d=texture(MaterialDepth,texCoord).r,scene=texture(SceneDepth,texCoord).r;
 vec4 n=texture(MaterialNormal,texCoord)*vec4(2,2,2,3)-vec4(1,1,1,0);
 int flags=int(round(texture(MaterialAlbedo,texCoord).a*255));
 if(d<=0 || scene<=0 || n.a<.5 || (flags&21)!=0){reject(vec3(1,0,0));return;}
 float zs=viewDepth(texCoord,scene),zm=viewDepth(texCoord,d);
 if(abs(zm-zs)>max(.01,.001*abs(zs))){reject(vec3(1,.4,0));return;}
 if(PtControls.w<.5){reject(vec3(1,1,0));return;}
 vec4 p=PtInvProjection*vec4(texCoord*2-1,scene,1);p/=p.w;vec3 world=(PtViewToWorld*vec4(p.xyz,0)).xyz;
 vec3 previousWorld=world+CameraDelta.xyz;vec4 projected=PreviousClip*vec4(previousWorld,1);
 if(projected.w<=0){reject(vec3(0,0,1));return;}vec2 historyUv=projected.xy/projected.w*.5+.5;
 if(any(lessThan(historyUv,vec2(0))) || any(greaterThanEqual(historyUv,vec2(1)))){reject(vec3(0,0,1));return;}
 ivec2 size=textureSize(PathRadiance,0);vec2 uv=historyUv*vec2(size)-.5;ivec2 base=ivec2(floor(uv));vec3 light=vec3(0);float total=0;bool normalRejected=false;
 for(int y=0;y<2;y++)for(int x=0;x<2;x++){
  ivec2 q=clamp(base+ivec2(x,y),ivec2(0),size-1);vec4 guide=texelFetch(PathPosition,q,0);vec3 normal=texelFetch(PathNormal,q,0).xyz;
  vec3 delta=guide.xyz-previousWorld;float plane=abs(dot(normal,delta));float tolerance=.12+.005*length(world);
  if(guide.w>.5 && dot(normal,normalize(n.xyz))<=.9)normalRejected=true;
  float weight=guide.w>.5 && dot(normal,normalize(n.xyz))>.9 && plane<tolerance && length(delta)<2.0 ? exp(-plane*plane/(tolerance*tolerance)) : 0;
  vec2 b=1-abs(uv-vec2(base+ivec2(x,y)));weight*=max(b.x,0)*max(b.y,0);
  light+=texelFetch(PathRadiance,q,0).rgb*weight;total+=weight;
 }
 // A sparse low-res footprint can contain only invalid pixels even though a
 // compatible surface is immediately beside it. Use a bounded same-plane
 // gather before dropping the contribution completely; never cross normals.
 if(total<1e-5){
  for(int y=-1;y<=2;y++)for(int x=-1;x<=2;x++){
   ivec2 q=base+ivec2(x,y);if(any(lessThan(q,ivec2(0))) || any(greaterThanEqual(q,size)))continue;
   vec4 guide=texelFetch(PathPosition,q,0);vec3 normal=texelFetch(PathNormal,q,0).xyz;
   vec3 delta=guide.xyz-previousWorld;float plane=abs(dot(normal,delta));float tolerance=.12+.005*length(world);
   if(guide.w<=.5 || dot(normal,normalize(n.xyz))<=.9 || plane>=tolerance || length(delta)>=2.0)continue;
   float weight=exp(-dot(uv-vec2(q),uv-vec2(q)))*exp(-plane*plane/(tolerance*tolerance));
   light+=texelFetch(PathRadiance,q,0).rgb*weight;total+=weight;
  }
 }
 if(total>1e-5){if(PtControls.z>.5){result=vec4(0,1,0,1);return;}light/=total;float fade=1-smoothstep(16,24,length(world));result.rgb=PtControls.y>.5?light*fade:result.rgb+light*fade*PtControls.x;}
 else reject(normalRejected?vec3(1,0,1):vec3(0,1,1));
}
