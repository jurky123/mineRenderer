// Execute production queue guards and indexing; CPU substitutes only atomic reservation.
#include "continuation_queue.cpp"
#include <vector>
#include <algorithm>
#include <cstdio>
int main(){
 Camera_0 camera{};camera.width_0=96;camera.height_0=1;camera.padding_0=2;constexpr unsigned capacity=192;
 std::vector<Vector<uint32_t,4>> feedback(1568+2*capacity);GlobalParams_0 globals{};globals.camera_0=&camera;globals.pageFeedback_0={feedback.data(),feedback.size()};
 for(unsigned bounce:{1u,2u,4u,5u,6u})for(bool compact:{false,true}){
  std::fill(feedback.begin(),feedback.end(),Vector<uint32_t,4>{});camera.frame_0=bounce;camera.origin_0.w=compact?4:0;
  for(unsigned i=1;i<6;i++){feedback[520+i].y=feedback[520+i].z=1;}
  std::vector<unsigned> expected;
  // Scrambled invocation order exercises append order independently of logical path indices.
  for(unsigned j=0;j<capacity;j++){unsigned i=(j*73)%capacity;ComputeVaryingInput varying{};varying.startGroupID={i,0,0};varying.endGroupID={i+1,1,1};continuation_queue(&varying,nullptr,&globals);if(compact&&bounce<6&&(bounce==4||(i%3!=0&&i%5!=0)))expected.push_back(i);}
  if(feedback[520+bounce].x!=expected.size())return 1;
  if(bounce<6&&(feedback[520+bounce].y!=1||feedback[520+bounce].z!=1||feedback[520+bounce].w!=0))return 1;
  std::vector<unsigned> actual;for(unsigned i=0;i<expected.size();i++)actual.push_back(feedback[1568+(bounce&1)*capacity+i].x);
  std::sort(expected.begin(),expected.end());std::sort(actual.begin(),actual.end());if(actual!=expected)return 1;
 }
 std::puts("Production continuation queue guards/indirect count: empty, full capacity, two spp, dead/invalid/terminal paths and ping-pong indexing passed (CPU reservation substitute; no GPU concurrency claim)");
}
