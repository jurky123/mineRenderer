#include "flame_sampling.cpp"
#include <vector>
#include <cstring>
#include <cmath>
#include <cstdio>
int main(){
 constexpr unsigned N=200000;std::vector<unsigned> assets((208+16*64)/4);assets[192/4]=16;assets[196/4]=208;
 auto put=[&](unsigned offset,float value){std::memcpy(reinterpret_cast<char*>(assets.data())+offset,&value,4);};
 double expected[3]={0,0,0};
 for(unsigned i=0;i<16;i++){unsigned a=208+i*64;float distance=i+1;put(a+8,distance);float colors[3]={float((i+1)*7),float((16-i)*11),float(i%2?17:3)};
  for(unsigned c=0;c<3;c++){put(a+16+c*4,colors[c]);if((i+1)%3!=0)expected[c]+=colors[c]/(distance*distance);}}
 std::vector<float4> result(N);GlobalParams_0 globals{};globals.assets_0={assets.data(),assets.size()*4};globals.result_0={result.data(),N};ComputeVaryingInput varying{};varying.endGroupID={N,1,1};flame_sampling(&varying,nullptr,&globals);
 double measured[3]={0,0,0};for(auto r:result){if(!std::isfinite(r.x)||!std::isfinite(r.y)||!std::isfinite(r.z)||r.x<0||r.y<0||r.z<0)return 1;measured[0]+=r.x/N;measured[1]+=r.y/N;measured[2]+=r.z/N;}
 for(unsigned c=0;c<3;c++)if(std::abs(measured[c]-expected[c])>.008*expected[c]){std::fprintf(stderr,"RIS flame energy/occlusion failed: %g vs %g\n",measured[c],expected[c]);return 1;}
 assets[192/4]=0;flame_sampling(&varying,nullptr,&globals);for(auto r:result)if(r.x!=0||r.y!=0||r.z!=0)return 1;
 std::printf("Production flame RIS: %u samples, nearest two deterministic, occluded/color-weighted remainder energy within 0.8%%, empty table passed\n",N);
}
