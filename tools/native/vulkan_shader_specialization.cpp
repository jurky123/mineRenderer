#include "shader_specialization.cpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
int main(int argc,char** argv){
 if(argc!=4)return 2;Camera_0 camera{};static_assert(sizeof(Camera_0)==96);unsigned one=1;for(int offset:{80,84,92})std::memcpy(reinterpret_cast<char*>(&camera)+offset,&one,4);
 Vector<float,4> output[3]{};Vector<unsigned,4> feedback[1648]{};GlobalParams_0 globals{};
 globals.camera_0=&camera;globals.output_0={output,3};globals.pageFeedback_0={feedback,1648};
 ComputeVaryingInput varying{};varying.endGroupID={1,1,1};shader_specialization(&varying,nullptr,&globals);
 if(output[0].x!=std::atoi(argv[1])||output[0].y!=0||output[1].y!=std::atoi(argv[3])||output[2].x!=0||output[2].y!=1||output[2].z!=2||output[2].w!=3)return 1;
 float flags=128;std::memcpy(reinterpret_cast<char*>(&camera)+76,&flags,4);shader_specialization(&varying,nullptr,&globals);
 if(output[0].x!=std::atoi(argv[2]))return 1;
 std::puts("Production shader specialization: direct compile-time selection, FULL policy bypass and unchanged path passed");
}
