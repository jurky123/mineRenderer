#include <cmath>
#include <cassert>
#include <vector>
#define __device__
#define __global__
float rsqrtf(float x){return 1/std::sqrt(x);}
struct Dim {int x;};Dim blockIdx{},blockDim{},threadIdx{};
#include "pathtrace.cu"
int main(){
 std::vector<unsigned> grid(80*80*80);V p,n;unsigned m=0;
 grid[(10*80+10)*80+12]=0x010000ff;
 assert(trace(grid.data(),v(10.5,10.5,10.5),v(1,0,0),p,n,m)==1);
 assert(std::abs(p.x-12)<1e-5&&n.x==-1&&m==0x010000ff);
 assert(trace(grid.data(),v(13.5,10.5,10.5),v(-1,0,0),p,n,m)==1&&std::abs(p.x-13)<1e-5&&n.x==1);
 assert(trace(grid.data(),v(10.5,10.5,10.5),v(0,1,0),p,n,m)==0);
 grid[(11*80+10)*80+10]=0xff000000;
 assert(trace(grid.data(),v(10.5,10.5,10.5),v(0,1,0),p,n,m)==-1);
 grid.assign(grid.size(),0);grid[(10*80+11)*80+10]=0x010000ff;
 // Exactly zero ray components must not advance a cell at an integer-plane origin.
 assert(trace(grid.data(),v(10.5,10.5,10),v(1,0,0),p,n,m)==0);
 unsigned seed=1;float mean=0;
 for(int i=0;i<100000;i++){V ray=cosine(v(0,1,0),seed);assert(ray.y>=0&&std::abs(dot(ray,ray)-1)<1e-5);mean+=ray.y;}
 assert(std::abs(mean/100000-2.f/3)<.005);
 // No primary-sky duplication: a ray into an empty world contributes zero.
 grid.assign(grid.size(),0);float pos[]={10.5,10.5,10.5,1},normal[]={0,1,0,1},alb[]={1,1,1,1},settings[21]={},sum[4]={},raw[4]={},guide[4]={};settings[9]=settings[10]=settings[11]=1;blockDim.x=1;
 paths(pos,normal,alb,grid.data(),settings,sum,raw,guide,1,0);assert(raw[0]==0&&raw[1]==0&&raw[2]==0);
 // An emissive enclosing proxy must produce indirect light, with red color bleed.
 for(int y=0;y<80;y++)for(int z=0;z<80;z++)for(int x=0;x<80;x++)if(y==12||y==8||x==12||x==8||z==12||z==8)grid[(y*80+z)*80+x]=0x100000ff;
 for(int i=0;i<4;i++)sum[i]=0;
 paths(pos,normal,alb,grid.data(),settings,sum,raw,guide,1,0);assert(raw[0]>0&&raw[1]==0&&raw[2]==0);
}
