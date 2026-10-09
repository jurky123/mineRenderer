#include "reconstruction_contract.cpp"
#include <cmath>
#include <initializer_list>
#include <cstdio>
int main(){float4 r[1288]{};GlobalParams_0 g{};g.result_0={r,1288};ComputeVaryingInput v{};v.endGroupID={1,1,1};reconstruction_contract(&v,nullptr,&g);
 if(r[0].x!=4||r[0].y!=1||r[0].z!=6||r[1].x!=1||r[1].y!=0||r[1].z!=0)return 1;
 if(std::abs(r[2].y+1)>1e-6||r[2].x!=0||r[2].z!=0||r[3].x!=0||r[3].y!=0||r[3].z!=0)return 2;
 if(r[4].x!=0||r[4].y!=0||r[4].z!=0)return 3;
 float maxError=0;for(int i=5;i<1029;i++){if(!std::isfinite(r[i].x)||r[i].x>.0001)return 4;maxError=std::fmax(maxError,r[i].x);float length=std::sqrt(r[i].y*r[i].y+r[i].z*r[i].z+r[i].w*r[i].w);if(std::abs(length-1)>1e-5)return 5;}
 for(int i=1029;i<1285;i++)for(float x:{r[i].x,r[i].y,r[i].z})if(!std::isfinite(x)||x<0||x>1)return 6;
 if(r[1285].x!=1||r[1285].y!=0||r[1285].z!=1||r[1285].w!=1||r[1286].x!=3.5||r[1286].y!=.25||r[1286].z!=.5||r[1286].w!=15||r[1287].x!=1||r[1287].y!=3||r[1287].z!=9)return 7;
 std::printf("Reconstruction contract: planar moving-endpoint reflection, stationary zero, normal transmission/TIR; 1024 packed direction cases max vector error=%g; 256 GGX guide reflectance bounds passed\n",maxError);
}
