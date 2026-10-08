#include "dlss_fixture.cpp"
#include <cmath>
#include <cstdio>
int main(){float4 r[4]{};GlobalParams_0 globals{};globals.result_0={r,4};ComputeVaryingInput varying{};varying.endGroupID={1,1,1};dlss_fixture(&varying,nullptr,&globals);
 if(std::abs(r[0].x-5)>1e-5||std::abs(r[0].y+9)>1e-5||std::abs(r[0].z-.4)>1e-5||r[0].w!=0)return 1;
 if(r[1].x!=0||r[1].y!=0||r[1].z!=0||r[1].w!=1)return 2;
 if(r[2].x!=0||r[2].y!=0||r[2].z!=0||r[2].w!=0)return 3;
 if(std::abs(r[3].x-.3)>1e-6||std::abs(r[3].y-.09)>1e-6||r[3].z!=0||r[3].w!=1)return 4;
 std::puts("Actual GLSL DLSS guides: previous-current pixel motion, stationary jitter-independent motion, reverse-Z/sky depth and invalid clip guards passed");}
