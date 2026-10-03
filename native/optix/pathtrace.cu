// Raster-primary diffuse path tracing. CUDA traverses a bounded voxel proxy;
// OptiX is used by the host for denoising, not for this prototype's traversal.
struct V { float x,y,z; };
__device__ V v(float x,float y,float z){return {x,y,z};}
__device__ V add(V a,V b){return v(a.x+b.x,a.y+b.y,a.z+b.z);}
__device__ V mul(V a,float b){return v(a.x*b,a.y*b,a.z*b);}
__device__ V product(V a,V b){return v(a.x*b.x,a.y*b.y,a.z*b.z);}
__device__ float dot(V a,V b){return a.x*b.x+a.y*b.y+a.z*b.z;}
__device__ V cross(V a,V b){return v(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
__device__ V norm(V a){return mul(a,rsqrtf(fmaxf(dot(a,a),1.e-12f)));}
__device__ float rnd(unsigned &s){s^=s<<13;s^=s>>17;s^=s<<5;return (s>>8)*0x1p-24f;}
__device__ V cosine(V n,unsigned &s){float r=sqrtf(rnd(s)),a=6.2831853f*rnd(s);V t=norm(cross(fabsf(n.y)<.9f?v(0,1,0):v(1,0,0),n));return norm(add(add(mul(t,r*cosf(a)),mul(cross(n,t),r*sinf(a))),mul(n,sqrtf(fmaxf(0,1-r*r)))));}
// 0=escape, -1=unknown, 1=hit. Unknown cells are not a source of sky light.
__device__ int trace(const unsigned* grid,V o,V d,V &p,V &n,unsigned &material){
 int x=(int)floorf(o.x),y=(int)floorf(o.y),z=(int)floorf(o.z);
 int sx=d.x>=0?1:-1,sy=d.y>=0?1:-1,sz=d.z>=0?1:-1;
 float ax=1/fmaxf(fabsf(d.x),1.e-8f),ay=1/fmaxf(fabsf(d.y),1.e-8f),az=1/fmaxf(fabsf(d.z),1.e-8f);
 float tx=(d.x>=0?x+1-o.x:o.x-x)*ax,ty=(d.y>=0?y+1-o.y:o.y-y)*ay,tz=(d.z>=0?z+1-o.z:o.z-z)*az,t=0;
 if(fabsf(d.x)<1.e-8f)tx=1.e30f;
 if(fabsf(d.y)<1.e-8f)ty=1.e30f;
 if(fabsf(d.z)<1.e-8f)tz=1.e30f;
 n=mul(d,-1);
 for(int i=0;i<244;i++){
  if(x<0||y<0||z<0||x>=80||y>=80||z>=80)return 0;
  unsigned m=grid[(y*80+z)*80+x],a=m>>24;
  if(a==255)return -1;
  if(a){p=add(o,mul(d,t));material=m;return 1;}
  if(tx<=ty&&tx<=tz){t=tx;tx+=ax;x+=sx;n=v(-sx,0,0);}else if(ty<=tz){t=ty;ty+=ay;y+=sy;n=v(0,-sy,0);}else{t=tz;tz+=az;z+=sz;n=v(0,0,-sz);}
 }
 return -1;
}
__device__ V rgb(unsigned m){return v((m&255)/255.f,((m>>8)&255)/255.f,((m>>16)&255)/255.f);}
extern "C" __global__ void paths(const float* positions,const float* normals,const float* albedos,const unsigned* grid,const float* settings,float* sum,float* radiance,float* guide,int count,int sample,int seed){
 int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;int j=i*4;
 V n=norm(v(normals[j],normals[j+1],normals[j+2]));
 // OptiX HDR denoiser normal guides are in camera space.
 guide[j]=settings[12]*n.x+settings[13]*n.y+settings[14]*n.z;
 guide[j+1]=settings[15]*n.x+settings[16]*n.y+settings[17]*n.z;
 guide[j+2]=settings[18]*n.x+settings[19]*n.y+settings[20]*n.z;guide[j+3]=1;
 V result=v(0,0,0);unsigned s=(i+1)*747796405u+(seed+1)*2891336453u;
 if(positions[j+3]>.5f){
  V p=add(v(positions[j],positions[j+1],positions[j+2]),v(settings[0],settings[1],settings[2]));
  V throughput=v(albedos[j],albedos[j+1],albedos[j+2]);
  for(int bounce=0;bounce<3;bounce++){
   V d=cosine(n,s),hit,hn;unsigned m;
   int status=trace(grid,add(p,mul(n,.025f)),d,hit,hn,m);
   // The raster sky term already lights the primary surface; only secondary sky is added.
   if(status==0){if(bounce>0)result=add(result,product(throughput,v(settings[9],settings[10],settings[11])));break;}
   if(status<0)break;
   V color=rgb(m);float emission=((m>>24)-1)/15.f;
   if(bounce>0)result=add(result,mul(product(throughput,color),emission*5.f));
   V sun=v(settings[3],settings[4],settings[5]);float facing=fmaxf(0,dot(hn,sun));
   if(facing>0){V unused,un;unsigned um;int visible=trace(grid,add(hit,mul(hn,.025f)),sun,unused,un,um);if(visible==0)result=add(result,mul(product(product(throughput,color),v(settings[6],settings[7],settings[8])),facing));}
   throughput=product(throughput,color);p=hit;n=hn;
  }
 }
 // Bounded radiance protects accumulation from pathological emissive proxies.
 sum[j]+=fminf(result.x,20.f);sum[j+1]+=fminf(result.y,20.f);sum[j+2]+=fminf(result.z,20.f);
 radiance[j]=sum[j]/(sample+1);radiance[j+1]=sum[j+1]/(sample+1);radiance[j+2]=sum[j+2]/(sample+1);radiance[j+3]=1;
}

// Persistent per-surface EMA. A batch is an observation, never the display history itself.
extern "C" __global__ void temporal(const float* current,const float* positions,const float* normals,const float* oldPosition,const float* oldNormal,const float* oldRadiance,const float* oldMoments,float* nextRadiance,float* nextMoments,const float* settings,int width,int height){
 int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=width*height)return;int j=i*4;
 V c=v(current[j],current[j+1],current[j+2]);float l=dot(c,v(.2126f,.7152f,.0722f));
 float count=0,oldMean=0,oldSecond=0;V history=c;
 if(settings[40]>.5f && positions[j+3]>.5f){
  V p=add(v(positions[j],positions[j+1],positions[j+2]),v(settings[37],settings[38],settings[39]));
  const float* m=settings+21;float w=m[3]*p.x+m[7]*p.y+m[11]*p.z+m[15];
  float u=(m[0]*p.x+m[4]*p.y+m[8]*p.z+m[12])/fmaxf(w,1.e-8f)*.5f+.5f;
  float vv=(m[1]*p.x+m[5]*p.y+m[9]*p.z+m[13])/fmaxf(w,1.e-8f)*.5f+.5f;
  if(w>0 && u>=0 && vv>=0 && u<1 && vv<1){
   int q=((int)(vv*height)*width+(int)(u*width))*4;
   V op=v(oldPosition[q],oldPosition[q+1],oldPosition[q+2]),n=v(normals[j],normals[j+1],normals[j+2]),on=v(oldNormal[q],oldNormal[q+1],oldNormal[q+2]);
   V delta=add(op,mul(p,-1));float distance=sqrtf(dot(p,p)),plane=fabsf(dot(n,delta));
   if(oldPosition[q+3]>.5f && dot(n,on)>.95f && plane<.08f+.003f*distance && fabsf(sqrtf(dot(op,op))-distance)<.2f+.015f*distance && dot(delta,delta)<2.25f){
    history=v(oldRadiance[q],oldRadiance[q+1],oldRadiance[q+2]);count=fminf(oldRadiance[q+3],32.f);oldMean=oldMoments[q];oldSecond=oldMoments[q+1];
   }
  }
 }
 // Neighborhood bounds expanded by variance prevent retaining a stale firefly indefinitely.
 if(count>0){
  V low=c,high=c;int x=i%width,y=i/width;
  for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
   int xx=x+dx,yy=y+dy;if(xx<0||yy<0||xx>=width||yy>=height)continue;int q=(yy*width+xx)*4;
   if(positions[q+3]<.5f || dot(v(normals[j],normals[j+1],normals[j+2]),v(normals[q],normals[q+1],normals[q+2]))<.95f)continue;
   low.x=fminf(low.x,current[q]);low.y=fminf(low.y,current[q+1]);low.z=fminf(low.z,current[q+2]);high.x=fmaxf(high.x,current[q]);high.y=fmaxf(high.y,current[q+1]);high.z=fmaxf(high.z,current[q+2]);
  }
  float sigma=sqrtf(fmaxf(oldSecond-oldMean*oldMean,0.f)),margin=.03f+2*sigma;
  history.x=fminf(fmaxf(history.x,low.x-margin),high.x+margin);history.y=fminf(fmaxf(history.y,low.y-margin),high.y+margin);history.z=fminf(fmaxf(history.z,low.z-margin),high.z+margin);
 }
 float alpha=count>0?fmaxf(.125f,1/(count+1)):1;
 V result=add(mul(history,1-alpha),mul(c,alpha));
 nextRadiance[j]=result.x;nextRadiance[j+1]=result.y;nextRadiance[j+2]=result.z;nextRadiance[j+3]=positions[j+3]>.5f?fminf(count+1,32.f):0;
 nextMoments[j]=oldMean*(1-alpha)+l*alpha;nextMoments[j+1]=oldSecond*(1-alpha)+l*l*alpha;nextMoments[j+2]=count+1;nextMoments[j+3]=0;
}
