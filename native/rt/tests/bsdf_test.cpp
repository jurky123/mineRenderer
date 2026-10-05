#include "../bsdf.h"
#include "../surface.h"
#include "../environment.h"
#include "../light_sampling.h"
#include <vector>
#include <cassert>
#include <iostream>
using namespace rt;
static bool finite(Vec a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
int main(){
 assert(!cutoutVisible(127,255,0)&&cutoutVisible(128,255,0));assert(!cutoutVisible(25,255,16)&&cutoutVisible(26,255,16));assert(!cutoutVisible(255,127,0));assert(cutoutVisible(200,163,0));
 // UV-derived frames preserve material U/V axes, including mirrored UV handedness.
 {Frame f(V(0,0,1),V(0,1,0),V(1,0,0));assert(dot(f.t,V(0,1,0))>.999f&&dot(f.b,V(1,0,0))>.999f);
  Vec w=normalize(V(.2f,.3f,1));assert(maxComponent(f.world(f.local(w))-w)<1e-6f);
  Frame degenerate(V(0,0,1),V(0,0,1),V(0,0,0));assert(finite(degenerate.t));
 }
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
 // Lossless conductor: compensation restores masked energy without a brightness multiplier.
 {Material m;m.type=CONDUCTOR;m.baseColor=V(1,1,1);m.eta=V(1,1,1);m.k=V(100000,100000,100000);m.microfacetAlpha=m.alphaV=1;Vec energy=V(0,0,0);const int N=200000;for(int i=0;i<N;i++){auto sample=sampleBsdf(m,frame,V(0,0,1),seed);energy=energy+sample.weight/N;}assert(fabsf(energy.x-1)<.035f);m.multipleScattering=false;Vec single=V(0,0,0);for(int i=0;i<N;i++)single=single+sampleBsdf(m,frame,V(0,0,1),seed).weight/N;assert(single.x<.4f&&energy.x>.96f);}
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
 // Scale-aware origin offsets preserve thin nearby interfaces and move outward at large coordinates.
 {for(float coordinate:{0.f,1.f,1024.f,65536.f}){Vec p=V(coordinate,0,0),n=V(1,0,0);auto outward=offsetRayOrigin(p,n,n),inward=offsetRayOrigin(p,n,n*-1);assert(outward.x>p.x&&inward.x<p.x);if(coordinate<1024)assert(outward.x-p.x<.001f);}assert(validShadingHemisphere(V(0,0,1),normalize(V(.2f,0,1)),V(0,0,1),V(0,0,1)));assert(!validShadingHemisphere(V(0,0,1),normalize(V(.9f,0,1)),V(0,0,1),normalize(V(1,0,-.1f))));}
 // HG sampling PDF produces the expected mean cosine g in the world-space frame.
 {double mean=0;const int N=200000;Vec axis=normalize(V(.2f,.9f,.3f));for(int i=0;i<N;i++){Vec d=sampleHg(axis,.7f,seed);assert(finite(d));assert(fabsf(dot(d,d)-1)<1e-4f);mean+=dot(axis,d)/N;}assert(fabs(mean-.7)<.004);}
 // Colored homogeneous segment scattering integrates T(t)*sigma_s without a distance bias.
 {Vec sigma=V(.2f,.06f,.035f);const int N=200000;Vec integral=V(0,0,0);for(int i=0;i<N;i++){auto s=sampleMediumDistance(sigma,8,seed);assert(s.distance>=0&&s.distance<=8&&s.pdf>0);integral=integral+expNeg(sigma,s.distance)*(1/(s.pdf*N));}Vec exact=V(-expm1f(-sigma.x*8)/sigma.x,-expm1f(-sigma.y*8)/sigma.y,-expm1f(-sigma.z*8)/sigma.z);assert(fabsf(integral.x-exact.x)<.03f&&fabsf(integral.y-exact.y)<.04f&&fabsf(integral.z-exact.z)<.04f);}
 // Hierarchical emitter selection and binary hit lookup retain lights beyond the old 4096 cap.
 {LightCluster clusters[]={{0,4096,4},{4096,8192,10}};assert(lightClusterIndex(clusters,2,3.9f)==0);assert(lightClusterIndex(clusters,2,4.f)==1);struct TestLight{unsigned identity;};std::vector<TestLight> lights(8192);for(unsigned i=0;i<lights.size();i++)lights[i].identity=i*2;assert(emitterIdentityIndex(lights.data(),lights.size(),12000)==6000);assert(emitterIdentityIndex(lights.data(),lights.size(),12001)==-1);}
 // HDR environment CDF matches solid-angle PDF, including pole cells and black maps.
 {std::vector<float> cdf(EnvironmentWidth*EnvironmentHeight+EnvironmentHeight);float total=0;double integral=0;
  for(int y=0;y<EnvironmentHeight;y++){float row=0;for(int x=0;x<EnvironmentWidth;x++){float luminance=(x<16&&y<8)?20:1;row+=luminance*environmentSolidAngle(y);cdf[y*EnvironmentWidth+x]=row;}total+=row;cdf[EnvironmentWidth*EnvironmentHeight+y]=total;}
  for(int y=0;y<EnvironmentHeight;y++)for(int x=0;x<EnvironmentWidth;x++){float theta=Pi*(y+.5f)/EnvironmentHeight,phi=2*Pi*(x+.5f)/EnvironmentWidth;Vec d=V(sinf(theta)*cosf(phi),cosf(theta),sinf(theta)*sinf(phi));integral+=environmentDensity(cdf.data(),d)*environmentSolidAngle(y);}
  assert(fabs(integral-1)<1e-4);int hot=0;const int N=200000;for(int k=0;k<N;k++){auto d=sampleEnvironment(cdf.data(),seed);assert(finite(d));auto i=environmentCell(d);hot+=(i/EnvironmentWidth<8&&i%EnvironmentWidth<16);assert(environmentDensity(cdf.data(),d)>0);}
  double hotMass=0;for(int y=0;y<8;y++)hotMass+=16*20*environmentSolidAngle(y)/total;assert(fabs(hot/(double)N-hotMass)<.004);
  std::fill(cdf.begin(),cdf.end(),0);assert(fabsf(environmentDensity(cdf.data(),V(0,1,0))-1/(4*Pi))<1e-7f);
 }
 // A coated diffuse substrate has only the authored top interface, not a hidden plastic highlight.
 {Material m;m.type=COATED_DIFFUSE;m.coatWeight=0;m.baseColor=V(.6f,.5f,.4f);
  auto e=evalBsdf(m,frame,V(0,0,1),V(0,0,1));assert(fabsf(e.f.x-.6f/Pi)<1e-6f);
  assert(evalGlossy(m,frame,V(0,0,1),V(0,0,1)).f.x==0);
 }
 // Two-technique primary glossy environment MIS agrees with direct hemisphere quadrature.
 // BSDF null events remain in the estimator; never renormalize accepted VNDF samples.
 {Material m;setConductor(m,230);m.baseColor=V(1,1,1);m.microfacetAlpha=m.alphaV=.3f;
  Vec wo=normalize(V(.3f,0,1)),sum=V(0,0,0),reference=V(0,0,0);const int N=400000;
  for(int i=0;i<N;i++){
   float z=rng(seed),a=2*Pi*rng(seed),r=sqrtf(1-z*z);Vec wi=V(r*cosf(a),r*sinf(a),z);
   auto e=evalGlossy(m,frame,wo,wi);float lp=1/(2*Pi);
   reference=reference+e.f*(z/lp/N);
   sum=sum+e.f*(z/lp*powerHeuristic(lp,e.pdf)/N);
   auto b=sampleGlossy(m,frame,wo,seed);if(b.pdf>0)sum=sum+b.weight*(powerHeuristic(b.pdf,lp)/N);
  }
  assert(fabsf(sum.x-reference.x)<.015f&&fabsf(sum.y-reference.y)<.015f&&fabsf(sum.z-reference.z)<.015f);
 }
 Material gold,copper;setConductor(gold,231);setConductor(copper,234);auto gf=conductorFresnel(gold,1),cf=conductorFresnel(copper,1);assert(gf.x>gf.z&&cf.x>cf.z);assert(fresnelDielectric(.1f,1/1.5f)==1);
 MediumStack stack;Medium glass{V(.2f,.1f,0),V(0,0,0),0,1.5f,1},water{V(.16f,.06f,.035f),V(.018f,.035f,.045f),.7f,1.333f,2};assert(stack.enter(glass));assert(stack.enter(water));assert(stack.outside(2)==1.5f);assert(stack.exit(1));assert(stack.ior()==1.333f);assert(stack.exit(2)&&stack.ior()==1);assert(!stack.exit(123));
 // Nested same-material boundaries are counted and removed one at a time.
 assert(stack.enter(glass)&&stack.enter(glass));assert(stack.exit(1)&&stack.count==1);assert(stack.exit(1)&&stack.count==0);
 auto attenuation=expNeg(glass.sigmaA,4);assert(attenuation.x<attenuation.y&&attenuation.y<attenuation.z);float phaseIntegral=0;for(int i=0;i<100000;i++){float cosine=-1+2*(i+.5f)/100000;phaseIntegral+=hg(cosine,.7f)*4*Pi/100000;}assert(fabsf(phaseIntegral-1)<.002f);
 assert(fabsf(powerHeuristic(.3f,.7f)+powerHeuristic(.7f,.3f)-1)<1e-6f);
 std::cout<<"BSDF energy, PDF, reciprocity, medium and MIS regressions passed\n";
}
