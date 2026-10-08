#include "diffuse_kernel.cpp"
#include "../../native/rt/bsdf.h"
#include <cstdio>
#include <vector>
#include <cmath>
#include <fstream>
#include <cstring>
int main(){
 constexpr unsigned cases=256;Camera_0 camera{};camera.width_0=2;camera.height_0=2;camera.padding_0=1;
 std::vector<Vector<float,4>> output(cases*3);std::vector<Vector<unsigned,4>> feedback(1648+28+64+655360+6144);
 std::ifstream file("src/main/resources/assets/voxellight/rt/diffuse-kernel.bin",std::ios::binary);
 std::vector<char> blob((std::istreambuf_iterator<char>(file)),{});if(blob.size()!=16+26004){std::fprintf(stderr,"Kernel resource missing or invalid\n");return 1;}
 std::vector<unsigned> assets((256+26004)/4);assets[240/4]=256;std::memcpy(reinterpret_cast<char*>(assets.data())+256,blob.data()+16,26004);
 GlobalParams_0 globals{};globals.assets_0.data=assets.data();globals.assets_0.sizeInBytes=assets.size()*4;globals.camera_0=&camera;globals.output_0={output.data(),output.size()};globals.pageFeedback_0={feedback.data(),feedback.size()};
 ComputeVaryingInput varying{};varying.endGroupID={cases,1,1};diffuse_kernel(&varying,nullptr,&globals);float maxError=0,maxQuadratureDifference=0,maxQuadratureError=0;
 for(unsigned i=0;i<cases;i++){
  rt::Material m;m.type=rt::ROUGH_DIFFUSE;m.baseColor=rt::V(.7,.5,.3);float root=.1f+.4f*(i%8)/7;m.f0=root*root;m.microfacetAlpha=.15f+.85f*((i/64)%4)/3;
  float cosine=.001f+.998f*((i/8)%8)/7;rt::Frame frame(rt::V(0,0,1));auto wo=rt::V(std::sqrt(1-cosine*cosine),0,cosine);rt::Vec integral=rt::V(0,0,0);constexpr unsigned N=65536;
  for(unsigned j=0;j<N;j++){float z=std::sqrt(1-(j+.5f)/N),r=std::sqrt(1-z*z),phi=2*rt::Pi*std::fmod(j*.6180339887498949,1.);auto w=rt::V(r*std::cos(phi),r*std::sin(phi),z);auto f=rt::evalBsdf(m,frame,wo,w),g=rt::evalGlossy(m,frame,wo,w);float li=2-.9f*z+(i%2==0?.8f:-.8f)*w.x+.3f*w.y;integral=integral+(f.f-g.f)*(li*rt::Pi/N);}
  auto v=output[i*3],clip=output[i*3+1],old=output[i*3+2];float err=std::max({std::abs(v.x-integral.x),std::abs(v.y-integral.y),std::abs(v.z-integral.z)})/std::max(.001f,rt::maxComponent(integral));maxError=std::max(maxError,err);
  float scale=std::max(.001f,rt::maxComponent(integral));
  maxQuadratureDifference=std::max(maxQuadratureDifference,std::max({std::abs(v.x-old.x),std::abs(v.y-old.y),std::abs(v.z-old.z)})/scale);
  maxQuadratureError=std::max(maxQuadratureError,std::max({std::abs(old.x-integral.x),std::abs(old.y-integral.y),std::abs(old.z-integral.z)})/scale);
  if(!std::isfinite(err)||err>.02||std::abs(clip.x)+std::abs(clip.y)+std::abs(clip.z)>1e-6){std::fprintf(stderr,"Preintegrated kernel failure case=%u error=%g clip=%g/%g/%g\n",i,err,clip.x,clip.y,clip.z);return 1;}
 }
 std::printf("Preintegrated diffuse kernel: 256 F0/roughness/grazing/positive-affine cases vs dense native integral; clipped fallback exact; max relative error=%g, old64 error=%g, kernel-vs-old64=%g passed\n",maxError,maxQuadratureError,maxQuadratureDifference);
}
