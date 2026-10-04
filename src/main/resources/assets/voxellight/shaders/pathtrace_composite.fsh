#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentHdr;
uniform sampler2D CachedGiTags;
uniform sampler2D CachedGiLight;
uniform sampler2D MaterialPbr;
uniform sampler2D MaterialTable;
layout(std140) uniform PbrSettings { vec4 PbrControls; };
uniform sampler2D PathRadiance;
uniform sampler2D PathPosition;
uniform sampler2D PathNormal;
uniform sampler2D OlderRadiance;
uniform sampler2D OlderPosition;
uniform sampler2D OlderNormal;
uniform sampler2D MaterialDepth;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialAlbedo;
uniform sampler2D SceneDepth;
layout(std140) uniform PathTraceSettings {mat4 PtInvProjection;mat4 PtViewToWorld;vec4 PtControls;};
layout(std140) uniform PathTraceHistory {mat4 PreviousClip;vec4 CameraDelta;mat4 OlderClip;vec4 OlderDelta;vec4 BatchTransition;vec4 CacheCamera;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 result;
float viewDepth(vec2 uv,float d){vec4 p=PtInvProjection*vec4(uv*2-1,d,1);return -p.z/p.w;}
void reject(vec3 color){if(PtControls.z>.5)result=vec4(color,1);}
// Both observations are validated against the current surface before interpolation.
bool lookup(sampler2D radiance,sampler2D positions,sampler2D normals,mat4 clip,vec3 deltaCamera,vec3 world,vec3 surfaceNormal,out vec3 light,out vec3 rejection){
 light=vec3(0);rejection=vec3(0,1,1);
 vec3 previousWorld=world+deltaCamera;vec4 projected=clip*vec4(previousWorld,1);
 if(projected.w<=0){rejection=vec3(0,0,1);return false;}vec2 historyUv=projected.xy/projected.w*.5+.5;
 if(any(lessThan(historyUv,vec2(0))) || any(greaterThanEqual(historyUv,vec2(1)))){rejection=vec3(0,0,1);return false;}
 ivec2 size=textureSize(radiance,0);vec2 uv=historyUv*vec2(size)-.5;ivec2 base=ivec2(floor(uv));light=vec3(0);float total=0;bool normalRejected=false;
 for(int y=0;y<2;y++)for(int x=0;x<2;x++){
  ivec2 q=clamp(base+ivec2(x,y),ivec2(0),size-1);vec4 guide=texelFetch(positions,q,0);vec3 normal=texelFetch(normals,q,0).xyz;
  vec3 delta=guide.xyz-previousWorld;float plane=abs(dot(normal,delta));float tolerance=.12+.005*length(world);
  if(guide.w>.5 && dot(normal,surfaceNormal)<=.9)normalRejected=true;
  float weight=guide.w>.5 && dot(normal,surfaceNormal)>.9 && plane<tolerance && length(delta)<2.0 ? exp(-plane*plane/(tolerance*tolerance)) : 0;
  vec2 b=1-abs(uv-vec2(base+ivec2(x,y)));weight*=max(b.x,0)*max(b.y,0);
  light+=texelFetch(radiance,q,0).rgb*weight;total+=weight;
 }
 // A sparse low-res footprint can contain only invalid pixels even though a
 // compatible surface is immediately beside it. Use a bounded same-plane
 // gather before dropping the contribution completely; never cross normals.
 if(total<1e-5){
  for(int y=-1;y<=2;y++)for(int x=-1;x<=2;x++){
   ivec2 q=base+ivec2(x,y);if(any(lessThan(q,ivec2(0))) || any(greaterThanEqual(q,size)))continue;
   vec4 guide=texelFetch(positions,q,0);vec3 normal=texelFetch(normals,q,0).xyz;
   vec3 delta=guide.xyz-previousWorld;float plane=abs(dot(normal,delta));float tolerance=.12+.005*length(world);
   if(guide.w<=.5 || dot(normal,surfaceNormal)<=.9 || plane>=tolerance || length(delta)>=2.0)continue;
   float weight=exp(-dot(uv-vec2(q),uv-vec2(q)))*exp(-plane*plane/(tolerance*tolerance));
   light+=texelFetch(radiance,q,0).rgb*weight;total+=weight;
  }
 }
 if(total<=1e-5){rejection=normalRejected?vec3(1,0,1):vec3(0,1,1);return false;}
 light/=total;return true;
}
// Reuse only sampled, coplanar static block faces; no fabricated light for unknown surfaces.
bool cachedLight(vec3 world,vec3 normal,out vec3 light){
 light=vec3(0);if(CacheCamera.w<.5)return false;
 int axis=abs(normal.x)>.999?0:abs(normal.y)>.999?1:abs(normal.z)>.999?2:-1;if(axis<0)return false;
 int face=axis*2+(normal[axis]<0.0?1:0);
 vec3 absolute=world+CacheCamera.xyz;ivec3 cell=ivec3(floor((absolute+normal*.02)/2.0));float total=0;
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
  ivec3 q=cell;q[(axis+1)%3]+=x;q[(axis+2)%3]+=y;
  uint slot=(uint(q.x)*73856093u ^ uint(q.y)*19349663u ^ uint(q.z)*83492791u ^ uint(face)*2654435761u)&16383u;
  ivec2 uv=ivec2(int(slot)&127,int(slot)>>7);vec4 tag=texelFetch(CachedGiTags,uv,0);
  if(any(notEqual(tag.xyz,vec3(q)))||int(floor(tag.w))!=face+1)continue;
  float plane=float(q[axis])*2.0+fract(tag.w)*32.0;
  if(abs(absolute[axis]-plane)>.12)continue;
  float weight=x==0&&y==0?4.0:1.0;
  light+=texelFetch(CachedGiLight,uv,0).rgb*weight;total+=weight;
 }
 if(total==0.0)return false;
 vec3 a=texture(MaterialAlbedo,texCoord).rgb;
 a=mix(a/12.92,pow((a+.055)/1.055,vec3(2.4)),greaterThan(a,vec3(.04045)));
 light=light/total*a;return true;
}
void main(){
 result=texture(CurrentHdr,texCoord);
 if(PbrControls.z>.5)return;
 float d=texture(MaterialDepth,texCoord).r,scene=texture(SceneDepth,texCoord).r;
 vec4 n=texture(MaterialNormal,texCoord)*vec4(2,2,2,3)-vec4(1,1,1,0);
 int flags=int(round(texture(MaterialAlbedo,texCoord).a*255));
 if(d<=0 || scene<=0 || n.a<.5 || (flags&21)!=0){reject(vec3(1,0,0));return;}
 float zs=viewDepth(texCoord,scene),zm=viewDepth(texCoord,d);
 if(abs(zm-zs)>max(.01,.001*abs(zs))){reject(vec3(1,.4,0));return;}

 vec4 p=PtInvProjection*vec4(texCoord*2-1,scene,1);p/=p.w;vec3 world=(PtViewToWorld*vec4(p.xyz,0)).xyz;
 vec3 incoming,older,reason=vec3(1,1,0),olderReason;
 bool hasIncoming=PtControls.w>.5 && lookup(PathRadiance,PathPosition,PathNormal,PreviousClip,CameraDelta.xyz,world,normalize(n.xyz),incoming,reason);
 bool hasOlder=false;
 if(BatchTransition.y>.5 && (BatchTransition.x<1 || !hasIncoming))
  hasOlder=lookup(OlderRadiance,OlderPosition,OlderNormal,OlderClip,OlderDelta.xyz,world,normalize(n.xyz),older,olderReason);
 vec3 cached;bool hasCached=(flags&24)==0 && cachedLight(world,normalize(n.xyz),cached);
 if(!hasIncoming && !hasOlder && !hasCached){reject(reason);return;}
 if(PtControls.z>.5){result=vec4(0,1,0,1);return;}
 vec3 light;
 if(hasIncoming && hasOlder)light=mix(older,incoming,BatchTransition.x);
 else if(hasOlder)light=older;
 // No compatible old surface: use the first estimate at full strength.
 else if(hasIncoming)light=incoming;
 else light=cached;
 // The existing worker supplies diffuse GI only. Conductors need a later specular tracer.
 if(PbrControls.x>.5){ivec2 id=ivec2(round(texture(MaterialPbr,texCoord).rg*255.0));vec4 profile=texelFetch(MaterialTable,id,0);light*=profile.g*255.0>=229.5?0.0:1.0-profile.g;}
 float fade=1-smoothstep(16,24,length(world));
 result.rgb=PtControls.y>.5?light*fade:result.rgb+light*fade*PtControls.x;
}
