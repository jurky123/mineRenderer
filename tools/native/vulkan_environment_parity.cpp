// Execute real Slang environment sampling against canonical native/rt/environment.h.
#include "environment_parity.cpp"
#include "../../native/rt/bsdf.h"
#include "../../native/rt/environment.h"
#include <vector>
#include <cstdio>
#include <cstring>
#include <cstdlib>
static void check(bool ok,const char* message){if(!ok){std::fprintf(stderr,"Environment parity: %s\n",message);std::exit(1);}}
static float luminance(rt::Vec v){return v.x*.2126f+v.y*.7152f+v.z*.0722f;}
int main(){
 constexpr unsigned W=256,H=128,N=100000,header=192,mapOffset=header,cellsOffset=mapOffset+W*H*16,rowsOffset=cellsOffset+W*H*16;
 float largest=0;
 for(unsigned mode=0;mode<4;mode++){
  std::vector<unsigned> assets((rowsOffset+H*16)/4);
  auto put=[&](unsigned offset,float f){std::memcpy(reinterpret_cast<char*>(assets.data())+offset,&f,4);};
  assets[12/4]=mapOffset;assets[28/4]=cellsOffset;assets[44/4]=rowsOffset;assets[84/4]=W;assets[88/4]=H;
  auto sun=rt::normalize(rt::V(.4,.8,.25));put(96,sun.x);put(100,sun.y);put(104,sun.z);put(108,6.793e-5f);
  rt::Vec sunColor=mode==1?rt::V(2,1,.5):rt::V(0,0,0),pointColor=(mode==1||mode==3)?rt::V(.4,.6,.8):rt::V(0,0,0);
  put(112,sunColor.x);put(116,sunColor.y);put(120,sunColor.z);
  put(128,3);put(132,4);put(136,5);put(140,(mode==1||mode==3)?1:0);put(144,pointColor.x);put(148,pointColor.y);put(152,pointColor.z);
  assets[92/4]=mode==1?1:0;put(160,.16f);put(164,.06f);put(168,.035f);put(172,1.333f);put(176,.018f);put(180,.035f);put(184,.045f);put(188,.7f);
  std::vector<float> cdf(W*H+H);std::vector<float> mass(W*H);float total=0;
  for(unsigned y=0;y<H;y++){
   float row=0,omega=rt::environmentSolidAngle(y);
   for(unsigned x=0;x<W;x++){
    float brightness=mode==3?1e6f:mode==2?0:mode==1?(x<16&&y<8?20:1):1;
    unsigned offset=mapOffset+(y*W+x)*16;for(unsigned j=0;j<3;j++)put(offset+j*4,brightness);put(offset+12,1);
    mass[y*W+x]=brightness*omega;row+=mass[y*W+x];cdf[y*W+x]=row;
   }
   total+=row;cdf[W*H+y]=total;
   for(unsigned x=0;x<W;x++){unsigned offset=cellsOffset+(y*W+x)*16;put(offset,cdf[y*W+x]);put(offset+4,mass[y*W+x]);put(offset+8,row);put(offset+12,omega);}
   put(rowsOffset+y*16,total);put(rowsOffset+y*16+4,row);
  }
  for(unsigned y=0;y<H;y++)put(rowsOffset+y*16+8,total);
  std::vector<Vector<float,4>> actual(N*12);GlobalParams_0 globals{};
  globals.assets_0.data=assets.data();globals.assets_0.sizeInBytes=assets.size()*4;globals.result_0.data=actual.data();globals.result_0.count=actual.size();
  ComputeVaryingInput varying{};varying.endGroupID={N,1,1};environment_parity(&varying,nullptr,&globals);
  float sunPower=luminance(sunColor),envPower=std::max(.001f,total),pointPower=0;
  float probabilities[]={sunPower/(sunPower+envPower+pointPower),envPower/(sunPower+envPower+pointPower),pointPower/(sunPower+envPower+pointPower)};
  double integral=0;unsigned hot=0,kinds[4]={};double energy[3]={},terminal[3]={};
  for(unsigned i=0;i<N;i++){
   auto* out=reinterpret_cast<float*>(&actual[i*12]);unsigned seed=(i+1)*747796405u;auto direction=rt::sampleEnvironment(cdf.data(),seed);
   float pdf=rt::environmentDensity(cdf.data(),direction),expected[]={direction.x,direction.y,direction.z,pdf};
   for(unsigned j=0;j<4;j++){float error=std::abs(out[j]-expected[j])/std::max(1.f,std::abs(expected[j]));largest=std::max(largest,error);check(error<.001f,"native sample/PDF disagreement");}
   if(i<W*H)integral+=out[4]*out[5];
   hot+=rt::environmentCell(direction)/W<8&&rt::environmentCell(direction)%W<16;
   unsigned kind=(unsigned)out[19];check(kind==0||kind==1||kind==3,"invalid light kind");kinds[kind]++;
   for(unsigned j=0;j<3;j++)check(std::abs(out[16+j]-probabilities[j])<1e-6f,"light power normalization");
   float omega=6.793e-5f;
   if(kind==0){check(rt::dot(rt::V(out[8],out[9],out[10]),sun)>=1-omega/(2*rt::Pi)-2e-7f,"sun sample outside cone");check(std::abs(out[11]-probabilities[0]/omega)<.01f,"sun PDF");}
   if(kind==3){check(std::abs(out[15]-std::sqrt(50.f))<1e-5f,"point light distance");check(std::abs(out[12]-.4f/50)<1e-6f,"point inverse-square intensity");check(std::abs(out[11]-probabilities[2])<1e-6f,"point discrete PDF");}
   float envPdf=probabilities[1]*pdf;auto miss=rt::V(mode==3?1e6f:mode==2?0:mode==1?(rt::environmentCell(direction)/W<8&&rt::environmentCell(direction)%W<16?20:1):1,0,0);
   float expectedMiss=miss.x*rt::powerHeuristic(.2f,envPdf);
   if(probabilities[0]>0&&rt::dot(direction,sun)>1-omega/(2*rt::Pi))expectedMiss+=sunColor.x/omega*rt::powerHeuristic(.2f,probabilities[0]/omega);
   check(std::abs(out[20]-expectedMiss)/std::max(1.f,expectedMiss)<.001f,"miss MIS complementary weight");
   if(mode==1||mode==3){check(out[39]==1,"held light deterministic discrete PDF");check(std::abs(out[40]-.4f/50)<1e-6f&&std::abs(out[43]-std::sqrt(50.f))<1e-5f,"held light independent inverse square");}
   else check(out[39]==0,"disabled held light has no connection");
   if(mode==1||mode==3){check(out[47]==1,"held delta has no continuous MIS competitor");check(std::abs(out[44]-(.6f/rt::Pi)*(4/std::sqrt(50.f))*(.4f/50))<1e-7,"held irradiance survives arbitrary sky power");}
   else check(out[44]==0&&out[45]==0&&out[46]==0,"no stale held contribution after disable");
   for(unsigned j=0;j<48;j++)check(std::isfinite(out[j]),"nonfinite transport component");
   for(unsigned j=0;j<3;j++){energy[j]+=out[24+j]/N;terminal[j]+=out[28+j]/N;}
   check(std::abs(out[32]-(mode==1?1.333f:1))<1e-6f&&out[33]==(mode==1?1:0),"camera water medium initialization");
   check(std::abs(out[34]-(mode==1?.16f:0))<1e-6f&&std::abs(out[35]-(mode==1?.045f:0))<1e-6f,"camera water coefficients");
  }
  check(std::abs(integral-1)<.0001,"solid-angle PDF normalization including poles");
  double hotMass=0;if(mode==1){for(unsigned y=0;y<8;y++)hotMass+=16*20*rt::environmentSolidAngle(y)/total;check(std::abs(hot/(double)N-hotMass)<.006,"importance sampling histogram");}
  check(std::abs(kinds[0]/(double)N-probabilities[0])<.006&&std::abs(kinds[1]/(double)N-probabilities[1])<.006,"light selection histogram");
  if(mode==0)check(std::abs(energy[0]-.6)<.008&&std::abs(energy[1]-.4)<.008&&std::abs(energy[2]-.2)<.008,"paired NEE/BSDF MIS white furnace");
  if(mode==0)check(std::abs(terminal[0]-.6)<.008&&std::abs(terminal[1]-.4)<.008&&std::abs(terminal[2]-.2)<.008,"depth-limit NEE has no competing BSDF technique");
  if(mode==2)check(energy[0]==0&&energy[1]==0&&energy[2]==0,"black map fallback energy");
 }
 std::printf("Environment Slang/native parity: %u samples, normalized PDFs/poles/histograms/black fallback, sun/environment MIS, deterministic held on/off/high-sky/irradiance, terminal-depth furnace and camera water; max normalized error=%g\n",N*4,largest);
}
