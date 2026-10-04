#include <optix.h>
#include <optix_device.h>
#include "contract.h"
#include "surface.h"
extern "C" __constant__ RtParams params;
static __forceinline__ __device__ float3 v(float x,float y,float z){return make_float3(x,y,z);}
static __forceinline__ __device__ float3 add(float3 a,float3 b){return v(a.x+b.x,a.y+b.y,a.z+b.z);}
static __forceinline__ __device__ float3 mul(float3 a,float b){return v(a.x*b,a.y*b,a.z*b);}
static __forceinline__ __device__ float3 prod(float3 a,float3 b){return v(a.x*b.x,a.y*b.y,a.z*b.z);}
static __forceinline__ __device__ float dot3(float3 a,float3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
static __forceinline__ __device__ float3 norm(float3 a){return mul(a,rsqrtf(fmaxf(dot3(a,a),1.e-15f)));}
static __forceinline__ __device__ float3 cross3(float3 a,float3 b){return v(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
static __forceinline__ __device__ float linear(float x){return x<=.04045f?x/12.92f:powf((x+.055f)/1.055f,2.4f);}
static __forceinline__ __device__ float3 rgb(unsigned c){return v(linear((c&255)/255.f),linear(((c>>8)&255)/255.f),linear(((c>>16)&255)/255.f));}
static __forceinline__ __device__ float random(unsigned& state){state=state*1664525u+1013904223u;return (state>>8)*0x1p-24f;}
static __forceinline__ __device__ RtPayload* payload(){return reinterpret_cast<RtPayload*>((static_cast<unsigned long long>(optixGetPayload_1())<<32)|optixGetPayload_0());}
static __forceinline__ __device__ void countOperation(int kind){unsigned i=params.launchOffset+optixGetLaunchIndex().x;if(params.counters&&i<(unsigned)(params.width*params.height))atomicAdd(params.counters+i*9+kind,1u);}
static __forceinline__ __device__ RtPayload trace(float3 o,float3 d,float maximum=512.f){
 countOperation(0);RtPayload p={};unsigned long long address=reinterpret_cast<unsigned long long>(&p);unsigned low=(unsigned)address,high=(unsigned)(address>>32);
 optixTrace(params.scene,o,d,0.f,maximum,0,255,OPTIX_RAY_FLAG_NONE,0,1,0,low,high);return p;
}
static __forceinline__ __device__ float3 spawn(const RtPayload& hit,float3 direction){auto p=rt::offsetRayOrigin(rt::V(hit.p.x,hit.p.y,hit.p.z),rt::V(hit.geometryNormal.x,hit.geometryNormal.y,hit.geometryNormal.z),rt::V(direction.x,direction.y,direction.z));return v(p.x,p.y,p.z);}
static __forceinline__ __device__ unsigned sample(unsigned* map,int w,int h,float2 uv){int x=max(0,min(w-1,(int)(uv.x*w))),y=max(0,min(h-1,(int)(uv.y*h)));return map[y*w+x];}
static __forceinline__ __device__ unsigned hitTexture(const RtHitData* data,float2 uv){if(data->textureSlot<0)return sample(params.atlas,params.atlasWidth,params.atlasHeight,uv);int slot=data->textureSlot,x=(slot&7)*256+max(0,min(255,(int)(uv.x*256))),y=(slot>>3)*256+max(0,min(255,(int)(uv.y*256)));return params.entityAtlas[y*2048+x];}
static __forceinline__ __device__ float3 cosine(float3 n,unsigned& seed){float r=sqrtf(random(seed)),a=6.283185307f*random(seed);float3 t=norm(cross3(fabsf(n.y)<.9f?v(0,1,0):v(1,0,0),n)),b=cross3(n,t);return norm(add(add(mul(t,r*cosf(a)),mul(b,r*sinf(a))),mul(n,sqrtf(fmaxf(0,1-r*r)))));}
static __forceinline__ __device__ float hash2(float x,float y){x=x-floorf(x/128)*128;y=y-floorf(y/128)*128;float t=sinf(x*127.1f+y*311.7f)*43758.5453f;return t-floorf(t);}
static __forceinline__ __device__ float noise2(float x,float y){float ix=floorf(x),iy=floorf(y),fx=x-ix,fy=y-iy;fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);float a=hash2(ix,iy)*(1-fx)+hash2(ix+1,iy)*fx,b=hash2(ix,iy+1)*(1-fx)+hash2(ix+1,iy+1)*fx;return a*(1-fy)+b*fy;}
static __forceinline__ __device__ float3 waterNormal(float3 normal,float3 p){if(fabsf(normal.y)<.8f)return normal;float x=fmodf(p.x,16384.f),z=fmodf(p.z,16384.f),sx=0,sz=0;const float kx[3]={1.7f,.64f,-3.14f},kz[3]={.51f,2.38f,1.07f},weights[3]={.45f,.32f,.23f};
 for(int i=0;i<3;i++){float len=sqrtf(kx[i]*kx[i]+kz[i]*kz[i]),angle=x*kx[i]+z*kz[i]-sqrtf(9.81f*len)*params.time+i*1.73f,amplitude=cosf(angle)*weights[i]/len;sx+=kx[i]*amplitude;sz+=kz[i]*amplitude;}
 float qx=x*6.7f+params.time*.31f,qz=z*6.7f-params.time*.19f,e=.13f;sx+=.28f*(noise2(qx+e,qz)-noise2(qx-e,qz))/(2*e);sz+=.28f*(noise2(qx,qz+e)-noise2(qx,qz-e))/(2*e);float rx=x*1.3f,rz=z*1.3f,cx=floorf(rx),cz=floorf(rz),lx=rx-cx-.5f,lz=rz-cz-.5f,radius=sqrtf(lx*lx+lz*lz),phase=params.cloudWind*4+hash2(cx,cz);phase-=floorf(phase);float ring=radius-phase*.65f,amplitude=expf(-ring*ring*220)*(1-phase)*.045f*params.rain*params.rainRipples;
 float distance=sqrtf(dot3(add(p,mul(params.camera,-1)),add(p,mul(params.camera,-1)))),microFade=fmaxf(0,1-distance/96);
 sx=sx*params.waveStrength+(radius>.001f?lx/radius*amplitude:0)*microFade;sz=sz*params.waveStrength+(radius>.001f?lz/radius*amplitude:0)*microFade;return norm(add(normal,v(sx,0,sz)));
}
static __forceinline__ __device__ float smooth(float a,float b,float x){float t=fminf(1,fmaxf(0,(x-a)/(b-a)));return t*t*(3-2*t);}
static __forceinline__ __device__ float cloudDensity(float3 world){float x=fmodf(world.x,16384.f)+params.cloudWind,z=fmodf(world.z,16384.f)+params.cloudWind*.5f,altitude=world.y-params.cloudAltitude;if(altitude<0||altitude>32)return 0;float shape=0;
 for(int y=0;y<2;y++)for(int k=0;k<2;k++){float cx=floorf(x/8+(k==0?-.12f:.12f)),cz=floorf(z/8+(y==0?-.12f:.12f));float noise=noise2(cx*.09f,cz*.09f),occupied=noise>=.48f-params.rain*.14f?1:0,height=4*(3+floorf(noise*5));float face=fmaxf(fmaxf(fabsf(x-(cx+.5f)*8)-4,fabsf(z-(cz+.5f)*8)-4),altitude-height);shape=fmaxf(shape,occupied*(1-smooth(-.7f,.8f,face)));}
 shape*=smooth(0,3,altitude)*(1-smooth(25,32,altitude));float detail=noise2(x*.47f+world.y*.31f,z*.47f-world.y*.17f);return fmaxf(0,shape*(.65f+.35f*noise2(floorf(x/8)*.31f+floorf(world.y/4),floorf(z/8)*.31f+floorf(world.y/4)))-(1-detail)*.27f);
}
static __forceinline__ __device__ float cloudVisibility(float3 p){if(params.cloudShadows<.5f||params.sun.y<.08f||p.y>=params.cloudAltitude)return 1;float t=(params.cloudAltitude-p.y)/params.sun.y;float3 projected=add(p,mul(params.sun,t));float density=0;for(int i=0;i<4;i++)density+=cloudDensity(v(projected.x,params.cloudAltitude+4+i*8,projected.z));return expf(-density*(.4f+.25f*params.rain));}
static __forceinline__ __device__ float3 causticIrradiance(float3 p,float3 n);
#include "transport.cuh"
#include "caustics.cuh"
static __forceinline__ __device__ float3 visibility(float3 o,float3 d){return mul(lightVisibility(o,d,512),cloudVisibility(o));}
extern "C" __global__ void __miss__radiance(){payload()->hit=0;}
extern "C" __global__ void __anyhit__surface(){
 const auto* data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());int i=optixGetPrimitiveIndex()*3;float2 b=optixGetTriangleBarycentrics();const auto& a=data->vertices[i];const auto& c=data->vertices[i+1];const auto& d=data->vertices[i+2];
 if(a.flags&1){float2 uv=make_float2(a.uv.x*(1-b.x-b.y)+c.uv.x*b.x+d.uv.x*b.y,a.uv.y*(1-b.x-b.y)+c.uv.y*b.x+d.uv.y*b.y);if(data->textureSlot<0&&((sample(params.ids,params.idsWidth,params.idsHeight,uv)>>24)&3)>0)return;if(!rt::cutoutVisible(hitTexture(data,uv)>>24,a.tint>>24,a.flags))optixIgnoreIntersection();}
}
extern "C" __global__ void __closesthit__surface(){
 countOperation(6);auto* p=payload();p->hit=1;p->distance=optixGetRayTmax();p->p=add(optixGetWorldRayOrigin(),mul(optixGetWorldRayDirection(),p->distance));
 const auto* data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());int i=optixGetPrimitiveIndex()*3;float2 b=optixGetTriangleBarycentrics();auto a=data->vertices[i],c=data->vertices[i+1],d=data->vertices[i+2];float w=1-b.x-b.y;
 p->n=norm(optixTransformNormalFromObjectToWorldSpace(add(add(mul(a.n,w),mul(c.n,b.x)),mul(d.n,b.y))));float2 uv=make_float2(a.uv.x*w+c.uv.x*b.x+d.uv.x*b.y,a.uv.y*w+c.uv.y*b.x+d.uv.y*b.y);
 unsigned tex=hitTexture(data,uv),id=data->textureSlot<0?sample(params.ids,params.idsWidth,params.idsHeight,uv):0,profile=data->textureSlot<0?params.lut[id&65535]:0xff200a33;
 if(dot3(p->n,p->n)<.1f)p->n=norm(optixTransformNormalFromObjectToWorldSpace(cross3(add(c.p,mul(a.p,-1)),add(d.p,mul(a.p,-1)))));
 p->geometryNormal=norm(optixTransformNormalFromObjectToWorldSpace(cross3(add(c.p,mul(a.p,-1)),add(d.p,mul(a.p,-1)))));
 if(dot3(p->n,p->geometryNormal)<0)p->n=mul(p->n,-1);
 rt::Frame baseFrame(rv(p->n));p->tangent=cv(baseFrame.t);p->bitangent=cv(baseFrame.b);
 unsigned packedNormal=data->textureSlot<0?sample(params.normalMap,params.idsWidth,params.idsHeight,uv):0xff008080;
 float nx=(packedNormal&255)/127.5f-1,ny=((packedNormal>>8)&255)/127.5f-1;
 float3 e1=add(c.p,mul(a.p,-1)),e2=add(d.p,mul(a.p,-1));float ux=c.uv.x-a.uv.x,uy=c.uv.y-a.uv.y,vx=d.uv.x-a.uv.x,vy=d.uv.y-a.uv.y,det=ux*vy-uy*vx;
 if(fabsf(det)>1.e-10f){float3 tangent=optixTransformVectorFromObjectToWorldSpace(mul(add(mul(e1,vy),mul(e2,-uy)),1/det));tangent=norm(add(tangent,mul(p->n,-dot3(tangent,p->n))));float3 bitangent=optixTransformVectorFromObjectToWorldSpace(mul(add(mul(e2,ux),mul(e1,-vx)),1/det));bitangent=norm(add(bitangent,mul(p->n,-dot3(bitangent,p->n))));p->tangent=tangent;p->bitangent=bitangent;p->n=norm(add(add(mul(tangent,nx),mul(bitangent,ny)),mul(p->n,sqrtf(fmaxf(0,1-nx*nx-ny*ny)))));}
 if(dot3(p->n,p->geometryNormal)<=0)p->n=p->geometryNormal;
 p->color=prod(rgb(tex),rgb(a.tint));p->roughness=1-(profile&255)/255.f;p->f0=((profile>>8)&255)/255.f;p->metal=(profile>>8)&255;p->flags=a.flags;
 unsigned type=(id>>24)&3;if(type==2)p->n=waterNormal(p->n,p->p);p->transmission=type?1:0;p->ior=type==2?1.333f:1.5f;p->absorption=type==2?v(.16f,.06f,.035f):v(-logf(fmaxf(.05f,p->color.x))*.7f,-logf(fmaxf(.05f,p->color.y))*.7f,-logf(fmaxf(.05f,p->color.z))*.7f);
 p->emission=mul(p->color,(((id>>24)&4)?((id>>16)&255)/254.f:(a.flags>>16)/15.f)*2.4f);
 p->objectId=optixGetInstanceId();p->primitiveId=optixGetPrimitiveIndex();p->bsdf=decodeMaterial(id,profile,p->color,p->flags);
 p->bsdf.emission=rv(p->emission);p->roughness=sqrtf(p->bsdf.microfacetAlpha);p->transmission=rt::dielectric(p->bsdf)?p->bsdf.transmission:0;p->ior=p->bsdf.ior;p->absorption=cv(p->bsdf.sigmaA);
 if(type==1&&rt::maxComponent(p->bsdf.sigmaA)==0)p->bsdf.sigmaA=rv(p->absorption=v(-logf(fmaxf(.001f,p->color.x))*.7f,-logf(fmaxf(.001f,p->color.y))*.7f,-logf(fmaxf(.001f,p->color.z))*.7f));
 if(p->flags&8&&rt::dielectric(p->bsdf))p->bsdf.type=rt::THIN_DIELECTRIC;
 // Medium identity excludes per-texel roughness IDs; textured entry/exit share their object/class/IOR volume.
 p->bsdf.mediumId=1+(p->objectId<<16)+(p->bsdf.type<<8)+(unsigned)roundf(p->ior*255/3);
 if(params.rain>0&&!rt::dielectric(p->bsdf)&&p->bsdf.type!=rt::DIFFUSE_TRANSMISSION){auto& m=p->bsdf;float skyAccess=((a.flags>>8)&15)/15.f;float wet=params.rain*skyAccess*smooth(.2f,.9f,p->geometryNormal.y);m.coatWeight=fmaxf(m.coatWeight,wet*(1-m.porosity)*.6f);if(wet>0){m.coatIOR=1.333f;m.coatAlpha=.025f;}m.baseColor=m.baseColor*(1-wet*m.porosity*.18f);if(m.coatWeight>0)m.type=rt::metal(m)?rt::COATED_CONDUCTOR:rt::COATED_DIFFUSE;}


}
// Isolated A/B geometry kernel. The application world continues to use exact compiled triangles.
extern "C" __global__ void __intersection__cube(){auto data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());auto cube=reinterpret_cast<RtCube*>(data->vertices)[optixGetPrimitiveIndex()];float3 o=optixGetObjectRayOrigin(),d=optixGetObjectRayDirection();float3 lo=v((cube.minimum.x-o.x)/d.x,(cube.minimum.y-o.y)/d.y,(cube.minimum.z-o.z)/d.z),hi=v((cube.maximum.x-o.x)/d.x,(cube.maximum.y-o.y)/d.y,(cube.maximum.z-o.z)/d.z);float near=fmaxf(fmaxf(fminf(lo.x,hi.x),fminf(lo.y,hi.y)),fminf(lo.z,hi.z)),far=fminf(fminf(fmaxf(lo.x,hi.x),fmaxf(lo.y,hi.y)),fmaxf(lo.z,hi.z));if(near<=far){float t=near>=optixGetRayTmin()?near:far;if(t>=optixGetRayTmin()&&t<=optixGetRayTmax())optixReportIntersection(t,0);}}
extern "C" __global__ void __closesthit__cube(){auto p=payload();p->hit=1;p->distance=optixGetRayTmax();}
// Probe coordinates are world anchored. Turning the camera never changes tags or admission.
static __forceinline__ __device__ float3 probeLight(float3 p,float3 n){
 for(int cascade=0;cascade<3;cascade++){float spacing=4.f*(1<<cascade);int cx=(int)floorf(p.x/spacing),cy=(int)floorf(p.y/spacing),cz=(int)floorf(p.z/spacing);float3 total=v(0,0,0);float weights=0;
  for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){int gx=cx+x,gy=cy+y,gz=cz+z;int slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));float4* a=params.probes+slot*8;float3 expected=v(gx*spacing,gy*spacing,gz*spacing);
   if(a[0].w<=0||fabsf(a[0].x-expected.x)+fabsf(a[0].y-expected.y)+fabsf(a[0].z-expected.z)>.1f)continue;
   float3 probePosition=add(expected,v(a[6].x,a[6].y,a[6].z));float3 delta=add(p,mul(probePosition,-1));float distance=sqrtf(dot3(delta,delta));float fx=p.x/spacing-cx,fy=p.y/spacing-cy,fz=p.z/spacing-cz;float w=(x?fx:1-fx)*(y?fy:1-fy)*(z?fz:1-fz);float variance=fmaxf(.01f,a[5].y-a[5].x*a[5].x),excess=fmaxf(0,distance-a[5].x-spacing*.2f);float visibility=variance/(variance+excess*excess);w*=fmaxf(.005f,visibility*visibility*visibility);w*=fmaxf(.05f,dot3(n,norm(mul(delta,-1))));
   float3 ir=add(v(a[1].x,a[1].y,a[1].z),add(mul(v(a[2].x,a[2].y,a[2].z),n.x),add(mul(v(a[3].x,a[3].y,a[3].z),n.y),mul(v(a[4].x,a[4].y,a[4].z),n.z))));total=add(total,mul(v(fmaxf(0,ir.x),fmaxf(0,ir.y),fmaxf(0,ir.z)),w));weights+=w;
  }if(weights>0)return mul(total,1/weights);
 }return mul(params.sky,.25f); // explicit environment base, never black while cache fills
}
extern "C" __global__ void __raygen__lighting(){
 unsigned i=params.launchOffset+optixGetLaunchIndex().x,seed=i*9781u+params.frame*6271u+1;
 if(params.mode==13){int row=i;if(row>=rt::EnvironmentHeight)return;float total=0;for(int x=0;x<rt::EnvironmentWidth;x++){auto c=params.environmentMap[row*rt::EnvironmentWidth+x];total+=fmaxf(0,c.x*.2126f+c.y*.7152f+c.z*.0722f)*rt::environmentSolidAngle(row);params.environmentCdf[row*rt::EnvironmentWidth+x]=total;}return;}
 if(params.mode==14){if(i)return;float total=0;for(int y=0;y<rt::EnvironmentHeight;y++){total+=params.environmentCdf[y*rt::EnvironmentWidth+rt::EnvironmentWidth-1];params.environmentCdf[rt::EnvironmentWidth*rt::EnvironmentHeight+y]=total;}return;}
 if(params.mode==12){if(i<(unsigned)(params.width*params.height))for(int kind=0;kind<9;kind++)atomicAdd(params.counterTotals+kind,params.counters[i*9+kind]);return;}
 if(params.mode==10){float cell=.25f;int cx=(int)floorf(params.camera.x/cell)-32,cz=(int)floorf(params.camera.z/cell)-32,x=cx+(i&63),z=cz+((i>>6)&63),slot=(x&63)|((z&63)<<6);auto record=params.caustics+slot*2;record[0]=make_float4(x*cell,0,z*cell,0);record[1]=make_float4(0,0,0,0);return;}
 if(params.mode==8){causticPhoton(i,seed);return;}
 if(params.mode==11){auto a=params.caustics+i*2,b=params.causticHistory+i*2;bool same=a[0].w>0&&b[0].w>0&&fabsf(a[0].x-b[0].x)+fabsf(a[0].z-b[0].z)<.01f&&fabsf(a[0].y-b[0].y)<.1f;if(same){a[1].x=.2f*a[1].x+.8f*b[1].x;a[1].y=.2f*a[1].y+.8f*b[1].y;a[1].z=.2f*a[1].z+.8f*b[1].z;}b[0]=a[0];b[1]=a[1];return;}
 if(params.mode==7){float3 origin=v(random(seed)*5,10,random(seed)*5),destination=v(random(seed)*5,0,random(seed)*5);auto h=trace(origin,norm(add(destination,mul(origin,-1))));params.diffuse[i]=make_float4(h.hit?h.distance:-1,0,0,0);return;}
 if(params.mode==2){auto a=params.probes+i*8;float margin=8.f*(1<<(i/512));float3 p=v(a[0].x,a[0].y,a[0].z);
  if(p.x>=params.invalidateMin.x-margin&&p.x<=params.invalidateMax.x+margin&&p.y>=params.invalidateMin.y-margin&&p.y<=params.invalidateMax.y+margin&&p.z>=params.invalidateMin.z-margin&&p.z<=params.invalidateMax.z+margin){a[0].w=0;a[7].w+=1;}return;
 }
 if(params.mode==1){int cascade=i/512;float spacing=4.f*(1<<cascade);int cx=(int)floorf(params.camera.x/spacing)-4,cy=(int)floorf(params.camera.y/spacing)-4,cz=(int)floorf(params.camera.z/spacing)-4;
  int gx=cx+(i&7),gy=cy+((i>>3)&7),gz=cz+((i>>6)&7);int slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));float3 p=v(gx*spacing,gy*spacing,gz*spacing);auto a=params.probes+slot*8;
  bool same=a[0].w>0&&fabsf(a[0].x-p.x)+fabsf(a[0].y-p.y)+fabsf(a[0].z-p.z)<.1f;
  unsigned quality=(params.options>>16)&3;int period=quality==0?32:quality==1?24:16,rays=quality==0?8:quality==1?12:16;if(((i+params.frame)%period)!=0)return;
  float3 origin=p;if(same)origin=add(origin,v(a[6].x,a[6].y,a[6].z));
  else{float nearest=2.f;float3 escape=v(0,0,0);const float3 axes[6]={v(1,0,0),v(-1,0,0),v(0,1,0),v(0,-1,0),v(0,0,1),v(0,0,-1)};for(int axis=0;axis<6;axis++){auto h=trace(origin,axes[axis],2);if(h.hit&&h.transmission<=0&&dot3(h.geometryNormal,axes[axis])>.5f&&h.distance<nearest){nearest=h.distance;escape=mul(axes[axis],h.distance+.05f);}}origin=add(origin,escape);a[6]=make_float4(escape.x,escape.y,escape.z,0);}
  float3 sh[4]={v(0,0,0),v(0,0,0),v(0,0,0),v(0,0,0)};float mean=0,second=0,luminance=0,luminanceSecond=0;
  for(int r=0;r<rays;r++){float z=1-2*random(seed),t=6.2831853f*random(seed),rad=sqrtf(fmaxf(0,1-z*z));float3 dir=v(rad*cosf(t),z,rad*sinf(t));RtPayload h=trace(origin,dir);float distance=h.hit?h.distance:128.f;mean+=distance/rays;second+=distance*distance/rays;
   float3 light=incoming(origin,dir,seed,3,true);float l=dot3(light,v(.2126f,.7152f,.0722f));luminance+=l/rays;luminanceSecond+=l*l/rays;sh[0]=add(sh[0],mul(light,1.f/rays));sh[1]=add(sh[1],mul(light,2*dir.x/rays));sh[2]=add(sh[2],mul(light,2*dir.y/rays));sh[3]=add(sh[3],mul(light,2*dir.z/rays));
  }float alpha=same?.15f:1;a[0]=make_float4(p.x,p.y,p.z,1);for(int k=0;k<4;k++){float3 old=v(a[k+1].x,a[k+1].y,a[k+1].z),value=add(mul(old,1-alpha),mul(sh[k],alpha));a[k+1]=make_float4(value.x,value.y,value.z,0);}a[5]=make_float4(mean,second,params.frame,same?a[5].w+1:1);a[7]=make_float4(luminance,luminanceSecond,fmaxf(0,luminanceSecond-luminance*luminance),same?a[7].w:a[7].w+1);return;
 }
 int count=params.width*params.height;if(i>=count)return;
 if(params.options&(1u<<25)){
  if(params.mode!=0)return;
  float x=2*((i%params.width+random(seed))/params.width)-1,y=2*((i/params.width+random(seed))/params.height)-1;
  auto m=params.inverseCamera;float z=.00001f,w=m[3]*x+m[7]*y+m[11]*z+m[15];
  float3 ray=norm(v((m[0]*x+m[4]*y+m[8]*z+m[12])/w,(m[1]*x+m[5]*y+m[9]*z+m[13])/w,(m[2]*x+m[6]*y+m[10]*z+m[14])/w));
  float3 result=incoming(params.camera,ray,seed,12,false);int slot=i;
  auto old=params.referenceSamples?params.referenceSum[slot]:make_float4(0,0,0,0);
  float weight=params.referenceSamples+1;float3 sum=add(v(old.x,old.y,old.z),result);
  params.referenceSum[slot]=make_float4(sum.x,sum.y,sum.z,weight);result=mul(sum,1/weight);
  params.diffuse[i]=make_float4(result.x,result.y,result.z,1);params.specular[i]=params.transmission[i]=make_float4(0,0,0,0);return;
 }

 if(params.mode==4){params.previousKey[i]=params.surfaceKey[i];params.previousSignal[i]=make_float4(params.specular[i].w,params.transmission[i].w,params.material[i].x,params.material[i].z);float4 p=params.position[i];params.previousPosition[i]=make_float4(p.x+params.camera.x,p.y+params.camera.y,p.z+params.camera.z,p.w);params.previousNormal[i]=params.normal[i];return;}float4 pp=params.position[i],nn=params.normal[i],aa=params.albedo[i],mm=params.material[i];
 if(params.mode==0){params.surfaceKey[i]=make_float4(-1,-1,0,0);params.sunVisibility[i]=make_float4(1,1,1,0);params.flow[i*2]=params.flow[i*2+1]=0;params.flowTrust[i]=0;params.diffuse[i]=params.specular[i]=params.transmission[i]=make_float4(0,0,0,0);}if(pp.w<.5f)return;float3 p=add(v(pp.x,pp.y,pp.z),params.camera),n=norm(v(nn.x,nn.y,nn.z)),view=norm(v(-pp.x,-pp.y,-pp.z));float3 color=v(aa.x,aa.y,aa.z);
 float3 sunT=v(1,1,1);bool dielectric=false;
 if(params.mode==0){float radial=sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z);auto primary=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),radial+.1f);bool coverage=primary.hit&&(primary.transmission>0||fabsf(primary.distance-radial)<.12f+.003f*radial);
  if(primary.hit&&primary.transmission>0&&(params.options&4)){dielectric=true;p=primary.p;n=primary.geometryNormal;color=primary.color;float3 relative=add(p,mul(params.camera,-1));pp=make_float4(relative.x,relative.y,relative.z,1);nn=make_float4(n.x,n.y,n.z,2);params.material[i]=make_float4(primary.bsdf.microfacetAlpha,primary.f0,primary.bsdf.materialId,1);params.position[i]=pp;params.normal[i]=nn;params.albedo[i]=make_float4(color.x,color.y,color.z,1);coverage=true;}
  if(coverage){params.surfaceKey[i]=make_float4(primary.bsdf.materialId,primary.objectId,primary.distance,primary.ior);
   if(!dielectric){n=primary.geometryNormal;nn=make_float4(n.x,n.y,n.z,1);params.normal[i]=nn;params.material[i]=make_float4(primary.bsdf.microfacetAlpha,primary.f0,primary.bsdf.materialId,0);}}
if(coverage){sunT=visibility(spawn(primary,params.sun),params.sun);if((params.options&33)==33&&dot3(causticIrradiance(p,n),v(1,1,1))>0)sunT=v(0,0,0);}params.sunVisibility[i]=make_float4(sunT.x,sunT.y,sunT.z,coverage?1:0);if(!coverage&&params.debug!=13&&params.debug!=7&&params.debug!=8)return;
 }
 float3 oldRelative=add(p,mul(params.previousCamera,-1));auto mat=params.previousClip;
 float ox=mat[0]*oldRelative.x+mat[4]*oldRelative.y+mat[8]*oldRelative.z+mat[12],oy=mat[1]*oldRelative.x+mat[5]*oldRelative.y+mat[9]*oldRelative.z+mat[13],ow=mat[3]*oldRelative.x+mat[7]*oldRelative.y+mat[11]*oldRelative.z+mat[15];
 if(params.mode==0&&!params.referenceSpp&&ow>.00001f){float px=(ox/ow*.5f+.5f)*params.width-.5f,py=(oy/ow*.5f+.5f)*params.height-.5f;int x=(int)roundf(px),y=(int)roundf(py);
  if(x>=0&&x<params.width&&y>=0&&y<params.height){int j=y*params.width+x;float4 op=params.previousPosition[j],on=params.previousNormal[j];float3 delta=add(v(op.x,op.y,op.z),mul(p,-1));float tolerance=.1f+.002f*sqrtf(dot3(v(pp.x,pp.y,pp.z),v(pp.x,pp.y,pp.z)));
   auto oldKey=params.previousKey[j],key=params.surfaceKey[i];if(op.w>.5f&&oldKey.x==key.x&&oldKey.y==key.y&&fabsf(oldKey.z-key.z)<tolerance&&fabsf(dot3(delta,n))<tolerance&&dot3(n,v(on.x,on.y,on.z))>.95f){params.flow[i*2]=(int)(i%params.width)-px;params.flow[i*2+1]=(int)(i/params.width)-py;params.flowTrust[i]=1;}
  }
 }

 if(params.debug>=4&&params.debug<=31){if(params.mode!=0)return;float3 debug=v(0,0,0);unsigned m=(unsigned)roundf(mm.y*255);auto diagnostic=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z)+.1f);if(diagnostic.hit){color=diagnostic.color;n=diagnostic.n;mm.x=diagnostic.roughness;m=diagnostic.metal;}
  if(params.debug==4)debug=color;else if(params.debug==5)debug=v(mm.x,mm.x,mm.x);else if(params.debug==6)debug=m>=230?v(1,.65f,.1f):v(0,0,0);else if(params.debug==7||params.debug==8){auto h=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z)+.1f);float value=params.debug==7?(h.hit?h.transmission:0):(h.hit?h.ior/2.5f:0);debug=v(value,value,value);}else if(params.debug==9)debug=add(mul(n,.5f),v(.5f,.5f,.5f));else if(params.debug==13)debug=params.sunVisibility[i].w>.5f?v(.1f,1,.1f):v(1,.05f,.05f);else if(params.debug==14){unsigned id=(unsigned)mm.z;debug=v((id&255)/255.f,((id>>8)&255)/255.f,0);}else if(params.debug==15)debug=sunT;else if(params.debug==16)debug=v(expf(-.16f*8),expf(-.06f*8),expf(-.035f*8));
  else if(params.debug>=17&&diagnostic.hit){auto m=diagnostic.bsdf;float scalar=0;debug=v(0,0,0);
   if(params.debug==17)debug=v(m.type/9.f,0,1-m.type/9.f);else if(params.debug==18)debug=diagnostic.color;else if(params.debug==19)scalar=m.microfacetAlpha;else if(params.debug==20)scalar=m.f0;else if(params.debug==21)debug=cv(m.eta*(1/4.f));else if(params.debug==22)debug=cv(m.k*(1/8.f));else if(params.debug==23)scalar=m.coatWeight;else if(params.debug==24)scalar=sqrtf(m.coatAlpha);else if(params.debug==25||params.debug==27)debug=cv(m.sigmaA*(1/8.f));else if(params.debug==26)debug=cv(m.sigmaS);else if(params.debug==28)debug=v((m.mediumId&255)/255.f,((m.mediumId>>8)&255)/255.f,0);else if(params.debug==29)scalar=m.porosity;else if(params.debug==30)scalar=m.sss;else if(params.debug==31)debug=diagnostic.emission;else if(params.debug==32)debug=v(0,0,0);if(scalar>0)debug=v(scalar,scalar,scalar);
  }else{for(int cascade=0;cascade<3;cascade++){float spacing=4.f*(1<<cascade);int gx=(int)floorf(p.x/spacing),gy=(int)floorf(p.y/spacing),gz=(int)floorf(p.z/spacing),slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));auto a=params.probes+slot*8;if(a[0].w>0&&fabsf(a[0].x-gx*spacing)+fabsf(a[0].y-gy*spacing)+fabsf(a[0].z-gz*spacing)<.1f){debug=params.debug==10?v(0,1,0):params.debug==11?v(fminf(1,(params.frame-a[5].z)/120),0,0):cascade==0?v(1,0,0):cascade==1?v(0,1,0):v(0,0,1);break;}}}
  params.diffuse[i]=make_float4(debug.x,debug.y,debug.z,1);if(dielectric)params.transmission[i]=make_float4(debug.x,debug.y,debug.z,sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z));return;
 }

 auto first=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(dot3(v(pp.x,pp.y,pp.z),v(pp.x,pp.y,pp.z)))+.15f);if(!first.hit)return;
 if(params.debug>=32){if(params.mode!=0)return;TransportDebug info;auto debugDirection=cosine(first.geometryNormal,seed);float3 diffuse=incoming(spawn(first,debugDirection),debugDirection,seed,8,true,0,&info);float3 out=v(0,0,0);unsigned debug=params.debug;
 if(debug==32)out=info.throughput;else if(debug==33)out=v(info.bounce/8.f,info.bounce/8.f,info.bounce/8.f);else if(debug==34)out=info.kind==0?v(1,1,0):info.kind==1?v(0,.5f,1):v(1,0,1);else if(debug==35)out=v((info.lobe&rt::DIFFUSE_LOBE)?1:0,(info.lobe&rt::GLOSSY)?1:0,(info.lobe&rt::TRANSMISSION)?1:0);else if(debug==36)out=v(info.mis,info.mis,info.mis);else if(debug==37)out=info.secondary;else if(debug==38)out=diffuse;else if(debug==39){auto bs=rt::sampleGlossy(first.bsdf,rt::Frame(rv(first.n)),rv(view),seed);if(bs.pdf>0)out=prod(cv(bs.weight),incoming(spawn(first,cv(bs.wi)),cv(bs.wi),seed,8,false));}else if(debug==40)out=info.emissive;else if(debug==41)out=info.sun;else if(debug==42)out=v(info.bsdfPdf/(1+info.bsdfPdf),0,0);else if(debug==43)out=v(info.lightPdf/(1+info.lightPdf),0,0);else if(debug==44)out=params.sunVisibility[i].w>.5f?v(0,1,0):v(1,0,0);
 params.diffuse[i]=make_float4(out.x,out.y,out.z,1);if(dielectric)params.transmission[i]=make_float4(out.x,out.y,out.z,first.distance);return;}
 unsigned batch=params.referenceSpp?rt::referenceBatch(params.referenceSpp,params.referenceSamples):1;float3 light=v(0,0,0);float signalHitDistance=0;TransportDebug diagnostic;
 for(unsigned r=0;r<batch;r++){
  float3 observation=v(0,0,0);
  if(params.mode==0&&!dielectric&&(params.options&1)&&!rt::metal(first.bsdf)){
   float3 facing=dot3(n,view)>0?n:mul(n,-1);bool thin=first.bsdf.type==rt::DIFFUSE_TRANSMISSION;float t=thin?first.bsdf.transmission:0;
   if((params.options&16)&&!params.referenceSpp){float3 irradiance=add(mul(probeLight(p,facing),1-t),t>0?mul(probeLight(p,mul(facing,-1)),t):v(0,0,0));observation=prod(cv(first.bsdf.baseColor),mul(irradiance,thin?1:1-first.bsdf.f0));}
   else{bool back=thin&&random(seed)<t;float3 direction=cosine(back?mul(facing,-1):facing,seed);rt::Frame f(rv(facing));auto total=rt::evalBsdf(first.bsdf,f,rv(view),rv(direction)),glossy=rt::evalGlossy(first.bsdf,f,rv(view),rv(direction));rt::Vec diffuse=total.f-glossy.f;float probability=thin?(back?t:1-t):1;observation=prod(cv(diffuse*(rt::Pi/fmaxf(.001f,probability))),incoming(spawn(first,direction),direction,seed,params.referenceSpp?8:4,true,0,&diagnostic));}

  }
  if(params.mode==5&&params.sunVisibility[i].w>.5f&&(params.options&2)){
   if(nn.w>1.5f)observation=incoming(params.camera,norm(v(pp.x,pp.y,pp.z)),seed,8,false,1,&diagnostic);
   else{observation=(params.options&64)?primaryGlossyDirect(first,view,seed):v(0,0,0);auto glossy=first.bsdf;rt::Frame frame(rv(dot3(first.n,view)>0?first.n:mul(first.n,-1)),rv(first.tangent),rv(first.bitangent));auto bs=rt::sampleGlossy(glossy,frame,rv(view),seed);if(bs.pdf>0){auto reflectedHit=trace(spawn(first,cv(bs.wi)),cv(bs.wi));signalHitDistance=reflectedHit.hit?reflectedHit.distance:512;observation=add(observation,prod(cv(bs.weight),incoming(spawn(first,cv(bs.wi)),cv(bs.wi),seed,params.referenceSpp?8:4,false,0,&diagnostic,true,(params.options&64)?bs.pdf:0)));}}
  }
  if(params.mode==6&&(params.options&4)&&(rt::dielectric(first.bsdf)||params.underwater>.5f))observation=incoming(params.camera,norm(v(pp.x,pp.y,pp.z)),seed,8,false,rt::dielectric(first.bsdf)?2:0,&diagnostic);
  light=add(light,mul(observation,1/fmaxf(1,batch)));
 }
 if(params.mode==0&&!rt::metal(first.bsdf)&&!rt::dielectric(first.bsdf)&&(params.options&32))light=add(light,prod(cv(first.bsdf.baseColor),mul(causticIrradiance(first.p,first.geometryNormal),1/rt::Pi)));
 float alpha=params.mode==6&&(rt::dielectric(first.bsdf)||params.underwater>.5f)?first.distance:params.mode==6?0:params.mode==5?fmaxf(.001f,signalHitDistance):1;
 float4* output=params.mode==0?params.diffuse:params.mode==5?params.specular:params.transmission;
 if(params.referenceSpp){int slot=(params.mode==0?0:params.mode==5?1:2)*count+i;float4 old=params.referenceSum[slot];if(params.referenceSamples==0)old=make_float4(0,0,0,0);float weight=fminf(params.referenceSamples+batch,params.referenceSpp);float3 sum=add(v(old.x,old.y,old.z),mul(light,batch));params.referenceSum[slot]=make_float4(sum.x,sum.y,sum.z,weight);light=mul(sum,1/fmaxf(1,weight));}
 if(params.mode==5&&params.flowTrust[i]>0){float px=i%params.width-params.flow[i*2],py=i/params.width-params.flow[i*2+1];int x=(int)roundf(px),y=(int)roundf(py);if(x>=0&&x<params.width&&y>=0&&y<params.height){auto previous=params.previousSignal[y*params.width+x];float tolerance=fmaxf(.05f,first.bsdf.microfacetAlpha*fmaxf(previous.x,alpha));if(fabsf(previous.x-alpha)>tolerance||fabsf(previous.z-first.bsdf.microfacetAlpha)>.02f)params.flowTrust[i]=0;}}
 output[i]=make_float4(light.x,light.y,light.z,alpha);
}
