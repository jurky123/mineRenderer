#include "direct_lighting.cpp"
#include <vector>
#include <fstream>
#include <cstring>
#include <cmath>
#include <cstdio>
int main(int argc,char**argv){
 if(argc!=2)return 1;constexpr unsigned N=200000;
 for(const char* mode:{"points","mixed","adapted","empty"}){
  std::ifstream file(std::string(argv[1])+"/"+mode+".light-runtime.bin",std::ios::binary);std::vector<char> runtime((std::istreambuf_iterator<char>(file)),{});if(runtime.empty()||runtime.size()>2*1024*1024)return 1;
  // Build exact assets header, a constant 2x2 environment and the real Material 3 palette.
  unsigned base=256,env=base+runtime.size(),palette=env+256;std::vector<unsigned> assets((palette+5*65536*4)/4);
  auto put=[&](unsigned offset,float value){std::memcpy(reinterpret_cast<char*>(assets.data())+offset,&value,4);};
  std::memcpy(reinterpret_cast<char*>(assets.data())+base,runtime.data(),runtime.size());assets[208/4]=base;assets[216/4]=8;assets[220/4]=4;assets[224/4]=1;
  assets[84/4]=assets[88/4]=2;assets[12/4]=env;assets[28/4]=env+64;assets[44/4]=env+128;
  assets[0]=assets[1]=1;assets[2]=env+192;assets[4]=assets[5]=1;assets[6]=env+208;assets[8]=assets[9]=1;assets[10]=env+224;assets[14]=palette;
  assets[(env+192)/4]=0xffffffff;assets[(env+208)/4]=0;assets[(env+224)/4]=0xff8080ff;
  const float envColor[3]={.25f,.5f,.75f};bool empty=std::strcmp(mode,"empty")==0,mixed=std::strcmp(mode,"points")!=0&&!empty;
  for(unsigned i=0;i<4;i++)for(unsigned c=0;c<3;c++)put(env+i*16+c*4,empty?0:envColor[c]);
  // Packed Material 3 emission fields: use production decoder instead of a test-only radiance sampler.
  // White textured triangles, explicit native emission level and a diffuse palette.
  assets[palette/4]=31|(10u<<8);assets[palette/4+65536]=0;assets[palette/4+2*65536]=0x070503ffu;
  // The fixture below reads emissive RGB from exact production terrainSurface; reference is measured once deterministically.
  std::vector<unsigned> geometry(34*30);auto vertex=[&](unsigned tri,unsigned v,float x,float y,float z){unsigned o=tri*30+v*10;float vals[8]={x,y,z,.5f,.5f,0,0,1};for(unsigned j=0;j<8;j++)std::memcpy(&geometry[o+j],&vals[j],4);geometry[o+8]=0xffffffff;geometry[o+9]=15u<<16;};
  vertex(32,0,-1,-1,3);vertex(32,1,1,-1,3);vertex(32,2,-1,1,3);vertex(33,0,1,-1,3);vertex(33,1,1,1,3);vertex(33,2,-1,1,3);
  std::vector<float4> normals(1);std::vector<float4> result(2*N);Camera_0 camera{};camera.width_0=N;camera.height_0=1;
  GlobalParams_0 globals{};globals.camera_0=&camera;globals.assets_0={assets.data(),assets.size()*4};globals.geometry_0={geometry.data(),geometry.size()*4};globals.normals_0={normals.data(),normals.size()};globals.result_0={result.data(),result.size()};
  float emission[3]={8,8,8}; // White native emission level 15 decodes to RGB 8.
  double expected[3]={0,0,0};if(!empty){for(unsigned c=0;c<3;c++)expected[c]=envColor[c];for(unsigned i=0;i<16;i++)if((i+1)%3!=0){double color[3]={double((i+1)*7),double((16-i)*11),double(i%2?17:3)};for(unsigned c=0;c<3;c++)expected[c]+=color[c]/((i+1.)*(i+1.))* (1/(4*3.141592653589793));}
   double solidAngle=4*std::atan(1/(3*std::sqrt(11.)));if(mixed)for(unsigned c=0;c<3;c++)expected[c]+=emission[c]*solidAngle*.5/(4*3.141592653589793);}
  double projected=0;constexpr int Q=256;for(int y=0;y<Q;y++)for(int x=0;x<Q;x++){double dx=-1+(x+.5)*2/Q,dy=-1+(y+.5)*2/Q,r2=dx*dx+dy*dy+9;projected+=9/(r2*r2)*4/(Q*Q);}
  for(bool surface:{false,true})for(unsigned bounce:{0u,1u,3u,5u}){camera.height_0=surface?2:1;camera.padding_0=bounce;
   double reference[3];for(unsigned c=0;c<3;c++){reference[c]=expected[c];if(surface){reference[c]=empty?0:envColor[c]*(mixed?1-projected/3.141592653589793:1);if(!empty)for(unsigned i=0;i<16;i++)if((i+1)%3!=0){double rgb[3]={double((i+1)*7),double((16-i)*11),double(i%2?17:3)};reference[c]+=rgb[c]/((i+1.)*(i+1.)*3.141592653589793);}if(mixed)reference[c]+=emission[c]*projected*.5/3.141592653589793;}}
ComputeVaryingInput varying{};varying.endGroupID={N,1,1};direct_lighting(&varying,nullptr,&globals);double measured[3]={0,0,0};bool strata[4096]{};
   for(unsigned i=0;i<N;i++){auto r=result[2*i];if(!std::isfinite(r.x)||!std::isfinite(r.y)||!std::isfinite(r.z)||result[2*i+1].x>1e-4||result[2*i+1].y<0||result[2*i+1].y>=1){std::fprintf(stderr,"RIS %s depth %u sample %u finite/PDF failure: %g\n",mode,bounce,i,result[2*i+1].x);return 1;}if(i<4096){unsigned bin=unsigned(result[2*i+1].y*4096);if(strata[bin]){std::fprintf(stderr,"Owen candidate stratification failed\n");return 1;}strata[bin]=true;}measured[0]+=r.x/N;measured[1]+=r.y/N;measured[2]+=r.z/N;}
   for(unsigned c=0;c<3;c++)if(std::abs(measured[c]-reference[c])>.02*std::max(.001,reference[c])){std::fprintf(stderr,"Direct RIS %s depth %u channel %u: %g expected %g\n",mode,bounce,c,measured[c],reference[c]);return 1;}
  }
 }
 std::puts("Production hierarchical direct RIS: colored/occluded points + area + environment, adaptive proposals, depth roulette, surface NEE/BSDF MIS energy, bounded Owen sequence and matched hit PDF passed (200000 samples per case)");
}
