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
 paths(pos,normal,alb,grid.data(),settings,sum,raw,guide,1,0,0);assert(raw[0]==0&&raw[1]==0&&raw[2]==0);
 grid.assign(grid.size(),0);grid[(11*80+10)*80+10]=0x100000ff;
 for(int i=0;i<4;i++)sum[i]=0;
 paths(pos,normal,alb,grid.data(),settings,sum,raw,guide,1,0,0);assert(raw[0]==0&&raw[1]==0&&raw[2]==0);
 // First-hit emissive lighting belongs to raster; later emitter hits are indirect.
 for(int y=0;y<80;y++)for(int z=0;z<80;z++)for(int x=0;x<80;x++)if(y==12||y==8||x==12||x==8||z==12||z==8)grid[(y*80+z)*80+x]=0x100000ff;
 for(int i=0;i<4;i++)sum[i]=0;
 paths(pos,normal,alb,grid.data(),settings,sum,raw,guide,1,0,0);assert(raw[0]>0&&raw[1]==0&&raw[2]==0);
 // Per-pixel history validates surface guides and blends instead of replacing noisy observations.
 float settingsT[41]={};settingsT[21]=settingsT[26]=settingsT[31]=settingsT[36]=1;settingsT[40]=1;
 float pNow[]={0,0,0,1},nNow[]={0,1,0,1},current[]={.5,.5,.5,1},history[]={.48,.48,.48,8},moments[]={.48,.2304,8,0},next[4]={},nextMom[4]={};
 temporal(current,pNow,nNow,pNow,nNow,history,moments,next,nextMom,settingsT,1,1);
 assert(next[0]>.48&&next[0]<.5&&next[3]==9);
 float differentNormal[]={0,-1,0,1};temporal(current,pNow,nNow,pNow,differentNormal,history,moments,next,nextMom,settingsT,1,1);assert(next[0]==.5f&&next[3]==1);
 float moved[]={3,0,0,1};temporal(current,pNow,nNow,moved,nNow,history,moments,next,nextMom,settingsT,1,1);assert(next[0]==.5f&&next[3]==1);
 float darkCurrent[]={0,0,0,1};
 temporal(darkCurrent,pNow,nNow,pNow,nNow,history,moments,next,nextMom,settingsT,1,1);assert(next[0]>.4f); // One noisy dark batch must not clamp stable GI to black.
 // A grazing same-plane offset changes radial distance but remains the same surface.
 float grazingNow[]={1,0,0,1},grazingOld[]={1.5,0,0,1};settingsT[21]=.25f;
 temporal(current,grazingNow,nNow,grazingOld,nNow,history,moments,next,nextMom,settingsT,1,1);assert(next[3]==9);
 // Projected footprint straddles an invalid texel and a valid coplanar one.
 float twoPos[]={0,0,0,1,0,0,0,1},twoNorm[]={0,1,0,1,0,1,0,1},twoCurrent[]={.5,.5,.5,1,.5,.5,.5,1};
 float twoOldPos[]={0,0,0,0,0,0,0,1},twoHistory[]={0,0,0,0,.48,.48,.48,8},twoMom[]={0,0,0,0,.48,.2304,8,0},twoNext[8]={},twoNextMom[8]={};
 temporal(twoCurrent,twoPos,twoNorm,twoOldPos,twoNorm,twoHistory,twoMom,twoNext,twoNextMom,settingsT,2,1);assert(twoNext[3]==9&&twoNext[0]<.5f);
 settingsT[40]=0;temporal(current,pNow,nNow,pNow,nNow,history,moments,next,nextMom,settingsT,1,1);assert(next[0]==.5f&&next[3]==1);
}
