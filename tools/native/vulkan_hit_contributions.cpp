#include "hit_contributions.cpp"
#include <vector>
#include <cstdio>
int main(){
 constexpr unsigned cases=48;
 std::vector<Vector<float,4>> output(cases*2),result(cases);
 std::vector<PathAov_0> aovs(cases*2);GlobalParams_0 globals{};
 globals.output_0={output.data(),output.size()};globals.pathAovs_0={aovs.data(),aovs.size()};globals.result_0={result.data(),result.size()};
 ComputeVaryingInput varying{};varying.endGroupID={cases,1,1};hit_contributions(&varying,nullptr,&globals);
 for(unsigned i=0;i<cases;i++){auto r=result[i];if(r.x||r.y||r.z||r.w){std::fprintf(stderr,"Per-hit contribution parity case=%u beauty/AOV/status/seed=%g/%g/%g/%g\n",i,r.x,r.y,r.z,r.w);return 1;}}
 std::puts("Production per-hit contributions: 48 bitwise global/local comparisons; ordered large-prefix sums, direct/continuation AOV channels, NaN/Inf/negative/overflow handling, preserved AOV metadata and RNG seed passed");
}
