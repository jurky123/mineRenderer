// Execute real Slang ByteAddressBuffer decoding against the canonical native Material 3 decoder.
#include "terrain_material_parity.cpp"
#include "../../native/rt/bsdf.h"
#include "../../native/rt/surface.h"
float3 v(float x,float y,float z){return {x,y,z};}
struct {unsigned* lut;unsigned options;float time,waveStrength,cloudWind,rain,rainRipples;float3 camera;} params;
#define __forceinline__ inline
#define __device__
#include "../../native/rt/material.cuh"
float3 add(float3 a,float3 b){return v(a.x+b.x,a.y+b.y,a.z+b.z);}
float3 mul(float3 a,float b){return v(a.x*b,a.y*b,a.z*b);}
float dot3(float3 a,float3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
float3 norm(float3 a){return mul(a,1/std::sqrt(std::max(1e-20f,dot3(a,a))));}
#include "water_fixture_native.h"
#include <vector>
#include <cstdio>
#include <cstring>
#include <cstdlib>
struct Vertex {float p[3],uv[2],n[3];unsigned tint,flags;};
static_assert(sizeof(Vertex)==40,"Canonical terrain stride changed");
float linear(unsigned x){float s=x/255.f;return s<=.04045f?s/12.92f:std::pow((s+.055f)/1.055f,2.4f);}
rt::Vec rgb(unsigned p){return rt::V(linear(p&255),linear((p>>8)&255),linear((p>>16)&255));}
int main(){
 constexpr unsigned cases=32,w=cases*2;
 std::vector<Vertex> geometry(cases*3);
 std::vector<unsigned> assets(24+w*3+65536*5),lut(65536*5),tex(cases),ids(cases),normal(cases);
 for(unsigned slot=0;slot<4;slot++){assets[slot*4]=slot==3?256:w;assets[slot*4+1]=slot==3?1280:1;assets[slot*4+2]=(24+(slot==3?w*3:w*slot))*4;}
 for(unsigned i=0;i<cases;i++){
  unsigned index=65504+i,type=i%10;
  lut[index]=(i==31?80:((i*7)&255))|((i%4==0?231:50)<<8)|(19<<16)|(255u<<24);
  lut[index+65536]=type|(128<<8)|(unsigned(type==5||type==6?150:0)<<16)|(20u<<24);
  lut[index+65536*2]=32|(11<<8)|(2<<16)|(150u<<24);
  lut[index+65536*3]=2|(3<<8)|(4<<16)|(220u<<24);
  lut[index+65536*4]=128|(80<<8);
  unsigned alpha=i%3==0?64:255;
  tex[i]=100|(170<<8)|(230<<16)|(alpha<<24);
  ids[i]=index|(100<<16)|((unsigned(type==3||type==4?1:type==9?2:0)|(i%2==0?4:0))<<24);
  normal[i]=(i%2==0?140u:128u)|(128<<8)|(255u<<24);
  for(unsigned slot=0;slot<3;slot++){unsigned value=slot==0?tex[i]:slot==1?ids[i]:normal[i];assets[24+slot*w+i*2]=value;assets[24+slot*w+i*2+1]=value;}
  float u=(i*2+.5f)/w;
  unsigned flags=1|(i%7==0?8:0)|(i==31?16:0)|(i==29?32:0)|(unsigned(i==31?0:i%16)<<16);
  unsigned tint=220|(180<<8)|(150<<16)|(unsigned(i%5==0?100:255)<<24);
  geometry[i*3]={{float(i*2),0,0},{u,.25f},{0,0,1},tint,flags};
  geometry[i*3+1]={{float(i*2+1),0,0},{u+.001f,.25f},{0,0,1},tint,flags};
  geometry[i*3+2]={{float(i*2),1,0},{u,.75f},{0,0,1},tint,flags};
 }
 std::memcpy(assets.data()+24+w*3,lut.data(),lut.size()*4);
 params={lut.data(),256,12.5f,.09f,3.4f,.7f,1,v(0,65,0)};
 std::vector<Vector<float,4>> actual(cases*12);
 GlobalParams_0 globals{};globals.geometry_0.data=reinterpret_cast<const uint32_t*>(geometry.data());globals.geometry_0.sizeInBytes=geometry.size()*40;
 globals.assets_0.data=assets.data();globals.assets_0.sizeInBytes=assets.size()*4;globals.result_0.data=actual.data();globals.result_0.count=actual.size();
 ComputeVaryingInput varying{};varying.endGroupID={cases,1,1};terrain_material_parity(&varying,nullptr,&globals);
 float largest=0;
 for(unsigned i=0;i<cases;i++){
  const auto& a=geometry[i*3];auto color=rgb(tex[i])*rgb(a.tint);auto m=decodeMaterial(ids[i],lut[ids[i]&65535],v(color.x,color.y,color.z),a.flags);
  m.emission=color*((ids[i]>>24&4)?(ids[i]>>16&255)/254.f:(a.flags>>16)/15.f)*8.f;
  if((a.flags&32)&&(ids[i]>>24&4)==0)m.emission=rt::V(0,0,0);
  if((a.flags&8)&&rt::dielectric(m))m.type=rt::THIN_DIELECTRIC;
  m.mediumId=m.type==rt::WATER?0xfffffffeu:1+(i<<16)+(m.type<<8)+(unsigned)std::round(m.ior*255/3);
  float nx=(normal[i]&255)/127.5f-1,ny=(normal[i]>>8&255)/127.5f-1;auto n=rt::normalize(rt::V(nx,ny,std::sqrt(std::max(0.f,1-nx*nx-ny*ny))));
  bool visible=(ids[i]>>24&3)||rt::cutoutVisible(tex[i]>>24,a.tint>>24,a.flags);
  auto water=waterNormal(v(0,1,0),v(i*.03125f,64,-.125f*i));
  float expected[44]={m.baseColor.x,m.baseColor.y,m.baseColor.z,float(m.type),m.microfacetAlpha,m.alphaV,m.f0,m.ior,m.coatWeight,m.coatAlpha,m.coatIOR,m.transmission,m.sigmaA.x,m.sigmaA.y,m.sigmaA.z,m.phaseG,m.sigmaS.x,m.sigmaS.y,m.sigmaS.z,float(m.materialId),n.x,n.y,n.z,float(visible),m.emission.x,m.emission.y,m.emission.z,float(m.mediumId),a.p[0],a.p[1],a.p[2],float(a.flags),a.uv[0]+.00025f,.5f,0,0,m.eta.x,m.eta.y,m.eta.z,float(m.conductorId),water.x,water.y,water.z,0};
  auto extra=actual[i*12+11];if(extra.y>1e-6||extra.z>1e-6||extra.w>1e-6||(i==31&&extra.x!=1)){std::fprintf(stderr,"Terrain emission/deferred frame mismatch case=%u: %g %g %g %g\n",i,extra.x,extra.y,extra.z,extra.w);return 2;}
  for(unsigned j=0;j<44;j++){float value=reinterpret_cast<const float*>(&actual[i*12])[j];float error=std::abs(value-expected[j])/std::max(1.f,std::abs(expected[j]));largest=std::max(largest,error);if(!std::isfinite(value)||error>.001f){std::fprintf(stderr,"Terrain material mismatch case=%u component=%u actual=%g expected=%g\n",i,j,value,expected[j]);return 1;}}
 }
 std::printf("Terrain Slang/native binding parity + emission-only parity + deferred frame completion: %u cases, %u components, max normalized error=%g\n",cases,cases*44,largest);
}
