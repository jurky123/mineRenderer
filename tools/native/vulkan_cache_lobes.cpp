#include "cache_lobes.cpp"
#include "../../native/rt/bsdf.h"
#include <cstdio>
#include <vector>
#include <cmath>
int main(){
 constexpr unsigned cases=40;Camera_0 camera{};camera.width_0=2;camera.height_0=2;camera.padding_0=1;
 std::vector<Vector<float,4>> output(cases*7);std::vector<Vector<unsigned,4>> feedback(1648+28+64+655360+6144);
 GlobalParams_0 globals{};globals.camera_0=&camera;globals.output_0={output.data(),output.size()};globals.pageFeedback_0={feedback.data(),feedback.size()};
 ComputeVaryingInput varying{};varying.endGroupID={cases,1,1};cache_lobes(&varying,nullptr,&globals);
 float maxKernel=0;
 for(unsigned i=0;i<cases;i++){
  rt::Material m;m.type=rt::ROUGH_DIFFUSE;m.baseColor=rt::V(.7,.5,.3);m.microfacetAlpha=.15f+.85f*(i%4)/3;m.alphaV=.2f+.8f*(i%3)/2;m.f0=i%2==0?.04f:.16f;
  float c=.02f+.96f*((i/4)%5)/4;rt::Frame frame(rt::V(0,0,1));auto wo=rt::V(std::sqrt(1-c*c),0,c),wi=rt::normalize(rt::V(.3,.2,1));auto full=rt::evalBsdf(m,frame,wo,wi);
  auto a=output[i*7],mis=output[i*7+4];float expected[4]={full.f.x,full.f.y,full.f.z,full.pdf};
  for(unsigned k=0;k<4;k++)if(std::abs(reinterpret_cast<float*>(&a)[k]-expected[k])>2e-5f){std::fprintf(stderr,"Lobe decomposition/PDF mismatch %u/%u\n",i,k);return 1;}
  if(std::abs(mis.x-full.f.x)>2e-5||std::abs(mis.y-full.f.y)>2e-5||std::abs(mis.z-full.f.z)>2e-5||mis.w>2e-5)return 2;
  auto sample=output[i*7+1],eval=output[i*7+2];for(unsigned k=0;k<4;k++)if(std::abs(reinterpret_cast<float*>(&sample)[k]-reinterpret_cast<float*>(&eval)[k])>2e-5)return 3;
  // Independent dense native integral of (full - exact glossy), including
  // direction-dependent rough diffuse Fresnel/Oren-Nayar at grazing views.
  rt::Vec integral=rt::V(0,0,0);float specIntegral=0;constexpr unsigned N=65536;
  for(unsigned j=0;j<N;j++){float z=std::sqrt(1-(j+.5f)/N),r=std::sqrt(1-z*z),phi=2*rt::Pi*std::fmod(j*.6180339887498949,1.);auto w=rt::V(r*std::cos(phi),r*std::sin(phi),z);auto f=rt::evalBsdf(m,frame,wo,w),g=rt::evalGlossy(m,frame,wo,w);specIntegral+=g.f.x*rt::Pi/N;integral=integral+(f.f-g.f)*((2+.25*w.x+.5*w.z)*rt::Pi/N);}
  auto mc=output[i*7+5];float standardError=std::sqrt(std::max(0.f,mc.y-mc.x*mc.x)/32768);
  if(output[i*7+6].y>2e-5||std::abs(output[i*7+6].z-output[i*7+6].w)>2e-5||std::abs(mc.x-specIntegral)>.002f+5*standardError||mc.z>mc.w+.015f||output[i*7+6].x>2e-5){std::fprintf(stderr,"Exact specular roulette/PDF failure case=%u mean=%g reference=%g stderr=%g alive=%g probability=%g boundary=%g/%g/%g/%g\n",i,mc.x,specIntegral,standardError,mc.z,mc.w,output[i*7+6].x,output[i*7+6].y,output[i*7+6].z,output[i*7+6].w);return 5;}
  auto cached=output[i*7+3];float err=std::max({std::abs(cached.x-integral.x),std::abs(cached.y-integral.y),std::abs(cached.z-integral.z)})/std::max(.001f,rt::maxComponent(integral));maxKernel=std::max(maxKernel,err);
  if(!std::isfinite(err)||err>.02){std::fprintf(stderr,"Rough diffuse cache kernel failed %u relative=%g\n",i,err);return 4;}
 }
 std::printf("Material-aware cache: %u native lobe/PDF/MIS cases; conditional and marginal specular throughput/PDF + unbiased roulette; independent affine incident-light dense integral max relative error=%g passed\n",cases,maxKernel);
}
