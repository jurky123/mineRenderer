#pragma once
#include "transport.cuh"
static __noinline__ __device__ float3 estimateDirect(const RtPayload& h,float3 wo,unsigned& seed,TransportDebug& info,float eta=1.5f,int forced=0,rt::MediumStack media={}){auto l=sampleOneLight(h.p,seed);if(l.pdf<=0)return v(0,0,0);if(l.kind==0&&(params.options&32)&&dot3(causticIrradiance(h.p,h.geometryNormal),v(1,1,1))>0)return v(0,0,0);rt::Vec normal=rv(dot3(h.n,wo)>0?h.n:mul(h.n,-1));rt::Frame frame(normal,rv(h.tangent),rv(h.bitangent));auto e=evaluateBsdf(h.bsdf,frame,rv(wo),rv(l.wi),eta,forced);float c=fabsf(rt::dot(normal,rv(l.wi)));if(!rt::validShadingHemisphere(rv(h.geometryNormal),normal,rv(wo),rv(l.wi)))return v(0,0,0);float mis=l.kind==3?1:rt::powerHeuristic(l.pdf,e.pdf);if(rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC&&rt::dot(normal,rv(l.wi))<0){rt::Medium m={h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.ior,h.bsdf.mediumId};if(dot3(h.geometryNormal,wo)>0)media.enter(m);else media.exit(m.id);}auto vis=lightVisibility(spawn(h,l.wi),l.wi,l.distance,media);float3 value=prod(cv(e.f*(c*mis/l.pdf)),prod(l.radiance,vis));if(l.kind==0)value=mul(value,cloudVisibility(h.p));info.lightPdf=l.pdf;info.bsdfPdf=e.pdf;info.mis=mis;info.kind=l.kind;info.secondary=add(info.secondary,value);if(l.kind==0)info.sun=add(info.sun,value);if(l.kind==2)info.emissive=add(info.emissive,value);return value;}
struct PathState {float3 radiance=v(0,0,0),throughput=v(1,1,1),origin,direction;rt::MediumStack media;float etaScale=1,previousBsdfPdf=0;bool previousWasDelta=true;int depth=0;};
static __noinline__ __device__ float3 incomingImplementation(float3 o,float3 d,unsigned& seed,int bounces=3,bool indirectOnly=false,int firstLobe=0,TransportDebug* output=nullptr,bool excludeFirstSun=false,float initialBsdfPdf=0){
 countOperation(8);PathState path;path.origin=o;path.direction=d;path.previousBsdfPdf=initialBsdfPdf;path.previousWasDelta=initialBsdfPdf<=0;if(params.underwater>.5f&&dot3(add(o,mul(params.camera,-1)),add(o,mul(params.camera,-1)))<.01f)path.media.enter(params.cameraWater);TransportDebug info;float3 previous=o;
 // A transport step contains tracing, BSDFs, media and NEE; do not clone it per bounce.
 #pragma unroll 1
 for(int bounce=0;bounce<bounces;bounce++){
  countOperation(2);RtPayload h=trace(path.origin,path.direction);float distance=h.hit?h.distance:128;
  bool entering=h.hit&&dot3(h.geometryNormal,path.direction)<0;
  if(h.hit&&!entering&&rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC&&path.media.count==0)path.media.enter({h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.ior,h.bsdf.mediumId});
  if(path.media.count){auto medium=path.media.entries[path.media.count-1];rt::Vec extinction=medium.sigmaA+medium.sigmaS;float3 segmentT=cv(rt::expNeg(extinction,distance));
   #ifdef RT_FULL_REFERENCE
   {
    // RGB mixture free flight: reference follows actual multiple medium events.
    int channel=(int)(random(seed)*3);float rate=channel==0?extinction.x:channel==1?extinction.y:extinction.z;
    float eventDistance=rate>0?-logf(fmaxf(1e-8f,1-random(seed)))/rate:1e30f;
    if(eventDistance<distance){
     countOperation(3);auto trans=rt::expNeg(extinction,eventDistance);float eventPdf=rt::dot(trans*extinction,rt::V(1.f/3,1.f/3,1.f/3));
     path.throughput=prod(path.throughput,cv(trans*medium.sigmaS/fmaxf(1e-12f,eventPdf)));auto event=add(path.origin,mul(path.direction,eventDistance));auto light=sampleOneLight(event,seed);
     if(light.pdf>0){float phase=rt::hg(dot3(path.direction,light.wi),medium.g),mis=light.kind==3?1:rt::powerHeuristic(light.pdf,phase);auto vis=lightVisibility(event,light.wi,light.distance,path.media);path.radiance=add(path.radiance,prod(path.throughput,prod(mul(light.radiance,phase*mis/light.pdf),vis)));}
     previous=event;auto next=rt::sampleHg(rv(path.direction),medium.g,seed);path.previousBsdfPdf=rt::hg(rt::dot(rv(path.direction),next),medium.g);path.previousWasDelta=false;path.direction=cv(next);path.origin=event;
     if(bounce>=2){float survival=fminf(.95f,fmaxf(.05f,rt::maxComponent(rv(path.throughput))));if(random(seed)>survival)break;path.throughput=mul(path.throughput,1/survival);}continue;
    }
    float survivalPdf=(segmentT.x+segmentT.y+segmentT.z)/3;path.throughput=prod(path.throughput,mul(segmentT,1/fmaxf(1e-12f,survivalPdf)));
   }
   #else
   {
   // Truncated free-flight proposal: RGB attenuation / proposal PDF keeps this unbiased.
   if(rt::maxComponent(medium.sigmaS)>0){countOperation(3);auto mediumSample=rt::sampleMediumDistance(extinction,distance,seed);float t=mediumSample.distance;float3 p=add(path.origin,mul(path.direction,t));auto light=sampleOneLight(p,seed);if(light.pdf>0){float3 vis=lightVisibility(p,light.wi,light.distance,path.media);float phase=rt::hg(dot3(path.direction,light.wi),medium.g);float3 scatter=prod(cv(rt::expNeg(extinction,t)*medium.sigmaS*(phase/(light.pdf*mediumSample.pdf))),prod(light.radiance,vis));path.radiance=add(path.radiance,prod(path.throughput,scatter));}}
   path.throughput=prod(path.throughput,segmentT);
   }
   #endif
  }
  if(!h.hit){float envPdf=lightProbability(1)*((params.options&128)?rt::environmentDensity(params.environmentCdf,rv(path.direction)):1/(4*rt::Pi)),w=path.previousWasDelta?1:rt::powerHeuristic(path.previousBsdfPdf,envPdf);path.radiance=add(path.radiance,prod(path.throughput,mul(environment(path.direction),w)));const float omega=6.793e-5f;if(!(bounce==0&&(indirectOnly||excludeFirstSun))&&dot3(path.direction,params.sun)>1-omega/(2*rt::Pi)){float pdf=lightProbability(0)/omega;path.radiance=add(path.radiance,prod(path.throughput,mul(params.sunColor,(path.previousWasDelta?1:rt::powerHeuristic(path.previousBsdfPdf,pdf))/omega)));}break;}
  if((!indirectOnly||bounce>0)&&!(bounce==0&&firstLobe==1)){float w=path.previousWasDelta?1:rt::powerHeuristic(path.previousBsdfPdf,emitterPdf(previous,h));path.radiance=add(path.radiance,prod(path.throughput,mul(h.emission,w)));}
  float3 wo=mul(path.direction,-1),normal=dot3(h.n,wo)>0?h.n:mul(h.n,-1);rt::Frame frame(rv(normal),rv(h.tangent),rv(h.bitangent));
  float from=path.media.ior(),to=entering?h.ior:path.media.outside(h.bsdf.mediumId),eta=to/from;int forced=bounce==0?firstLobe:0;
  if(h.bsdf.type!=rt::THIN_DIELECTRIC){path.radiance=add(path.radiance,prod(path.throughput,estimateDirect(h,wo,seed,info,eta,forced,path.media)));if((params.options&32)&&!rt::metal(h.bsdf)&&!rt::dielectric(h.bsdf))path.radiance=add(path.radiance,prod(path.throughput,prod(cv(h.bsdf.baseColor),mul(causticIrradiance(h.p,h.geometryNormal),1/rt::Pi))));}
  countOperation(5);if(h.bsdf.type==rt::THIN_DIELECTRIC)eta=h.bsdf.ior/path.media.ior();auto bs=sampleMaterial(h.bsdf,frame,rv(wo),seed,eta,forced);
  if(bs.pdf<=0||rt::maxComponent(bs.weight)<=0||!rt::validShadingHemisphere(rv(h.geometryNormal),rv(normal),rv(wo),bs.wi))break;
  path.throughput=prod(path.throughput,cv(bs.weight));path.previousBsdfPdf=bs.pdf;path.previousWasDelta=(bs.flags&rt::DELTA)!=0;
  if((bs.flags&rt::TRANSMISSION)&&rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC){rt::Medium medium={h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.ior,h.bsdf.mediumId};if(entering){if(!path.media.enter(medium))break;}else if(!path.media.exit(medium.id)){if(path.media.count&&path.media.entries[path.media.count-1].id==0xfffffffeu)path.media.count--;else break;}path.etaScale*=bs.eta*bs.eta;}
  info.bounce=bounce+1;info.lobe=bs.flags;info.throughput=path.throughput;info.bsdfPdf=bs.pdf;
  if(bounce>=2){float probability=fminf(.95f,fmaxf(.05f,fmaxf(path.throughput.x,fmaxf(path.throughput.y,path.throughput.z))*path.etaScale));if(random(seed)>probability)break;path.throughput=mul(path.throughput,1/probability);}
  previous=h.p;path.direction=cv(bs.wi);path.origin=spawn(h,path.direction);
 }
 if(output)*output=info;
 if(params.fireflyClamp>0){float peak=fmaxf(path.radiance.x,fmaxf(path.radiance.y,path.radiance.z));if(peak>params.fireflyClamp)path.radiance=mul(path.radiance,params.fireflyClamp/peak);}return path.radiance;
}
