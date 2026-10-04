#pragma once
// Explicit OptiX SBT callable indices; identical for realtime/reference/caustics.
enum RtCallable { TransportCallable, VisibilityCallable, BsdfEvalCallable, BsdfSampleCallable, GlossyEvalCallable, GlossySampleCallable, RtCallableCount };
struct TransportDebug {float bsdfPdf=0,lightPdf=0,mis=0;unsigned bounce=0,lobe=0,kind=0;float3 secondary=v(0,0,0),sun=v(0,0,0),emissive=v(0,0,0),throughput=v(1,1,1);};
struct VisibilityCall {float3 origin,direction;float maximum;rt::MediumStack media;float3 result;};
static __forceinline__ __device__ float3 lightVisibility(float3 origin,float3 direction,float maximum,rt::MediumStack media={}){
 VisibilityCall call{origin,direction,maximum,media,{}};optixContinuationCall<void,VisibilityCall*>(VisibilityCallable,&call);return call.result;
}
struct IncomingCall {float3 origin,direction;unsigned seed;int bounces;bool indirectOnly;int firstLobe;bool excludeFirstSun;float initialBsdfPdf;TransportDebug debug;float3 result;};
static __forceinline__ __device__ float3 incoming(float3 origin,float3 direction,unsigned& seed,int bounces=3,bool indirectOnly=false,int firstLobe=0,TransportDebug* output=nullptr,bool excludeFirstSun=false,float initialBsdfPdf=0){
 IncomingCall call{origin,direction,seed,bounces,indirectOnly,firstLobe,excludeFirstSun,initialBsdfPdf,{},{}};
 optixContinuationCall<void,IncomingCall*>(TransportCallable,&call);seed=call.seed;if(output)*output=call.debug;return call.result;
}
struct BsdfCall {const rt::Material* material;const rt::Frame* frame;rt::Vec wo,wi;unsigned seed;float eta;int forced;rt::BsdfEval evaluation;rt::BsdfSample sample;};
static __forceinline__ __device__ rt::BsdfEval evaluateBsdf(const rt::Material& material,const rt::Frame& frame,rt::Vec wo,rt::Vec wi,float eta=1.5f,int forced=0){
 BsdfCall call{&material,&frame,wo,wi,0,eta,forced,{},{}};optixDirectCall<void,BsdfCall*>(BsdfEvalCallable,&call);return call.evaluation;
}
static __forceinline__ __device__ rt::BsdfEval evaluateGlossy(const rt::Material& material,const rt::Frame& frame,rt::Vec wo,rt::Vec wi){
 BsdfCall call{&material,&frame,wo,wi,0,1.5f,0,{},{}};optixDirectCall<void,BsdfCall*>(GlossyEvalCallable,&call);return call.evaluation;
}
static __forceinline__ __device__ rt::BsdfSample sampleMaterial(const rt::Material& material,const rt::Frame& frame,rt::Vec wo,unsigned& seed,float eta=1.5f,int forced=0){
 BsdfCall call{&material,&frame,wo,{},seed,eta,forced,{},{}};optixDirectCall<void,BsdfCall*>(BsdfSampleCallable,&call);seed=call.seed;return call.sample;
}
static __forceinline__ __device__ rt::BsdfSample sampleReflection(const rt::Material& material,const rt::Frame& frame,rt::Vec wo,unsigned& seed){
 BsdfCall call{&material,&frame,wo,{},seed,1.5f,0,{},{}};optixDirectCall<void,BsdfCall*>(GlossySampleCallable,&call);seed=call.seed;return call.sample;
}
