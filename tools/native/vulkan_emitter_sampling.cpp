#include "emitter_sampling.cpp"
#include <vector>
#include <cstring>
#include <cmath>
#include <cstdio>
#include <cstdlib>
int main(){
 constexpr unsigned N=100000;std::vector<unsigned> assets(80);assets[124/4]=2;assets[156/4]=192;
 auto put=[&](unsigned offset,float value){std::memcpy(reinterpret_cast<char*>(assets.data())+offset,&value,4);};
 for(unsigned i=0;i<2;i++){unsigned a=192+i*64;put(a,i*2);put(a+8,2);put(a+12,i==0?1:4);put(a+16,i*2+1);put(a+24,2);put(a+28,.5);put(a+32,i*2);put(a+36,1);put(a+40,2);put(a+44,i==0?1:3);assets[(a+48)/4]=i;}
 std::vector<float4> results(N*2);GlobalParams_0 globals{};globals.assets_0={assets.data(),assets.size()*4};globals.result_0={results.data(),N*2};ComputeVaryingInput varying{};varying.endGroupID={N,1,1};emitter_sampling(&varying,nullptr,&globals);
 unsigned first=0;for(unsigned i=0;i<N;i++){auto r=results[i*2];first+=r.x==1;if(!std::isfinite(r.y)||r.y<=0||std::abs(r.y-r.z)>1e-4f*std::max(1.f,r.y)||r.w<2){std::fprintf(stderr,"Emitter solid-angle PDF/matched hit PDF failed\n");return 1;}}
 if(std::abs(first/float(N)-.25f)>.006f){std::fprintf(stderr,"Emitter CDF frequency failed\n");return 1;}
 assets[(256+56)/4]=1;put(256+16,20);put(256+20,12.4);put(256+24,4.8);
 emitter_sampling(&varying,nullptr,&globals);unsigned points=0;
 for(unsigned i=0;i<N;i++){auto r=results[i*2];if(r.x==-1){points++;auto color=results[i*2+1];if(std::abs(r.y-.75f)>1e-6||r.z!=0||std::abs(color.x*r.w*r.w-20)>1e-4||color.w!=1)return 1;}}
 if(std::abs(points/float(N)-.75f)>.006f)return 1;
 std::printf("Actual Slang emissive sampler: %u samples, area CDF, matched area NEE/hit PDFs, flame discrete PDF/inverse-square/no BSDF competitor passed\n",N*2);
}
