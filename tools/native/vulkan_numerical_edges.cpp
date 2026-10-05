#include "numerical_edges.cpp"
#include <cmath>
#include <cstdio>
#include <cstdlib>
void check(bool valid){if(!valid){std::fprintf(stderr,"Numerical boundary regression failed\n");std::exit(1);}}
int main(){
 float4 results[8]{};GlobalParams_0 globals{};globals.result_0={results,8};ComputeVaryingInput varying{};varying.endGroupID={1,1,1};numerical_edges(&varying,nullptr,&globals);
 check(results[0].x==0&&results[0].y==1&&results[0].z==.5f&&results[0].w==.5f);
 check(std::abs(results[1].x-.8f)<1e-6&&results[1].w==1&&results[2].z==-1&&results[2].w==1);
 for(int i=3;i<=4;i++)check(results[i].x==.2f&&results[i].y==.3f&&results[i].z==.4f&&results[i].w==12);
 check(results[5].x==.8f&&results[5].w==1);check(std::abs(results[6].x-(.2f+.6f/64))<1e-6&&results[6].w==64);
 check(std::isfinite(results[7].x)&&std::abs(results[7].x/1e30f-1)<1e-6);
 std::puts("Actual Slang BSDF + GLSL accumulation boundaries: matched IOR/grazing, overflow-safe MIS, invalid rejection, corrupt history recovery, live EMA, extreme finite HDR passed");
}
