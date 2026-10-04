#include "../bsdf.h"
#include "../surface.h"
#include "../dispatch.h"
#include <cassert>
#include <iostream>
using namespace rt;
static bool finite(Vec a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
int main(){
 for(unsigned pixels:{1u,65u,8193u,640u*338u}){ReferenceSweep sweep;unsigned total=0;while(sweep.samples<4){unsigned count=sweep.count(pixels,4,64);assert(count>0&&count<=64);total+=count;sweep.advance(pixels,count);}assert(total==pixels*4&&sweep.offset==0&&sweep.count(pixels,4,64)==0);sweep.reset();assert(sweep.offset==0&&sweep.samples==0);}
 assert(referenceBudget(64,20000000)==32&&referenceBudget(8,99999999)==8&&referenceBudget(64,1000000)==128&&referenceBudget(1024,1)==1024&&referenceBudget(64,10000000)==64);

 for(unsigned target:{4u,256u,4096u}){unsigned samples=0;while(samples<target){assert(referenceBatch(target,samples)==1);samples+=referenceBatch(target,samples);}assert(samples==target&&referenceBatch(target,samples)==0);assert(referenceBatch(target,target+1)==0);}
 for(unsigned pixels:{1u,8192u,8193u,640u*360u}){unsigned covered=0;while(covered<pixels){auto chunk=referenceChunk(pixels,covered);assert(chunk>0&&chunk<=8192&&covered+chunk<=pixels);covered+=chunk;}assert(covered==pixels&&referenceChunk(pixels,covered)==0);}

 assert(!cutoutVisible(127,255,0)&&cutoutVisible(128,255,0));assert(!cutoutVisible(25,255,16)&&cutoutVisible(26,255,16));assert(!cutoutVisible(255,127,0));assert(cutoutVisible(200,163,0));
 Frame frame(V(0,0,1));unsigned seed=736284;
 for(unsigned type=0;type<=9;type++)for(float rough:{.02f,.05f,.1f,.2f,.4f,.7f,1.f}){
  Material m;m.type=type;m.baseColor=V(.65f,.35f,.12f);m.microfacetAlpha=m.alphaV=rough*rough;m.transmission=.7f;
  if(type==CONDUCTOR||type==COATED_CONDUCTOR)setConductor(m,231);
  if(type==COATED_DIFFUSE||type==COATED_CONDUCTOR)m.coatWeight=.65f;
  Vec wo=normalize(V(.3f,0,1));Vec energy=V(0,0,0);int accepted=0;
  for(int i=0;i<60000;i++){
   auto s=sampleBsdf(m,frame,wo,seed);assert(std::isfinite(s.pdf)&&s.pdf>=0&&finite(s.weight));if(s.pdf==0)continue;accepted++;
   assert(fabsf(dot(s.wi,s.wi)-1)<1e-4f);assert(s.weight.x>=0&&s.weight.y>=0&&s.weight.z>=0);
   energy=energy+s.weight/60000;
   if(!(s.flags&DELTA)){auto e=evalBsdf(m,frame,wo,s.wi);assert(fabsf(e.pdf-s.pdf)<=1e-5f*fmaxf(1,e.pdf));Vec expected=e.f*(fabsf(s.wi.z)/e.pdf);assert(maxComponent(expected-s.weight)<1e-4f&&maxComponent(s.weight-expected)<1e-4f);}
  }
  assert(accepted>1000);assert(maxComponent(energy)<1.04f);
  std::cout<<"furnace "<<type<<" r="<<rough<<" energy="<<maxComponent(energy)<<"\n";
  if(type==CONDUCTOR||type==COATED_CONDUCTOR||type==DIFFUSE||type==ROUGH_DIFFUSE||type==COATED_DIFFUSE){for(int i=0;i<1000;i++){auto wi=cosine(seed);auto a=evalBsdf(m,frame,wo,wi),b=evalBsdf(m,frame,wi,wo);assert(maxComponent(a.f-b.f)<.001f&&maxComponent(b.f-a.f)<.001f);}}
 }
 // White furnace and grazing white dielectric energy; physically accounted masking loss is allowed.
 for(unsigned type:{DIFFUSE,ROUGH_DIFFUSE,COATED_DIFFUSE,CONDUCTOR,COATED_CONDUCTOR,DIELECTRIC})for(float cosine:{.1f,.5f,1.f}){
  Material m;m.type=type;m.baseColor=V(1,1,1);m.microfacetAlpha=m.alphaV=.3f;m.transmission=1;if(type==CONDUCTOR||type==COATED_CONDUCTOR)setConductor(m,237);if(type==COATED_DIFFUSE||type==COATED_CONDUCTOR)m.coatWeight=.5f;Vec wo=V(sqrtf(1-cosine*cosine),0,cosine),energy=V(0,0,0);for(int i=0;i<100000;i++){auto sample=sampleBsdf(m,frame,wo,seed);assert(finite(sample.weight));energy=energy+sample.weight/100000;}assert(maxComponent(energy)<=1.04f);if(type==DIFFUSE)assert(fabsf(energy.x-1)<.002f);
 }
 // Reflection-only VNDF must agree with evaluation and PDF (including coating mixtures).
 for(unsigned type:{ROUGH_DIFFUSE,CONDUCTOR,COATED_DIFFUSE,COATED_CONDUCTOR}){Material m;m.type=type;if(type==CONDUCTOR||type==COATED_CONDUCTOR)setConductor(m,234);m.coatWeight=(type==COATED_DIFFUSE||type==COATED_CONDUCTOR)?.6f:0;for(int i=0;i<10000;i++){Vec wo=normalize(V(.2f,0,1));auto sample=sampleGlossy(m,frame,wo,seed);if(sample.pdf<=0)continue;auto e=evalGlossy(m,frame,wo,sample.wi);assert(fabsf(sample.pdf-e.pdf)<1e-6f*fmaxf(1,e.pdf));assert(finite(sample.weight));}}
 // Compare hemisphere PDF quadrature to empirical non-null probability; rejected VNDF samples retain null mass.
 for(unsigned type:{ROUGH_DIFFUSE,CONDUCTOR,COATED_DIFFUSE,DIELECTRIC,DIFFUSE_TRANSMISSION}){
  Material m;m.type=type;m.baseColor=V(.6f,.5f,.4f);m.microfacetAlpha=.5f;m.alphaV=.3f;m.transmission=.7f;if(type==CONDUCTOR)setConductor(m,234);if(type==COATED_DIFFUSE)m.coatWeight=.5f;Vec wo=normalize(V(.4f,0,1));double integral=0;int accepted=0;const int N=400000;for(int i=0;i<N;i++){float z=1-2*rng(seed),phi=2*Pi*rng(seed),r=sqrtf(fmaxf(0,1-z*z));auto e=evalBsdf(m,frame,wo,V(r*cosf(phi),r*sinf(phi),z));integral+=e.pdf*4*Pi/N;auto sample=sampleBsdf(m,frame,wo,seed);accepted+=sample.pdf>0;}assert(fabs(integral-accepted/(double)N)<.035);assert(integral<=1.035);}
 {Material m;m.type=DIELECTRIC;assert(evalBsdf(m,frame,V(0,0,1),V(0,0,1),1.5f,2).pdf==0);assert(evalBsdf(m,frame,V(0,0,1),V(0,0,-1),1.5f,1).pdf==0);}
 // Delta thin sheets preserve reflection + transmission and Beer; TIR never escapes into an invalid medium.
 {Material m;m.type=THIN_DIELECTRIC;m.transmission=1;m.sigmaA=V(0,0,0);Vec wo=normalize(V(.4f,0,1));auto r=sampleBsdf(m,frame,wo,seed,1.5f,1),t=sampleBsdf(m,frame,wo,seed,1.5f,2);assert(fabsf(r.weight.x+t.weight.x-1)<1e-5f);assert((r.flags&DELTA)&&(t.flags&TRANSMISSION));assert(dot(t.wi,wo)<-.999f);}
 {Material m;m.type=DIELECTRIC;m.transmission=1;m.microfacetAlpha=m.alphaV=.0005f;Vec wo=normalize(V(.98f,0,.2f));for(int j=0;j<10000;j++){auto s=sampleBsdf(m,frame,wo,seed,1/1.5f);if(s.pdf>0)assert(s.flags&REFLECTION);}}
 {Material m;m.type=DIFFUSE;assert(evalGlossy(m,frame,V(0,0,1),V(0,0,1)).pdf==0);assert(sampleGlossy(m,frame,V(0,0,1),seed).pdf==0);}
 Material gold,copper;setConductor(gold,231);setConductor(copper,234);auto gf=conductorFresnel(gold,1),cf=conductorFresnel(copper,1);assert(gf.x>gf.z&&cf.x>cf.z);assert(fresnelDielectric(.1f,1/1.5f)==1);
 MediumStack stack;Medium glass{V(.2f,.1f,0),V(0,0,0),0,1.5f,1},water{V(.16f,.06f,.035f),V(.018f,.035f,.045f),.7f,1.333f,2};assert(stack.enter(glass));assert(stack.enter(water));assert(stack.outside(2)==1.5f);assert(stack.exit(1));assert(stack.ior()==1.333f);assert(stack.exit(2)&&stack.ior()==1);assert(!stack.exit(123));
 // Nested same-material boundaries are counted and removed one at a time.
 assert(stack.enter(glass)&&stack.enter(glass));assert(stack.exit(1)&&stack.count==1);assert(stack.exit(1)&&stack.count==0);
 auto attenuation=expNeg(glass.sigmaA,4);assert(attenuation.x<attenuation.y&&attenuation.y<attenuation.z);float phaseIntegral=0;for(int i=0;i<100000;i++){float cosine=-1+2*(i+.5f)/100000;phaseIntegral+=hg(cosine,.7f)*4*Pi/100000;}assert(fabsf(phaseIntegral-1)<.002f);
 assert(fabsf(powerHeuristic(.3f,.7f)+powerHeuristic(.7f,.3f)-1)<1e-6f);
 std::cout<<"BSDF energy, PDF, reciprocity, medium and MIS regressions passed\n";
}
