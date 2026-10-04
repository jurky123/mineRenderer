#pragma once
#include "callable_api.cuh"
#include "material.cuh"
struct LightSample {float3 wi,radiance;float distance,pdf;int kind;};
static __forceinline__ __device__ float3 environment(float3 d){if((params.options&128)&&params.environmentMap){auto pixel=params.environmentMap[rt::environmentCell(rv(d))];return v(pixel.x,pixel.y,pixel.z);}return mul(params.sky,.4f+.6f*fmaxf(0,d.y));}
static __forceinline__ __device__ float lightProbability(int kind){float sun=fmaxf(0,dot3(params.sunColor,v(.2126f,.7152f,.0722f))),env=fmaxf(.001f,((params.options&128)&&params.environmentCdf?params.environmentCdf[rt::EnvironmentWidth*rt::EnvironmentHeight+rt::EnvironmentHeight-1]:dot3(params.sky,v(.2126f,.7152f,.0722f))*6.28f)),area=fmaxf(0,params.lightPower);float point=params.pointPosition.w>0?dot3(v(params.pointIntensity.x,params.pointIntensity.y,params.pointIntensity.z),v(.2126f,.7152f,.0722f))*4*rt::Pi:0;float total=sun+env+area+point;return (kind==0?sun:kind==1?env:kind==2?area:point)/total;}
static __forceinline__ __device__ float3 cone(float3 axis,float cosineMax,unsigned& seed){float c=1-random(seed)*(1-cosineMax),r=sqrtf(fmaxf(0,1-c*c)),a=2*rt::Pi*random(seed);rt::Frame f(rv(axis));return cv(f.world(rt::V(r*cosf(a),r*sinf(a),c)));}
static __forceinline__ __device__ LightSample sampleOneLight(float3 p,unsigned& seed){
 countOperation(4);float choice=random(seed),sun=lightProbability(0),env=lightProbability(1);LightSample l{};l.distance=512;
 if(choice<sun){const float omega=6.793e-5f;l.kind=0;l.wi=cone(params.sun,1-omega/(2*rt::Pi),seed);l.pdf=sun/omega;l.radiance=mul(params.sunColor,1/omega);return l;}
 if(choice<sun+env){l.kind=1;if(params.options&128){l.wi=cv(rt::sampleEnvironment(params.environmentCdf,seed));l.pdf=env*rt::environmentDensity(params.environmentCdf,rv(l.wi));}else{float z=1-2*random(seed),a=2*rt::Pi*random(seed),r=sqrtf(1-z*z);l.wi=v(r*cosf(a),z,r*sinf(a));l.pdf=env/(4*rt::Pi);}l.radiance=environment(l.wi);return l;}
 if(choice>=sun+env+lightProbability(2)){float3 delta=add(v(params.pointPosition.x,params.pointPosition.y,params.pointPosition.z),mul(p,-1));l.distance=sqrtf(dot3(delta,delta));if(l.distance<.025f)return l;l.wi=mul(delta,1/l.distance);l.pdf=lightProbability(3);l.radiance=mul(v(params.pointIntensity.x,params.pointIntensity.y,params.pointIntensity.z),1/(l.distance*l.distance));l.kind=3;return l;}
 if(params.lightCount<=0)return l;float value=random(seed)*params.lightPower;auto cluster=params.lightClusters[rt::lightClusterIndex(params.lightClusters,params.lightClusterCount,value)];int lo=cluster.begin,hi=cluster.end-1;while(lo<hi){int mid=(lo+hi)/2;if(params.lights[mid].cdf<value)lo=mid+1;else hi=mid;}auto light=params.lights[lo];float u=sqrtf(random(seed)),w=random(seed),wa=1-u,wb=u*(1-w),wc=u*w;float3 position=add(add(mul(light.a.p,wa),mul(light.b.p,wb)),mul(light.c.p,wc));float3 delta=add(position,mul(p,-1));l.distance=sqrtf(dot3(delta,delta));if(l.distance<.025f)return l;l.wi=mul(delta,1/l.distance);float3 normal=norm(cross3(add(light.b.p,mul(light.a.p,-1)),add(light.c.p,mul(light.a.p,-1))));float c=fabsf(dot3(normal,l.wi));if(c<1e-6f)return l;
 float power=light.cdf-(lo?params.lights[lo-1].cdf:0);l.pdf=lightProbability(2)*power/params.lightPower*l.distance*l.distance/(light.area*c);float2 uv=make_float2(light.a.uv.x*wa+light.b.uv.x*wb+light.c.uv.x*wc,light.a.uv.y*wa+light.b.uv.y*wb+light.c.uv.y*wc);unsigned tex=sample(params.atlas,params.atlasWidth,params.atlasHeight,uv),id=sample(params.ids,params.idsWidth,params.idsHeight,uv);l.radiance=mul(prod(rgb(tex),rgb(light.a.tint)),(((id>>24)&4)?((id>>16)&255)/254.f:(light.a.flags>>16)/15.f)*2.4f);l.kind=2;return l;
}
static __forceinline__ __device__ float emitterPdf(float3 previous,const RtPayload& hit){float d2=dot3(add(previous,mul(hit.p,-1)),add(previous,mul(hit.p,-1)));int i=rt::emitterIdentityIndex(params.lights,params.lightCount,hit.objectId*600001u+hit.primitiveId);if(i<0)return 0;auto light=params.lights[i];float probability=(light.cdf-(i?params.lights[i-1].cdf:0))/fmaxf(1e-6f,params.lightPower);float c=fabsf(dot3(hit.geometryNormal,norm(add(previous,mul(hit.p,-1)))));return lightProbability(2)*probability*d2/fmaxf(1e-7f,light.area*c);}
// Primary opaque glossy direct uses the same light distribution/visibility as secondary hits.
// Sun remains raster-owned. Sampling and rejecting sun preserves the original light PDF.
static __noinline__ __device__ float3 primaryGlossyDirect(const RtPayload& h,float3 wo,unsigned& seed){
 auto l=sampleOneLight(h.p,seed);if(l.pdf<=0||l.kind==0)return v(0,0,0);
 rt::Frame frame(rv(dot3(h.n,wo)>0?h.n:mul(h.n,-1)),rv(h.tangent),rv(h.bitangent));
 auto e=evaluateGlossy(h.bsdf,frame,rv(wo),rv(l.wi));
 float cosine=fmaxf(0,rt::dot(frame.n,rv(l.wi)));
 if(cosine<=0||dot3(h.geometryNormal,l.wi)*dot3(h.geometryNormal,wo)<=0)return v(0,0,0);
 float mis=l.kind==3?1:rt::powerHeuristic(l.pdf,e.pdf);
 auto visibility=lightVisibility(spawn(h,l.wi),l.wi,l.distance);
 return prod(cv(e.f*(cosine*mis/l.pdf)),prod(l.radiance,visibility));
}
