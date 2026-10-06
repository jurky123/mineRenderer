#include "reconstruction_fixture.cpp"
#include <cmath>
#include <cstdio>
#include <cstdlib>
int main(){float4 r[6]{};GlobalParams_0 globals{};globals.result_0={r,6};ComputeVaryingInput varying{};varying.endGroupID={1,1,1};reconstruction_fixture(&varying,nullptr,&globals);
 if(r[0].x!=3||r[0].y!=-2||r[1].x!=1||r[1].y!=1||r[1].z!=0||r[1].w!=0||r[2].x!=0||r[2].y!=0||r[2].z!=0||r[2].w!=0||r[3].x!=1||r[3].y!=0||r[3].z!=0||r[3].w!=0)return 1;
 if(r[4].x!=1||r[4].y!=0||r[4].z!=0||r[4].w!=0||r[5].x!=10.5f||r[5].y!=22||r[5].z!=30)return 2;
 std::puts("Actual GLSL reconstruction: pixel flow sign, surface footprint, plane rejection, dynamic identity, prior pose barycentric mapping, normal/material/albedo validation and sky rotation passed");}
