// Execute production surface transport, replacing only hardware intersections with triangles.
#include "visibility_transport.cpp"
#include <vector>
#include <cstring>
#include <cmath>
#include <cstdio>
struct Vertex {float p[3],uv[2],n[3];unsigned tint,flags;};
int main(){
 std::vector<Vertex> geometry;
 for(float z:{0.f,1.f}){geometry.push_back({{-4,-4,z},{0,0},{0,0,1},0xffffffff,0});geometry.push_back({{4,-4,z},{0,0},{0,0,1},0xffffffff,0});geometry.push_back({{0,4,z},{0,0},{0,0,1},0xffffffff,0});}
 constexpr unsigned palette=204, environment=palette+5*65536*4, emitter=environment+48;
 std::vector<unsigned> assets((emitter+64)/4);
 auto put=[&](unsigned offset,float value){std::memcpy(reinterpret_cast<char*>(assets.data())+offset,&value,4);};
 for(unsigned slot=0;slot<4;slot++){assets[slot*4]=slot==3?256:1;assets[slot*4+1]=slot==3?1280:1;assets[slot*4+2]=slot==3?palette:192+slot*4;}
 assets[192/4]=0xffffffff;assets[200/4]=128|(128<<8);assets[palette/4]=10<<8;assets[palette/4+65536]=(128<<8)|(20u<<24);
 assets[12/4]=environment;assets[28/4]=environment+16;assets[44/4]=environment+32;assets[84/4]=assets[88/4]=1;
 put(108,.00006793f);put(128,0);put(132,0);put(136,2);put(140,1);put(144,20);put(148,12.4f);put(152,4.8f);
 Camera_0 camera{};camera.width_0=camera.height_0=1;camera.origin_1={0,0,.5f,1};
 Vector<float,4> result{};GlobalParams_0 globals{};globals.geometry_0={reinterpret_cast<unsigned*>(geometry.data()),geometry.size()*40};globals.assets_0={assets.data(),assets.size()*4};globals.output_0={&result,1};globals.camera_0=&camera;
 ComputeVaryingInput varying{};varying.endGroupID={1,1,1};
 auto run=[&](unsigned triangles,bool lit,const char* label){camera.padding_1=triangles;visibility_transport(&varying,nullptr,&globals);float expected=lit?20.f/(4*3.14159265358979323846f):0; if(!std::isfinite(result.x)||std::abs(result.x-expected)>.0001f){std::fprintf(stderr,"%s: RGB=%g/%g/%g expected red=%g\n",label,result.x,result.y,result.z,expected);return false;}return true;};
 if(!run(1,true,"clear held")||!run(2,false,"occluded held"))return 1;
 put(140,0);if(!run(1,false,"disabled held"))return 1;
 assets[124/4]=1;assets[156/4]=emitter;put(emitter+8,2);put(emitter+12,1);put(emitter+16,20);put(emitter+20,12.4f);put(emitter+24,4.8f);put(emitter+44,1);assets[(emitter+56)/4]=1;
 if(!run(1,true,"clear placed flame")||!run(2,false,"occluded placed flame"))return 1;
 std::puts("Production Slang surface transport: held and placed flame irradiance, opaque occlusion and disabled held light passed");
}
