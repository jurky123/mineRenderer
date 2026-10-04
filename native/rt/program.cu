#include <optix.h>
#include <optix_device.h>
#include "contract.h"
extern "C" __constant__ RtParams params;
static __forceinline__ __device__ float3 v(float x,float y,float z){return make_float3(x,y,z);}
static __forceinline__ __device__ float3 add(float3 a,float3 b){return v(a.x+b.x,a.y+b.y,a.z+b.z);}
static __forceinline__ __device__ float3 mul(float3 a,float b){return v(a.x*b,a.y*b,a.z*b);}
static __forceinline__ __device__ float3 prod(float3 a,float3 b){return v(a.x*b.x,a.y*b.y,a.z*b.z);}
static __forceinline__ __device__ float dot3(float3 a,float3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
static __forceinline__ __device__ float3 norm(float3 a){return mul(a,rsqrtf(fmaxf(dot3(a,a),1.e-15f)));}
static __forceinline__ __device__ float3 cross3(float3 a,float3 b){return v(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
static __forceinline__ __device__ float3 reflect3(float3 d,float3 n){return add(d,mul(n,-2*dot3(d,n)));}
static __forceinline__ __device__ float linear(float x){return x<=.04045f?x/12.92f:powf((x+.055f)/1.055f,2.4f);}
static __forceinline__ __device__ float3 rgb(unsigned c){return v(linear((c&255)/255.f),linear(((c>>8)&255)/255.f),linear(((c>>16)&255)/255.f));}
static __forceinline__ __device__ float random(unsigned& state){state=state*1664525u+1013904223u;return (state>>8)*0x1p-24f;}
static __forceinline__ __device__ RtPayload* payload(){return reinterpret_cast<RtPayload*>((static_cast<unsigned long long>(optixGetPayload_1())<<32)|optixGetPayload_0());}
static __forceinline__ __device__ RtPayload trace(float3 o,float3 d,float maximum=512.f){
 RtPayload p={};unsigned long long address=reinterpret_cast<unsigned long long>(&p);unsigned low=(unsigned)address,high=(unsigned)(address>>32);
 optixTrace(params.scene,o,d,.015f,maximum,0,255,OPTIX_RAY_FLAG_NONE,0,1,0,low,high);return p;
}
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
static __forceinline__ __device__ float3 conductor(unsigned metal,float3 color){
 const float3 ns[8]={v(2.9114,2.9497,2.5845),v(.18299,.42108,1.3734),v(1.3456,.96521,.61722),v(3.1071,3.1812,2.323),v(.27105,.67693,1.3164),v(1.91,1.83,1.44),v(2.3757,2.0847,1.8453),v(.15943,.14512,.13547)};
 const float3 ks[8]={v(3.0893,2.9318,2.767),v(3.4242,2.3459,1.7704),v(7.4746,6.3995,5.3031),v(3.3314,3.3291,3.135),v(3.6092,2.6248,2.2921),v(3.51,3.4,3.18),v(4.2655,3.7153,3.1365),v(3.9291,3.19,2.3808)};
 if(metal<230||metal>237)return color;float3 n=ns[metal-230],k=ks[metal-230];return v(((n.x-1)*(n.x-1)+k.x*k.x)/((n.x+1)*(n.x+1)+k.x*k.x),((n.y-1)*(n.y-1)+k.y*k.y)/((n.y+1)*(n.y+1)+k.y*k.y),((n.z-1)*(n.z-1)+k.z*k.z)/((n.z+1)*(n.z+1)+k.z*k.z));
}
static __forceinline__ __device__ float3 fresnel(float3 f,float c){float k=powf(1-fminf(1,fmaxf(0,c)),5);return add(f,mul(add(v(1,1,1),mul(f,-1)),k));}
// Heitz visible-normal GGX sampling. VNDF/pdf cancellation leaves Fresnel * G1(L).
static __forceinline__ __device__ float g1(float cosine,float alpha){
 if(cosine<=0)return 0;float c2=cosine*cosine;return 2*cosine/(cosine+sqrtf(alpha*alpha+(1-alpha*alpha)*c2));
}
static __forceinline__ __device__ float3 ggx(float3 view,float3 n,float rough,unsigned& seed,float3 f0,float3& weight){
 float3 t=norm(cross3(fabsf(n.y)<.9f?v(0,1,0):v(1,0,0),n)),b=cross3(n,t);
 float alpha=fmaxf(.002f,rough*rough);float3 local=v(dot3(view,t),dot3(view,b),fmaxf(.0001f,dot3(view,n)));
 float3 vh=norm(v(alpha*local.x,alpha*local.y,local.z));float lens=vh.x*vh.x+vh.y*vh.y;
 float3 t1=lens>1.e-10f?mul(v(-vh.y,vh.x,0),rsqrtf(lens)):v(1,0,0),t2=cross3(vh,t1);
 float r=sqrtf(random(seed)),phi=6.283185307f*random(seed),x=r*cosf(phi),y=r*sinf(phi),blend=.5f*(1+vh.z);
 y=(1-blend)*sqrtf(fmaxf(0,1-x*x))+blend*y;
 float3 nh=add(add(mul(t1,x),mul(t2,y)),mul(vh,sqrtf(fmaxf(0,1-x*x-y*y))));
 float3 hLocal=norm(v(alpha*nh.x,alpha*nh.y,fmaxf(0,nh.z))),h=norm(add(add(mul(t,hLocal.x),mul(b,hLocal.y)),mul(n,hLocal.z)));
 float3 out=reflect3(mul(view,-1),h);weight=mul(fresnel(f0,dot3(view,h)),g1(dot3(out,n),alpha));return out;
}
// Colored visibility traverses interfaces, not a scalar shadow bit. Boundary pairs measure optical distance.
static __forceinline__ __device__ float3 visibility(float3 o,float3 d){float cloud=cloudVisibility(o);float3 t=v(cloud,cloud,cloud),absorption[4];int depth=0;
 for(int i=0;i<16;i++){RtPayload h=trace(o,d);if(!h.hit)return t;if(h.transmission<=0)return v(0,0,0);
  bool entering=dot3(h.geometryNormal,d)<0;float3 absorb=depth>0?absorption[depth-1]:(!entering&&!(h.flags&8)?h.absorption:v(0,0,0));
  t=prod(t,v(expf(-absorb.x*h.distance),expf(-absorb.y*h.distance),expf(-absorb.z*h.distance)));
  float f=powf((h.ior-1)/(h.ior+1),2);t=mul(t,(1-f)*h.transmission);
  if(h.flags&8)t=prod(t,v(expf(-h.absorption.x*.0625f),expf(-h.absorption.y*.0625f),expf(-h.absorption.z*.0625f)));else if(entering){if(depth>=4)return v(0,0,0);absorption[depth++]=h.absorption;}else if(depth>0)depth--;
  o=add(h.p,mul(d,.02f));
 }return v(0,0,0);
}
static __forceinline__ __device__ float3 incoming(float3 o,float3 d,unsigned& seed,int bounces=3,bool indirectOnly=false,int firstLobe=0){
 float3 radiance=v(0,0,0),throughput=v(1,1,1),absorptions[4];float iors[4];int mediumDepth=0;
 for(int bounce=0;bounce<bounces;bounce++){
  RtPayload h=trace(o,d);if(!h.hit){radiance=add(radiance,prod(throughput,mul(params.sky,.4f+.6f*fmaxf(0,d.y))));break;}
  bool entering=dot3(h.geometryNormal,d)<0;
  float3 absorb=mediumDepth>0?absorptions[mediumDepth-1]:(!entering&&h.transmission>0&&!(h.flags&8)?h.absorption:v(0,0,0));throughput=prod(throughput,v(expf(-absorb.x*h.distance),expf(-absorb.y*h.distance),expf(-absorb.z*h.distance)));
  if(!indirectOnly||bounce>0)radiance=add(radiance,prod(throughput,h.emission));
  float3 n=dot3(h.n,d)>0?mul(h.n,-1):h.n;
  if(h.transmission>0){float from=mediumDepth>0?iors[mediumDepth-1]:entering?1:h.ior,to=entering?h.ior:mediumDepth>1?iors[mediumDepth-2]:1,eta=from/to,c=fmaxf(0,-dot3(n,d)),k=1-eta*eta*(1-c*c),f0=(from-to)/(from+to),f=f0*f0+(1-f0*f0)*powf(1-c,5);
   bool forced=bounce==0&&firstLobe!=0;
   if(forced&&firstLobe==2&&k<0)break;
   if(k<0||forced&&firstLobe==1||!forced&&random(seed)<f){if(forced){if(k<0)d=reflect3(d,n);else{float3 weight;d=ggx(mul(d,-1),n,h.roughness,seed,v(f0*f0,f0*f0,f0*f0),weight);throughput=prod(throughput,weight);if(dot3(d,n)<=0)break;}}else d=reflect3(d,n);}

   else{if(forced)throughput=mul(throughput,1-f);if(h.flags&8){throughput=prod(throughput,v(expf(-h.absorption.x*.0625f),expf(-h.absorption.y*.0625f),expf(-h.absorption.z*.0625f)));}
    else{d=norm(add(mul(d,eta),mul(n,eta*c-sqrtf(k))));throughput=mul(throughput,eta*eta);if(entering){if(mediumDepth>=4)break;iors[mediumDepth]=h.ior;absorptions[mediumDepth++]=h.absorption;}else if(mediumDepth>0)mediumDepth--;}
    throughput=mul(throughput,h.transmission);
   }o=add(h.p,mul(d,.02f));continue;
  }
  if(h.metal>=230){float3 weight;d=ggx(mul(d,-1),n,h.roughness,seed,conductor(h.metal,h.color),weight);throughput=prod(throughput,weight);if(dot3(d,n)<=0)break;}
  else{float nl=fmaxf(0,dot3(n,params.sun));float3 vis=visibility(add(h.p,mul(n,.025f)),params.sun);radiance=add(radiance,prod(throughput,prod(mul(h.color,nl*(1-h.f0)),prod(params.sunColor,vis))));float specularChance=fminf(.9f,fmaxf(.05f,h.f0));
   if(random(seed)<specularChance){float3 weight;d=ggx(mul(d,-1),n,h.roughness,seed,v(h.f0,h.f0,h.f0),weight);throughput=prod(throughput,mul(weight,1/specularChance));if(dot3(d,n)<=0)break;}
   else{throughput=prod(throughput,mul(h.color,(1-h.f0)/(1-specularChance)));d=cosine(n,seed);}}
  if(bounce>=1){float survive=fminf(.95f,fmaxf(.1f,fmaxf(throughput.x,fmaxf(throughput.y,throughput.z))));if(random(seed)>survive)break;throughput=mul(throughput,1/survive);}o=add(h.p,mul(n,.025f));
 }return radiance;
}
extern "C" __global__ void __miss__radiance(){payload()->hit=0;}
extern "C" __global__ void __anyhit__surface(){
 const auto* data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());int i=optixGetPrimitiveIndex()*3;float2 b=optixGetTriangleBarycentrics();const auto& a=data->vertices[i];const auto& c=data->vertices[i+1];const auto& d=data->vertices[i+2];
 if(a.flags&1){float2 uv=make_float2(a.uv.x*(1-b.x-b.y)+c.uv.x*b.x+d.uv.x*b.y,a.uv.y*(1-b.x-b.y)+c.uv.y*b.x+d.uv.y*b.y);if(data->textureSlot<0&&(sample(params.ids,params.idsWidth,params.idsHeight,uv)>>24)>0)return;if((hitTexture(data,uv)>>24)<26)optixIgnoreIntersection();}
}
extern "C" __global__ void __closesthit__surface(){
 auto* p=payload();p->hit=1;p->distance=optixGetRayTmax();p->p=add(optixGetWorldRayOrigin(),mul(optixGetWorldRayDirection(),p->distance));
 const auto* data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());int i=optixGetPrimitiveIndex()*3;float2 b=optixGetTriangleBarycentrics();auto a=data->vertices[i],c=data->vertices[i+1],d=data->vertices[i+2];float w=1-b.x-b.y;
 p->n=norm(optixTransformNormalFromObjectToWorldSpace(add(add(mul(a.n,w),mul(c.n,b.x)),mul(d.n,b.y))));float2 uv=make_float2(a.uv.x*w+c.uv.x*b.x+d.uv.x*b.y,a.uv.y*w+c.uv.y*b.x+d.uv.y*b.y);
 unsigned tex=hitTexture(data,uv),id=data->textureSlot<0?sample(params.ids,params.idsWidth,params.idsHeight,uv):0,profile=data->textureSlot<0?params.lut[id&65535]:0xff200a33;
 if(dot3(p->n,p->n)<.1f)p->n=norm(optixTransformNormalFromObjectToWorldSpace(cross3(add(c.p,mul(a.p,-1)),add(d.p,mul(a.p,-1)))));
 p->geometryNormal=p->n;
 unsigned packedNormal=data->textureSlot<0?sample(params.normalMap,params.idsWidth,params.idsHeight,uv):0xff008080;
 float nx=(packedNormal&255)/127.5f-1,ny=((packedNormal>>8)&255)/127.5f-1;
 float3 e1=add(c.p,mul(a.p,-1)),e2=add(d.p,mul(a.p,-1));float ux=c.uv.x-a.uv.x,uy=c.uv.y-a.uv.y,vx=d.uv.x-a.uv.x,vy=d.uv.y-a.uv.y,det=ux*vy-uy*vx;
 if(fabsf(det)>1.e-10f){float3 tangent=optixTransformVectorFromObjectToWorldSpace(mul(add(mul(e1,vy),mul(e2,-uy)),1/det));tangent=norm(add(tangent,mul(p->n,-dot3(tangent,p->n))));float3 bitangent=mul(cross3(p->n,tangent),det<0?-1:1);p->n=norm(add(add(mul(tangent,nx),mul(bitangent,ny)),mul(p->n,sqrtf(fmaxf(0,1-nx*nx-ny*ny)))));}
 p->color=prod(rgb(tex),rgb(a.tint));p->roughness=1-(profile&255)/255.f;p->f0=((profile>>8)&255)/255.f;p->metal=(profile>>8)&255;p->flags=a.flags;
 unsigned type=id>>24;if(type==2)p->n=waterNormal(p->n,p->p);p->transmission=type?1:0;p->ior=type==2?1.333f:1.5f;p->absorption=type==2?v(.16f,.06f,.035f):v(-logf(fmaxf(.05f,p->color.x))*.7f,-logf(fmaxf(.05f,p->color.y))*.7f,-logf(fmaxf(.05f,p->color.z))*.7f);
 p->emission=mul(p->color,fmaxf((a.flags>>16)/15.f,((id>>16)&255)/254.f)*2.4f);
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
 unsigned i=optixGetLaunchIndex().x,seed=i*9781u+params.frame*6271u+1;
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
 if(params.mode==4){float4 p=params.position[i];params.previousPosition[i]=make_float4(p.x+params.camera.x,p.y+params.camera.y,p.z+params.camera.z,p.w);params.previousNormal[i]=params.normal[i];return;}float4 pp=params.position[i],nn=params.normal[i],aa=params.albedo[i],mm=params.material[i];
 if(params.mode==0){params.sunVisibility[i]=make_float4(1,1,1,0);params.flow[i*2]=params.flow[i*2+1]=0;params.flowTrust[i]=0;params.diffuse[i]=params.specular[i]=params.transmission[i]=make_float4(0,0,0,0);}if(pp.w<.5f)return;float3 p=add(v(pp.x,pp.y,pp.z),params.camera),n=norm(v(nn.x,nn.y,nn.z)),view=norm(v(-pp.x,-pp.y,-pp.z));float3 color=v(aa.x,aa.y,aa.z);
 float3 sunT=v(1,1,1);bool dielectric=false;
 if(params.mode==0){float radial=sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z);auto primary=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),radial+.1f);bool coverage=pp.w<1.5f&&primary.hit&&(primary.transmission>0||fabsf(primary.distance-radial)<.12f+.003f*radial);
  if(primary.hit&&primary.transmission>0&&(params.options&4)){dielectric=true;p=primary.p;n=primary.n;color=primary.color;float3 relative=add(p,mul(params.camera,-1));pp=make_float4(relative.x,relative.y,relative.z,1);nn=make_float4(n.x,n.y,n.z,2);params.material[i]=make_float4(primary.roughness,primary.f0,0,1);params.position[i]=pp;params.normal[i]=nn;params.albedo[i]=make_float4(color.x,color.y,color.z,1);coverage=true;}
  if(coverage)sunT=visibility(add(p,mul(n,.025f)),params.sun);params.sunVisibility[i]=make_float4(sunT.x,sunT.y,sunT.z,coverage?1:0);if(!coverage&&params.debug!=13&&params.debug!=7&&params.debug!=8)return;
 }
 float3 oldRelative=add(p,mul(params.previousCamera,-1));auto mat=params.previousClip;
 float ox=mat[0]*oldRelative.x+mat[4]*oldRelative.y+mat[8]*oldRelative.z+mat[12],oy=mat[1]*oldRelative.x+mat[5]*oldRelative.y+mat[9]*oldRelative.z+mat[13],ow=mat[3]*oldRelative.x+mat[7]*oldRelative.y+mat[11]*oldRelative.z+mat[15];
 if(params.mode==0&&ow>.00001f){float px=(ox/ow*.5f+.5f)*params.width-.5f,py=(oy/ow*.5f+.5f)*params.height-.5f;int x=(int)roundf(px),y=(int)roundf(py);
  if(x>=0&&x<params.width&&y>=0&&y<params.height){int j=y*params.width+x;float4 op=params.previousPosition[j],on=params.previousNormal[j];float3 delta=add(v(op.x,op.y,op.z),mul(p,-1));float tolerance=.1f+.002f*sqrtf(dot3(v(pp.x,pp.y,pp.z),v(pp.x,pp.y,pp.z)));
   if(op.w>.5f&&fabsf(dot3(delta,n))<tolerance&&dot3(n,v(on.x,on.y,on.z))>.85f){params.flow[i*2]=(int)(i%params.width)-px;params.flow[i*2+1]=(int)(i/params.width)-py;params.flowTrust[i]=1;}
  }
 }

 if(params.debug>=4){if(params.mode!=0)return;float3 debug=v(0,0,0);unsigned m=(unsigned)roundf(mm.y*255);auto diagnostic=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z)+.1f);if(diagnostic.hit){color=diagnostic.color;n=diagnostic.n;mm.x=diagnostic.roughness;m=diagnostic.metal;}
  if(params.debug==4)debug=color;else if(params.debug==5)debug=v(mm.x,mm.x,mm.x);else if(params.debug==6)debug=m>=230?v(1,.65f,.1f):v(0,0,0);else if(params.debug==7||params.debug==8){auto h=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z)+.1f);float value=params.debug==7?(h.hit?h.transmission:0):(h.hit?h.ior/2.5f:0);debug=v(value,value,value);}else if(params.debug==9)debug=add(mul(n,.5f),v(.5f,.5f,.5f));else if(params.debug==13)debug=params.sunVisibility[i].w>.5f?v(.1f,1,.1f):v(1,.05f,.05f);else if(params.debug==14){unsigned id=(unsigned)mm.z;debug=v((id&255)/255.f,((id>>8)&255)/255.f,0);}else if(params.debug==15)debug=sunT;else if(params.debug==16)debug=v(expf(-.16f*8),expf(-.06f*8),expf(-.035f*8));
  else{for(int cascade=0;cascade<3;cascade++){float spacing=4.f*(1<<cascade);int gx=(int)floorf(p.x/spacing),gy=(int)floorf(p.y/spacing),gz=(int)floorf(p.z/spacing),slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));auto a=params.probes+slot*8;if(a[0].w>0&&fabsf(a[0].x-gx*spacing)+fabsf(a[0].y-gy*spacing)+fabsf(a[0].z-gz*spacing)<.1f){debug=params.debug==10?v(0,1,0):params.debug==11?v(fminf(1,(params.frame-a[5].z)/120),0,0):cascade==0?v(1,0,0):cascade==1?v(0,1,0):v(0,0,1);break;}}}
  params.diffuse[i]=make_float4(debug.x,debug.y,debug.z,1);if(dielectric)params.transmission[i]=make_float4(debug.x,debug.y,debug.z,sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z));return;
 }
 unsigned metal=(unsigned)roundf(mm.y*255);float3 f=metal>=230?conductor(metal,color):v(mm.y,mm.y,mm.y);
 if(params.mode==0&&!dielectric&&(params.options&1)){float3 gi=prod(color,mul((params.options&16)?probeLight(p,n):incoming(add(p,mul(n,.025f)),cosine(n,seed),seed,3,true),metal>=230?0:1-mm.y));params.diffuse[i]=make_float4(gi.x,gi.y,gi.z,1);}
 if(params.mode==5&&params.sunVisibility[i].w>.5f&&(params.options&2)){float3 light;
  if(nn.w>1.5f)light=incoming(params.camera,norm(v(pp.x,pp.y,pp.z)),seed,8,false,1);
  else{float3 weight;float3 dir=ggx(view,n,mm.x,seed,f,weight);light=dot3(dir,n)>0?prod(incoming(add(p,mul(n,.025f)),dir,seed),weight):v(0,0,0);}
  params.specular[i]=make_float4(light.x,light.y,light.z,1);}
 if(params.mode==6&&(params.sunVisibility[i].w>.5f||pp.w>1.5f)&&(params.options&4)){float3 d=norm(v(pp.x,pp.y,pp.z));RtPayload h=trace(params.camera,d,sqrtf(dot3(v(pp.x,pp.y,pp.z),v(pp.x,pp.y,pp.z)))+.03f);
  if(h.hit&&h.transmission>0){float3 light=incoming(params.camera,d,seed,8,false,2);params.transmission[i]=make_float4(light.x,light.y,light.z,h.distance);}
 }
}
