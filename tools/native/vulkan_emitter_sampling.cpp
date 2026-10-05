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
 std::vector<float4> results(N);GlobalParams_0 globals{};globals.assets_0={assets.data(),assets.size()*4};globals.result_0={results.data(),N};ComputeVaryingInput varying{};varying.endGroupID={N,1,1};emitter_sampling(&varying,nullptr,&globals);
 unsigned first=0;for(auto r:results){first+=r.x==1;if(!std::isfinite(r.y)||r.y<=0||std::abs(r.y-r.z)>1e-4f*std::max(1.f,r.y)||r.w<2){std::fprintf(stderr,"Emitter solid-angle PDF/matched hit PDF failed\n");return 1;}}
 if(std::abs(first/float(N)-.25f)>.006f){std::fprintf(stderr,"Emitter CDF frequency failed\n");return 1;}
 std::printf("Actual Slang emissive sampler: %u samples, area CDF and matched NEE/hit solid-angle PDFs passed\n",N);
}
