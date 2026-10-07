#include "realtime_policy.cpp"
#include <cstdio>
#include <cmath>
#include <vector>
int main(){
 Camera_0 camera{};camera.width_0=2;camera.height_0=2;camera.padding_0=1;camera.frame_0=101;
 Vector<float,4> output[32]{};std::vector<Vector<unsigned,4>> feedback(1648+40+64+655360);PathAov_0 aovs[4]{};
 GlobalParams_0 globals{};globals.camera_0=&camera;globals.output_0={output,32};globals.pageFeedback_0={feedback.data(),feedback.size()};globals.pathAovs_0={aovs,4};
 ComputeVaryingInput varying{};varying.endGroupID={1,1,1};realtime_policy(&varying,nullptr,&globals);
 if(std::abs(output[1].x-2)>1e-3||std::abs(output[1].y-3)>1e-3||std::abs(output[1].z-4)>1e-3||output[1].w!=1||output[2].x!=4||output[2].y!=2||output[2].z!=1||output[2].w!=0||output[3].x!=0||output[3].y!=0||output[4].x!=1||output[4].y!=0||output[4].z!=0||output[4].w!=0||output[5].w!=1||std::abs(output[5].x-.2)>1e-6||output[6].x!=0||output[6].y!=0||output[6].z!=0||output[8].x!=0||output[9].x!=1||output[10].w!=1||std::abs(output[10].x-.1)>1e-6||std::abs(output[10].y-.45)>1e-6||std::abs(output[10].z-.4)>1e-6||output[11].x!=1||output[11].y!=0||output[12].x!=0||output[13].x!=0||output[14].x!=1||output[14].y!=1||output[15].x!=0||std::abs(output[17].x-(.2+.6/9))>1e-6||std::abs(output[17].y-(.3+.6/9))>1e-6||std::abs(output[17].z-(.4+.6/9))>1e-6||output[17].w!=9||output[18].x!=0||output[18].y!=5||output[18].z!=2||output[18].w!=3||output[19].x!=8||output[19].y!=100||output[19].z!=4||output[19].w!=1||output[20].x!=1||output[20].y!=0){for(auto& v:output)std::printf("%g %g %g %g\n",v.x,v.y,v.z,v.w);return 1;}
 std::puts("Production realtime policy: exact suffix normalization, SH constant radiance, maturity, plane/epoch/TTL rejection, adaptive intervals, neutral RG8 normal admission, textured diffuse remodulation, dark-channel fallback, rough specular protection, jittered neighbor reprojection, noisy mean confidence and TTL, temporal color averaging without invented samples, gradual-light two-frame cap, protected materials, visibility replay isolation and FULL bypass passed");
}
