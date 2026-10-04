 #include "environment.h"
// Material lookup, sampling and path transport deliberately have separate responsibilities.
static __forceinline__ __device__ rt::Vec rv(float3 a){return rt::V(a.x,a.y,a.z);}
static __forceinline__ __device__ float3 cv(rt::Vec a){return v(a.x,a.y,a.z);}
static __forceinline__ __device__ rt::Material decodeMaterial(unsigned id,unsigned packed,float3 color,unsigned flags){
 rt::Material m;m.multipleScattering=(params.options&256)!=0;unsigned index=id&65535;
 m.baseColor=rv(color);float perceptualRoughness=1-(packed&255)/255.f;m.microfacetAlpha=perceptualRoughness*perceptualRoughness;m.alphaV=m.microfacetAlpha;
 unsigned green=(packed>>8)&255,blue=(packed>>16)&255;m.f0=green<230?green/255.f:.04f;m.porosity=blue<=64?blue/64.f:0;m.sss=blue>=65?(blue-65)/190.f:0;
 unsigned a=params.lut[index+65536],b=params.lut[index+2*65536],c=params.lut[index+3*65536],d=params.lut[index+4*65536];
 m.type=a&255;m.ior=fmaxf(1,((a>>8)&255)*3/255.f);m.coatWeight=((a>>16)&255)/255.f;m.coatAlpha=((a>>24)&255)/255.f;m.coatIOR=fmaxf(1,(d&255)*3/255.f);m.alphaV=fmaxf(.0005f,((d>>8)&255)/255.f);
 m.sigmaA=rt::V((b&255)*8/255.f,((b>>8)&255)*8/255.f,((b>>16)&255)*8/255.f);m.phaseG=((b>>24)&255)*1.8f/255.f-.9f;
 m.sigmaS=rt::V((c&255)/255.f,((c>>8)&255)/255.f,((c>>16)&255)/255.f);m.transmission=((c>>24)&255)/255.f;
 if(green>=230)rt::setConductor(m,green<=237?green:255);
 if(flags&16){m.type=rt::ROUGH_DIFFUSE;m.coatWeight=0;m.f0=.04f;m.ior=1.5f;m.transmission=0;m.alphaV=m.microfacetAlpha;}
 if(m.sss>0&&!rt::metal(m)&&!rt::dielectric(m)){m.type=rt::DIFFUSE_TRANSMISSION;m.coatWeight=0;m.transmission=m.sss*.5f;}

 m.materialId=index;m.mediumId=index+1;return m;
}
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
// RGB visibility with medium identity. This is straight-line NEE, not focused caustics.
static __noinline__ __device__ float3 lightVisibility(float3 o,float3 d,float maximum,rt::MediumStack stack={}){countOperation(1);float3 T=v(1,1,1);float travel=0;for(int k=0;k<24;k++){auto h=trace(o,d,fmaxf(0,maximum-travel));if(!h.hit){if(stack.count)T=prod(T,cv(rt::expNeg(stack.entries[stack.count-1].sigmaA+stack.entries[stack.count-1].sigmaS,fmaxf(0,maximum-travel))));return T;}if(!rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::DIFFUSE_TRANSMISSION)return v(0,0,0);bool entering=dot3(h.geometryNormal,d)<0;rt::Medium medium={h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.bsdf.ior,h.bsdf.mediumId};if(!entering&&stack.count==0&&rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC)stack.enter(medium);if(stack.count)T=prod(T,cv(rt::expNeg(stack.entries[stack.count-1].sigmaA+stack.entries[stack.count-1].sigmaS,h.distance)));else if(!entering&&h.bsdf.type!=rt::THIN_DIELECTRIC)T=prod(T,cv(rt::expNeg(medium.sigmaA+medium.sigmaS,h.distance)));
 if(h.bsdf.type==rt::DIFFUSE_TRANSMISSION){T=prod(T,mul(h.color,h.bsdf.transmission));}
 else{float from=stack.ior(),to=entering?h.ior:stack.outside(medium.id),F=rt::fresnelDielectric(fabsf(dot3(h.n,d)),h.bsdf.type==rt::THIN_DIELECTRIC?h.ior/from:to/from);if(h.bsdf.type==rt::THIN_DIELECTRIC)F=2*F/(1+F);T=mul(T,(1-F)*h.bsdf.transmission);if(h.bsdf.type==rt::THIN_DIELECTRIC)T=prod(T,cv(rt::expNeg(medium.sigmaA,h.bsdf.thickness/fmaxf(.01f,fabsf(dot3(h.n,d))))));else if(entering){if(!stack.enter(medium))return v(0,0,0);}else if(!stack.exit(medium.id)&&stack.count&&stack.entries[stack.count-1].id==0xfffffffeu)stack.count--;}
 travel+=h.distance;if(travel>=maximum)return T;o=spawn(h,d);}return v(0,0,0);}
struct TransportDebug {float bsdfPdf=0,lightPdf=0,mis=0;unsigned bounce=0,lobe=0,kind=0;float3 secondary=v(0,0,0),sun=v(0,0,0),emissive=v(0,0,0),throughput=v(1,1,1);};
static __noinline__ __device__ float3 estimateDirect(const RtPayload& h,float3 wo,unsigned& seed,TransportDebug& info,float eta=1.5f,int forced=0,rt::MediumStack media={}){auto l=sampleOneLight(h.p,seed);if(l.pdf<=0)return v(0,0,0);if(l.kind==0&&(params.options&32)&&dot3(causticIrradiance(h.p,h.geometryNormal),v(1,1,1))>0)return v(0,0,0);rt::Vec normal=rv(dot3(h.n,wo)>0?h.n:mul(h.n,-1));rt::Frame frame(normal,rv(h.tangent),rv(h.bitangent));auto e=rt::evalBsdf(h.bsdf,frame,rv(wo),rv(l.wi),eta,forced);float c=fabsf(rt::dot(normal,rv(l.wi)));if(!rt::validShadingHemisphere(rv(h.geometryNormal),normal,rv(wo),rv(l.wi)))return v(0,0,0);float mis=l.kind==3?1:rt::powerHeuristic(l.pdf,e.pdf);if(rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC&&rt::dot(normal,rv(l.wi))<0){rt::Medium m={h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.ior,h.bsdf.mediumId};if(dot3(h.geometryNormal,wo)>0)media.enter(m);else media.exit(m.id);}auto vis=lightVisibility(spawn(h,l.wi),l.wi,l.distance,media);float3 value=prod(cv(e.f*(c*mis/l.pdf)),prod(l.radiance,vis));if(l.kind==0)value=mul(value,cloudVisibility(h.p));info.lightPdf=l.pdf;info.bsdfPdf=e.pdf;info.mis=mis;info.kind=l.kind;info.secondary=add(info.secondary,value);if(l.kind==0)info.sun=add(info.sun,value);if(l.kind==2)info.emissive=add(info.emissive,value);return value;}
// Primary opaque glossy direct uses the same light distribution/visibility as secondary hits.
// Sun remains raster-owned. Sampling and rejecting sun preserves the original light PDF.
static __noinline__ __device__ float3 primaryGlossyDirect(const RtPayload& h,float3 wo,unsigned& seed){
 auto l=sampleOneLight(h.p,seed);if(l.pdf<=0||l.kind==0)return v(0,0,0);
 rt::Frame frame(rv(dot3(h.n,wo)>0?h.n:mul(h.n,-1)),rv(h.tangent),rv(h.bitangent));
 auto e=rt::evalGlossy(h.bsdf,frame,rv(wo),rv(l.wi));
 float cosine=fmaxf(0,rt::dot(frame.n,rv(l.wi)));
 if(cosine<=0||dot3(h.geometryNormal,l.wi)*dot3(h.geometryNormal,wo)<=0)return v(0,0,0);
 float mis=l.kind==3?1:rt::powerHeuristic(l.pdf,e.pdf);
 auto visibility=lightVisibility(spawn(h,l.wi),l.wi,l.distance);
 return prod(cv(e.f*(cosine*mis/l.pdf)),prod(l.radiance,visibility));
}
struct PathState {float3 radiance=v(0,0,0),throughput=v(1,1,1),origin,direction;rt::MediumStack media;float etaScale=1,previousBsdfPdf=0;bool previousWasDelta=true;int depth=0;};
static __noinline__ __device__ float3 incoming(float3 o,float3 d,unsigned& seed,int bounces=3,bool indirectOnly=false,int firstLobe=0,TransportDebug* output=nullptr,bool excludeFirstSun=false,float initialBsdfPdf=0){
 countOperation(8);PathState path;path.origin=o;path.direction=d;path.previousBsdfPdf=initialBsdfPdf;path.previousWasDelta=initialBsdfPdf<=0;if(params.underwater>.5f&&dot3(add(o,mul(params.camera,-1)),add(o,mul(params.camera,-1)))<.01f)path.media.enter(params.cameraWater);TransportDebug info;float3 previous=o;
 for(int bounce=0;bounce<bounces;bounce++){
  countOperation(2);RtPayload h=trace(path.origin,path.direction);float distance=h.hit?h.distance:128;
  bool entering=h.hit&&dot3(h.geometryNormal,path.direction)<0;
  if(h.hit&&!entering&&rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC&&path.media.count==0)path.media.enter({h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.ior,h.bsdf.mediumId});
  if(path.media.count){auto medium=path.media.entries[path.media.count-1];rt::Vec extinction=medium.sigmaA+medium.sigmaS;float3 segmentT=cv(rt::expNeg(extinction,distance));
   if(params.options&(1u<<25)){
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
   }else{
   // Truncated free-flight proposal: RGB attenuation / proposal PDF keeps this unbiased.
   if(rt::maxComponent(medium.sigmaS)>0){countOperation(3);auto mediumSample=rt::sampleMediumDistance(extinction,distance,seed);float t=mediumSample.distance;float3 p=add(path.origin,mul(path.direction,t));auto light=sampleOneLight(p,seed);if(light.pdf>0){float3 vis=lightVisibility(p,light.wi,light.distance,path.media);float phase=rt::hg(dot3(path.direction,light.wi),medium.g);float3 scatter=prod(cv(rt::expNeg(extinction,t)*medium.sigmaS*(phase/(light.pdf*mediumSample.pdf))),prod(light.radiance,vis));path.radiance=add(path.radiance,prod(path.throughput,scatter));}}
   path.throughput=prod(path.throughput,segmentT);
   }
  }
  if(!h.hit){float envPdf=lightProbability(1)*((params.options&128)?rt::environmentDensity(params.environmentCdf,rv(path.direction)):1/(4*rt::Pi)),w=path.previousWasDelta?1:rt::powerHeuristic(path.previousBsdfPdf,envPdf);path.radiance=add(path.radiance,prod(path.throughput,mul(environment(path.direction),w)));const float omega=6.793e-5f;if(!(bounce==0&&(indirectOnly||excludeFirstSun))&&dot3(path.direction,params.sun)>1-omega/(2*rt::Pi)){float pdf=lightProbability(0)/omega;path.radiance=add(path.radiance,prod(path.throughput,mul(params.sunColor,(path.previousWasDelta?1:rt::powerHeuristic(path.previousBsdfPdf,pdf))/omega)));}break;}
  if((!indirectOnly||bounce>0)&&!(bounce==0&&firstLobe==1)){float w=path.previousWasDelta?1:rt::powerHeuristic(path.previousBsdfPdf,emitterPdf(previous,h));path.radiance=add(path.radiance,prod(path.throughput,mul(h.emission,w)));}
  float3 wo=mul(path.direction,-1),normal=dot3(h.n,wo)>0?h.n:mul(h.n,-1);rt::Frame frame(rv(normal),rv(h.tangent),rv(h.bitangent));
  float from=path.media.ior(),to=entering?h.ior:path.media.outside(h.bsdf.mediumId),eta=to/from;int forced=bounce==0?firstLobe:0;
  if(h.bsdf.type!=rt::THIN_DIELECTRIC){path.radiance=add(path.radiance,prod(path.throughput,estimateDirect(h,wo,seed,info,eta,forced,path.media)));if((params.options&32)&&!rt::metal(h.bsdf)&&!rt::dielectric(h.bsdf))path.radiance=add(path.radiance,prod(path.throughput,prod(cv(h.bsdf.baseColor),mul(causticIrradiance(h.p,h.geometryNormal),1/rt::Pi))));}
  countOperation(5);if(h.bsdf.type==rt::THIN_DIELECTRIC)eta=h.bsdf.ior/path.media.ior();auto bs=rt::sampleBsdf(h.bsdf,frame,rv(wo),seed,eta,forced);
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
