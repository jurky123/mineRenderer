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
 constexpr unsigned palette=220, environment=palette+5*65536*4, emitter=environment+48;
 std::vector<unsigned> assets((emitter+64)/4);
 auto put=[&](unsigned offset,float value){std::memcpy(reinterpret_cast<char*>(assets.data())+offset,&value,4);};
 for(unsigned slot=0;slot<4;slot++){assets[slot*4]=slot==3?256:1;assets[slot*4+1]=slot==3?1280:1;assets[slot*4+2]=slot==3?palette:208+slot*4;}
 assets[208/4]=0xffffffff;assets[216/4]=128|(128<<8);assets[palette/4]=10<<8;assets[palette/4+65536]=(128<<8)|(20u<<24);
 assets[12/4]=environment;assets[28/4]=environment+16;assets[44/4]=environment+32;assets[84/4]=assets[88/4]=1;
 put(108,.00006793f);put(128,0);put(132,0);put(136,2);put(140,1);put(144,20);put(148,12.4f);put(152,4.8f);
 Camera_0 camera{};camera.width_0=camera.height_0=1;camera.origin_0={0,0,.5f,1};
 Vector<float,4> result{};GlobalParams_0 globals{};globals.geometry_0={reinterpret_cast<unsigned*>(geometry.data()),geometry.size()*40};globals.assets_0={assets.data(),assets.size()*4};globals.output_0={&result,1};globals.camera_0=&camera;
 ComputeVaryingInput varying{};varying.endGroupID={1,1,1};
 float sourceDistance=2;
 auto run=[&](unsigned triangles,bool lit,const char* label){camera.padding_0=triangles;camera.origin_0.w=1;visibility_transport(&varying,nullptr,&globals);auto legacy=result;camera.origin_0.w=25;visibility_transport(&varying,nullptr,&globals);if(std::memcmp(&legacy,&result,sizeof(result))!=0){std::fprintf(stderr,"%s: optimized visibility differs from legacy\n",label);return false;}float expected=lit?20.f/(sourceDistance*sourceDistance*3.14159265358979323846f):0; if(!std::isfinite(result.x)||std::abs(result.x-expected)>.0001f){std::fprintf(stderr,"%s: RGB=%g/%g/%g expected red=%g\n",label,result.x,result.y,result.z,expected);return false;}return true;};
 if(!run(1,true,"clear held")||!run(2,false,"occluded held"))return 1;
 put(140,0);if(!run(1,false,"disabled held"))return 1;
 assets[124/4]=1;assets[156/4]=emitter;put(emitter+8,2);put(emitter+12,1);put(emitter+16,20);put(emitter+20,12.4f);put(emitter+24,4.8f);put(emitter+44,1);assets[(emitter+56)/4]=1;
 if(!run(1,true,"clear placed flame")||!run(2,false,"occluded placed flame"))return 1;
 assets[124/4]=0;assets[192/4]=1;assets[196/4]=emitter;
 if(!run(1,true,"independent placed flame")||!run(2,false,"independent placed flame occlusion"))return 1;
 // Source and own stem share block z=1, while the other stem lies in block z=0.
 put(emitter+8,1.5f);sourceDistance=1.5f;
 for(unsigned i=3;i<6;i++){geometry[i].p[2]=.8f;geometry[i].flags=32;}
 if(!run(2,false,"other flame body must occlude"))return 1;
 for(unsigned i=3;i<6;i++)geometry[i].p[2]=1.2f;
 if(!run(2,true,"own flame body does not self-shadow"))return 1;
 for(unsigned i=3;i<6;i++)geometry[i].flags=0;
 if(!run(2,false,"solid wall in emitter cell must still occlude"))return 1;
 camera.frame_0=1;camera.padding_0=2;
 auto compare=[&](const char* label){camera.origin_0.w=1;visibility_transport(&varying,nullptr,&globals);auto legacy=result;camera.origin_0.w=25;visibility_transport(&varying,nullptr,&globals);if(std::memcmp(&legacy,&result,sizeof(result))!=0){std::fprintf(stderr,"%s visibility mismatch\n",label);return false;}return true;};
 for(unsigned i=3;i<6;i++){geometry[i].p[2]=1;geometry[i].flags=4|8;}geometry[3].p[0]=4;geometry[4].p[0]=-4;
 assets[palette/4+65536]=4|(128<<8);assets[palette/4+3*65536]=255u<<24;
 if(!compare("thin glass")||result.x<=0)return 1;
 assets[palette/4+65536]=3|(128<<8);assets[palette/4+2*65536]=10|(20<<8)|(30<<16);
 if(!compare("solid absorbing glass")||result.x<=0)return 1;
 assets[palette/4+65536]=9|(113<<8);if(!compare("water interface")||result.x<=0)return 1;
 assets[palette/4+65536]=7|(128<<8);if(!compare("foliage transmission")||result.x<=0)return 1;
 assets[palette/4+65536]=(128<<8);for(unsigned i=3;i<6;i++)geometry[i].flags=1;assets[208/4]=0;
 if(!compare("transparent cutout"))return 1;assets[208/4]=0xffffffff;if(!compare("opaque cutout"))return 1;
 camera.frame_0=2;camera.padding_0=0;camera.origin_0.w=1;visibility_transport(&varying,nullptr,&globals);auto legacy=result;camera.origin_0.w=9;visibility_transport(&varying,nullptr,&globals);if(std::memcmp(&legacy,&result,sizeof(result))!=0)return 1;
 std::puts("Production Slang surface transport: held and placed flame irradiance, opaque/cutout/glass/water/foliage/extinction visibility legacy-fast parity and flame self-shell passed");
}
