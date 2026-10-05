// Compare the actual Slang CPU target against the existing native Material 3 implementation.
// The generated translation unit supplies Slang ABI types and the exported compute entry.
#include "transport_parity.cpp"
#include "../../native/rt/bsdf.h"
#include "../../native/rt/surface.h"
float3 v(float x,float y,float z){return {x,y,z};}
struct {unsigned* lut;unsigned options;} params;
#define __forceinline__ inline
#define __device__
#include "../../native/rt/material.cuh"
#include <cstdio>
#include <cstdlib>
#include <vector>
rt::Material fixture(unsigned kind,float alpha) {
 rt::Material m;m.type=kind;m.microfacetAlpha=alpha;m.alphaV=alpha;m.baseColor=rt::V(.7,.5,.3);
 m.transmission=.8;m.sigmaA=rt::V(.16,.06,.035);m.sigmaS=rt::V(.01,.02,.03);
 if(kind==rt::CONDUCTOR||kind==rt::COATED_CONDUCTOR)rt::setConductor(m,231);
 if(kind==rt::COATED_DIFFUSE||kind==rt::COATED_CONDUCTOR){m.type=kind;m.coatWeight=.6;}
 if(kind==rt::WATER)m.ior=1.333;
 if(kind==10)rt::setConductor(m,234);
 if(kind==11)rt::setConductor(m,255);
 if(kind>=12)rt::setConductor(m,230+kind-12);
 return m;
}
int main() {
 constexpr unsigned cases=20*9*5*64;
 std::vector<Vector<float,4>> actual(cases*14);
 GlobalParams_0 globals{};globals.result_0.data=actual.data();globals.result_0.count=actual.size();
 ComputeVaryingInput varying{};varying.endGroupID={cases,1,1};transport_parity(&varying,nullptr,&globals);
 float largest=0;unsigned compared=0;
 for(unsigned index=0;index<cases;index++) {
  unsigned kind=index%20,rough=(index/20)%9,angle=(index/180)%5,seed=index*9781+1;
  float alpha=fmaxf(.0005f,rough/8.f),cosine=.1f+angle*.2f;
  auto m=fixture(kind,alpha);rt::Frame frame(rt::V(0,0,1));auto wo=rt::V(sqrtf(1-cosine*cosine),0,cosine),wi=rt::normalize(rt::V(.3,.2,1));
  auto eval=rt::evalBsdf(m,frame,wo,wi,m.ior);auto sample=rt::sampleBsdf(m,frame,wo,seed,m.ior);
  auto F=rt::conductorFresnel(m,cosine);auto distance=rt::sampleMediumDistance(m.sigmaA+m.sigmaS,4,seed);auto hg=rt::sampleHg(rt::V(0,0,1),.35,seed);
  float expected[56]={eval.f.x,eval.f.y,eval.f.z,eval.pdf,sample.wi.x,sample.wi.y,sample.wi.z,sample.pdf,sample.weight.x,sample.weight.y,sample.weight.z,sample.eta,F.x,F.y,F.z,rt::fresnelDielectric(cosine,m.ior),distance.distance,distance.pdf,rt::hg(cosine,.35),(float)sample.flags,hg.x,hg.y,hg.z,rt::ggxEnergy(alpha,cosine)};
  auto offset=rt::offsetRayOrigin(rt::V(index%100,-.0000001f,-12345.5f),rt::V(0,1,0),wo);
  expected[24]=offset.x;expected[25]=offset.y;expected[26]=offset.z;expected[27]=rt::shadingNormalCorrection(rt::V(0,0,1),frame.n,wo,wi,true);
  rt::MediumStack stack;stack.enter({rt::V(.1,.1,.1),rt::V(.2,.2,.2),.35,1.333,11});stack.enter({rt::V(.3,.3,.3),rt::V(.4,.4,.4),0,1.5,12});float outside=stack.outside(12);bool exited=stack.exit(11);
  expected[28]=stack.ior();expected[29]=outside;expected[30]=stack.count;expected[31]=exited?1:0;
  static std::vector<unsigned> lut(5*65536);params.lut=lut.data();params.options=256;
  unsigned id=index&65535,packed=(rough*31)|((kind>=10?255:kind==rt::CONDUCTOR?231:10)<<8)|((angle*40)<<16);
  lut[id+65536]=kind%10|(128<<8)|(96<<16)|(32u<<24);lut[id+2*65536]=0x90a02010u;lut[id+3*65536]=0x80204060u;lut[id+4*65536]=0x00004080u;
  auto decoded=decodeMaterial(id,packed,v(.7,.5,.3),index%2==0?16:0);
  expected[32]=decoded.microfacetAlpha;expected[33]=decoded.alphaV;expected[34]=decoded.ior;expected[35]=decoded.type;
  expected[36]=decoded.sigmaA.x;expected[37]=decoded.sigmaA.y;expected[38]=decoded.sigmaA.z;expected[39]=decoded.transmission;
  expected[40]=rt::cutoutVisible(127,255,0);expected[41]=rt::cutoutVisible(128,255,0);expected[42]=rt::cutoutVisible(25,255,16);expected[43]=rt::cutoutVisible(26,255,16);
  auto tiny=rt::sampleMediumDistance(rt::V(.0001,.0001,.0001),.0001,seed);expected[44]=tiny.distance;expected[45]=tiny.pdf;expected[46]=rt::normalize(rt::V(0,0,0)).x;expected[47]=float(seed>>8);
  auto glossy=rt::evalGlossy(m,frame,wo,wi);auto glossySample=rt::sampleGlossy(m,frame,wo,seed);
  expected[48]=glossy.f.x;expected[49]=glossy.f.y;expected[50]=glossy.f.z;expected[51]=glossy.pdf;
  expected[52]=glossySample.weight.x;expected[53]=glossySample.weight.y;expected[54]=glossySample.weight.z;expected[55]=glossySample.pdf;
  float got[56];for(unsigned row=0;row<14;row++){const auto& value=actual[index*14+row];got[row*4]=value.x;got[row*4+1]=value.y;got[row*4+2]=value.z;got[row*4+3]=value.w;}
  for(unsigned component=0;component<56;component++) {
   float error=fabsf(got[component]-expected[component])/fmaxf(1,fabsf(expected[component]));largest=fmaxf(error,largest);compared++;
   if(!std::isfinite(got[component]) || error>0.003f) {std::fprintf(stderr,"Material parity failed case=%u kind=%u rough=%u angle=%u component=%u actual=%g expected=%g error=%g\n",index,kind,rough,angle,component,got[component],expected[component],error);return 1;}
  }
 }
 std::printf("Material 3 Slang/native parity: %u cases, %u components, max normalized error=%g\n",cases,compared,largest);
}
