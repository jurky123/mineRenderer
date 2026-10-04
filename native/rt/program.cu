// One compile-time signal per translation unit; no runtime raygen mode graph.
#ifndef RT_SIGNAL
#define RT_SIGNAL 0
#endif
#include "device.cuh"
#include "caustic_lookup.cuh"
#include "transport.cuh"
static __device__ float3 visibility(float3 o,float3 d){return mul(lightVisibility(o,d,512),cloudVisibility(o));}
// Probe coordinates are world anchored. Turning the camera never changes tags or admission.
static __forceinline__ __device__ float3 probeLight(float3 p,float3 n){
 for(int cascade=0;cascade<3;cascade++){float spacing=4.f*(1<<cascade);int cx=(int)floorf(p.x/spacing),cy=(int)floorf(p.y/spacing),cz=(int)floorf(p.z/spacing);float3 total=v(0,0,0);float weights=0;
  for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){int gx=cx+x,gy=cy+y,gz=cz+z;int slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));float4* a=params.probes+slot*8;float3 expected=v(gx*spacing,gy*spacing,gz*spacing);
   if(a[0].w<=0||fabsf(a[0].x-expected.x)+fabsf(a[0].y-expected.y)+fabsf(a[0].z-expected.z)>.1f)continue;
   float3 probePosition=add(expected,v(a[6].x,a[6].y,a[6].z));float3 delta=add(p,mul(probePosition,-1));float distance=sqrtf(dot3(delta,delta));float fx=p.x/spacing-cx,fy=p.y/spacing-cy,fz=p.z/spacing-cz;float w=(x?fx:1-fx)*(y?fy:1-fy)*(z?fz:1-fz);float variance=fmaxf(.01f,a[5].y-a[5].x*a[5].x),excess=fmaxf(0,distance-a[5].x-spacing*.2f);float visibility=variance/(variance+excess*excess);w*=fmaxf(.005f,visibility*visibility*visibility);w*=fmaxf(.05f,dot3(n,norm(mul(delta,-1))));
   float3 ir=add(v(a[1].x,a[1].y,a[1].z),add(mul(v(a[2].x,a[2].y,a[2].z),n.x),add(mul(v(a[3].x,a[3].y,a[3].z),n.y),mul(v(a[4].x,a[4].y,a[4].z),n.z))));total=add(total,mul(v(fmaxf(0,ir.x),fmaxf(0,ir.y),fmaxf(0,ir.z)),w));weights+=w;
  }if(weights>0)return mul(total,1/weights);
 }return mul(params.sky,.25f); // explicit environment base, never black while cache fills
}
#if RT_SIGNAL == 0
#define RT_ENTRY __raygen__diffuse
#elif RT_SIGNAL == 5
#define RT_ENTRY __raygen__specular
#elif RT_SIGNAL == 6
#define RT_ENTRY __raygen__transmission
#else
#error Invalid realtime signal
#endif
extern "C" __global__ void RT_ENTRY(){
 unsigned i=params.launchOffset+optixGetLaunchIndex().x,seed=i*9781u+params.frame*6271u+1;
 int count=params.width*params.height;if(i>=count)return;


 float4 pp=params.position[i],nn=params.normal[i],aa=params.albedo[i],mm=params.material[i];
 if(RT_SIGNAL==0){params.surfaceKey[i]=make_float4(-1,-1,0,0);params.sunVisibility[i]=make_float4(1,1,1,0);params.flow[i*2]=params.flow[i*2+1]=0;params.flowTrust[i]=0;params.diffuse[i]=params.specular[i]=params.transmission[i]=make_float4(0,0,0,0);}if(pp.w<.5f)return;float3 p=add(v(pp.x,pp.y,pp.z),params.camera),n=norm(v(nn.x,nn.y,nn.z)),view=norm(v(-pp.x,-pp.y,-pp.z));float3 color=v(aa.x,aa.y,aa.z);
 float3 sunT=v(1,1,1);bool dielectric=false;
 if(RT_SIGNAL==0){float radial=sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z);auto primary=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),radial+.1f);bool coverage=primary.hit&&(primary.transmission>0||fabsf(primary.distance-radial)<.12f+.003f*radial);
  if(primary.hit&&primary.transmission>0&&(params.options&4)){dielectric=true;p=primary.p;n=primary.geometryNormal;color=primary.color;float3 relative=add(p,mul(params.camera,-1));pp=make_float4(relative.x,relative.y,relative.z,1);nn=make_float4(n.x,n.y,n.z,2);params.material[i]=make_float4(primary.bsdf.microfacetAlpha,primary.f0,primary.bsdf.materialId,1);params.position[i]=pp;params.normal[i]=nn;params.albedo[i]=make_float4(color.x,color.y,color.z,1);coverage=true;}
  if(coverage){params.surfaceKey[i]=make_float4(primary.bsdf.materialId,primary.objectId,primary.distance,primary.ior);
   if(!dielectric){n=primary.geometryNormal;nn=make_float4(n.x,n.y,n.z,1);params.normal[i]=nn;params.material[i]=make_float4(primary.bsdf.microfacetAlpha,primary.f0,primary.bsdf.materialId,0);}}
if(coverage){sunT=visibility(spawn(primary,params.sun),params.sun);if((params.options&33)==33&&dot3(causticIrradiance(p,n),v(1,1,1))>0)sunT=v(0,0,0);}params.sunVisibility[i]=make_float4(sunT.x,sunT.y,sunT.z,coverage?1:0);if(!coverage&&params.debug!=13&&params.debug!=7&&params.debug!=8)return;
 }
 float3 oldRelative=add(p,mul(params.previousCamera,-1));auto mat=params.previousClip;
 float ox=mat[0]*oldRelative.x+mat[4]*oldRelative.y+mat[8]*oldRelative.z+mat[12],oy=mat[1]*oldRelative.x+mat[5]*oldRelative.y+mat[9]*oldRelative.z+mat[13],ow=mat[3]*oldRelative.x+mat[7]*oldRelative.y+mat[11]*oldRelative.z+mat[15];
 if(RT_SIGNAL==0&&!params.referenceSpp&&ow>.00001f){float px=(ox/ow*.5f+.5f)*params.width-.5f,py=(oy/ow*.5f+.5f)*params.height-.5f;int x=(int)roundf(px),y=(int)roundf(py);
  if(x>=0&&x<params.width&&y>=0&&y<params.height){int j=y*params.width+x;float4 op=params.previousPosition[j],on=params.previousNormal[j];float3 delta=add(v(op.x,op.y,op.z),mul(p,-1));float tolerance=.1f+.002f*sqrtf(dot3(v(pp.x,pp.y,pp.z),v(pp.x,pp.y,pp.z)));
   auto oldKey=params.previousKey[j],key=params.surfaceKey[i];if(op.w>.5f&&oldKey.x==key.x&&oldKey.y==key.y&&fabsf(oldKey.z-key.z)<tolerance&&fabsf(dot3(delta,n))<tolerance&&dot3(n,v(on.x,on.y,on.z))>.95f){params.flow[i*2]=(int)(i%params.width)-px;params.flow[i*2+1]=(int)(i/params.width)-py;params.flowTrust[i]=1;}
  }
 }

 if(params.debug>=4&&params.debug<=31){if(RT_SIGNAL!=0)return;float3 debug=v(0,0,0);unsigned m=(unsigned)roundf(mm.y*255);auto diagnostic=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z)+.1f);if(diagnostic.hit){color=diagnostic.color;n=diagnostic.n;mm.x=diagnostic.roughness;m=diagnostic.metal;}
  if(params.debug==4)debug=color;else if(params.debug==5)debug=v(mm.x,mm.x,mm.x);else if(params.debug==6)debug=m>=230?v(1,.65f,.1f):v(0,0,0);else if(params.debug==7||params.debug==8){auto h=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z)+.1f);float value=params.debug==7?(h.hit?h.transmission:0):(h.hit?h.ior/2.5f:0);debug=v(value,value,value);}else if(params.debug==9)debug=add(mul(n,.5f),v(.5f,.5f,.5f));else if(params.debug==13)debug=params.sunVisibility[i].w>.5f?v(.1f,1,.1f):v(1,.05f,.05f);else if(params.debug==14){unsigned id=(unsigned)mm.z;debug=v((id&255)/255.f,((id>>8)&255)/255.f,0);}else if(params.debug==15)debug=sunT;else if(params.debug==16)debug=v(expf(-.16f*8),expf(-.06f*8),expf(-.035f*8));
  else if(params.debug>=17&&diagnostic.hit){auto m=diagnostic.bsdf;float scalar=0;debug=v(0,0,0);
   if(params.debug==17)debug=v(m.type/9.f,0,1-m.type/9.f);else if(params.debug==18)debug=diagnostic.color;else if(params.debug==19)scalar=m.microfacetAlpha;else if(params.debug==20)scalar=m.f0;else if(params.debug==21)debug=cv(m.eta*(1/4.f));else if(params.debug==22)debug=cv(m.k*(1/8.f));else if(params.debug==23)scalar=m.coatWeight;else if(params.debug==24)scalar=sqrtf(m.coatAlpha);else if(params.debug==25||params.debug==27)debug=cv(m.sigmaA*(1/8.f));else if(params.debug==26)debug=cv(m.sigmaS);else if(params.debug==28)debug=v((m.mediumId&255)/255.f,((m.mediumId>>8)&255)/255.f,0);else if(params.debug==29)scalar=m.porosity;else if(params.debug==30)scalar=m.sss;else if(params.debug==31)debug=diagnostic.emission;else if(params.debug==32)debug=v(0,0,0);if(scalar>0)debug=v(scalar,scalar,scalar);
  }else{for(int cascade=0;cascade<3;cascade++){float spacing=4.f*(1<<cascade);int gx=(int)floorf(p.x/spacing),gy=(int)floorf(p.y/spacing),gz=(int)floorf(p.z/spacing),slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));auto a=params.probes+slot*8;if(a[0].w>0&&fabsf(a[0].x-gx*spacing)+fabsf(a[0].y-gy*spacing)+fabsf(a[0].z-gz*spacing)<.1f){debug=params.debug==10?v(0,1,0):params.debug==11?v(fminf(1,(params.frame-a[5].z)/120),0,0):cascade==0?v(1,0,0):cascade==1?v(0,1,0):v(0,0,1);break;}}}
  params.diffuse[i]=make_float4(debug.x,debug.y,debug.z,1);if(dielectric)params.transmission[i]=make_float4(debug.x,debug.y,debug.z,sqrtf(pp.x*pp.x+pp.y*pp.y+pp.z*pp.z));return;
 }

 auto first=trace(params.camera,norm(v(pp.x,pp.y,pp.z)),sqrtf(dot3(v(pp.x,pp.y,pp.z),v(pp.x,pp.y,pp.z)))+.15f);if(!first.hit)return;
 if(params.debug>=32){if(RT_SIGNAL!=0)return;TransportDebug info;auto debugDirection=cosine(first.geometryNormal,seed);float3 diffuse=incoming(spawn(first,debugDirection),debugDirection,seed,8,true,0,&info);float3 out=v(0,0,0);unsigned debug=params.debug;
 if(debug==32)out=info.throughput;else if(debug==33)out=v(info.bounce/8.f,info.bounce/8.f,info.bounce/8.f);else if(debug==34)out=info.kind==0?v(1,1,0):info.kind==1?v(0,.5f,1):v(1,0,1);else if(debug==35)out=v((info.lobe&rt::DIFFUSE_LOBE)?1:0,(info.lobe&rt::GLOSSY)?1:0,(info.lobe&rt::TRANSMISSION)?1:0);else if(debug==36)out=v(info.mis,info.mis,info.mis);else if(debug==37)out=info.secondary;else if(debug==38)out=diffuse;else if(debug==39){auto bs=rt::sampleGlossy(first.bsdf,rt::Frame(rv(first.n)),rv(view),seed);if(bs.pdf>0)out=prod(cv(bs.weight),incoming(spawn(first,cv(bs.wi)),cv(bs.wi),seed,8,false));}else if(debug==40)out=info.emissive;else if(debug==41)out=info.sun;else if(debug==42)out=v(info.bsdfPdf/(1+info.bsdfPdf),0,0);else if(debug==43)out=v(info.lightPdf/(1+info.lightPdf),0,0);else if(debug==44)out=params.sunVisibility[i].w>.5f?v(0,1,0):v(1,0,0);
 params.diffuse[i]=make_float4(out.x,out.y,out.z,1);if(dielectric)params.transmission[i]=make_float4(out.x,out.y,out.z,first.distance);return;}
 unsigned batch=params.referenceSpp?rt::referenceBatch(params.referenceSpp,params.referenceSamples):1;float3 light=v(0,0,0);float signalHitDistance=0;TransportDebug diagnostic;
 for(unsigned r=0;r<batch;r++){
  float3 observation=v(0,0,0);
  if(RT_SIGNAL==0&&!dielectric&&(params.options&1)&&!rt::metal(first.bsdf)){
   float3 facing=dot3(n,view)>0?n:mul(n,-1);bool thin=first.bsdf.type==rt::DIFFUSE_TRANSMISSION;float t=thin?first.bsdf.transmission:0;
   if((params.options&16)&&!params.referenceSpp){float3 irradiance=add(mul(probeLight(p,facing),1-t),t>0?mul(probeLight(p,mul(facing,-1)),t):v(0,0,0));observation=prod(cv(first.bsdf.baseColor),mul(irradiance,thin?1:1-first.bsdf.f0));}
   else{bool back=thin&&random(seed)<t;float3 direction=cosine(back?mul(facing,-1):facing,seed);rt::Frame f(rv(facing));auto total=rt::evalBsdf(first.bsdf,f,rv(view),rv(direction)),glossy=rt::evalGlossy(first.bsdf,f,rv(view),rv(direction));rt::Vec diffuse=total.f-glossy.f;float probability=thin?(back?t:1-t):1;observation=prod(cv(diffuse*(rt::Pi/fmaxf(.001f,probability))),incoming(spawn(first,direction),direction,seed,params.referenceSpp?8:4,true,0,&diagnostic));}

  }
  if(RT_SIGNAL==5&&params.sunVisibility[i].w>.5f&&(params.options&2)){
   if(nn.w>1.5f)observation=incoming(params.camera,norm(v(pp.x,pp.y,pp.z)),seed,8,false,1,&diagnostic);
   else{observation=(params.options&64)?primaryGlossyDirect(first,view,seed):v(0,0,0);auto glossy=first.bsdf;rt::Frame frame(rv(dot3(first.n,view)>0?first.n:mul(first.n,-1)),rv(first.tangent),rv(first.bitangent));auto bs=rt::sampleGlossy(glossy,frame,rv(view),seed);if(bs.pdf>0){auto reflectedHit=trace(spawn(first,cv(bs.wi)),cv(bs.wi));signalHitDistance=reflectedHit.hit?reflectedHit.distance:512;observation=add(observation,prod(cv(bs.weight),incoming(spawn(first,cv(bs.wi)),cv(bs.wi),seed,params.referenceSpp?8:4,false,0,&diagnostic,true,(params.options&64)?bs.pdf:0)));}}
  }
  if(RT_SIGNAL==6&&(params.options&4)&&(rt::dielectric(first.bsdf)||params.underwater>.5f))observation=incoming(params.camera,norm(v(pp.x,pp.y,pp.z)),seed,8,false,rt::dielectric(first.bsdf)?2:0,&diagnostic);
  light=add(light,mul(observation,1/fmaxf(1,batch)));
 }
 if(RT_SIGNAL==0&&!rt::metal(first.bsdf)&&!rt::dielectric(first.bsdf)&&(params.options&32))light=add(light,prod(cv(first.bsdf.baseColor),mul(causticIrradiance(first.p,first.geometryNormal),1/rt::Pi)));
 float alpha=RT_SIGNAL==6&&(rt::dielectric(first.bsdf)||params.underwater>.5f)?first.distance:RT_SIGNAL==6?0:RT_SIGNAL==5?fmaxf(.001f,signalHitDistance):1;
 float4* output=RT_SIGNAL==0?params.diffuse:RT_SIGNAL==5?params.specular:params.transmission;
 if(params.referenceSpp){int slot=(RT_SIGNAL==0?0:RT_SIGNAL==5?1:2)*count+i;float4 old=params.referenceSum[slot];if(params.referenceSamples==0)old=make_float4(0,0,0,0);float weight=fminf(params.referenceSamples+batch,params.referenceSpp);float3 sum=add(v(old.x,old.y,old.z),mul(light,batch));params.referenceSum[slot]=make_float4(sum.x,sum.y,sum.z,weight);light=mul(sum,1/fmaxf(1,weight));}
 if(RT_SIGNAL==5&&params.flowTrust[i]>0){float px=i%params.width-params.flow[i*2],py=i/params.width-params.flow[i*2+1];int x=(int)roundf(px),y=(int)roundf(py);if(x>=0&&x<params.width&&y>=0&&y<params.height){auto previous=params.previousSignal[y*params.width+x];float tolerance=fmaxf(.05f,first.bsdf.microfacetAlpha*fmaxf(previous.x,alpha));if(fabsf(previous.x-alpha)>tolerance||fabsf(previous.z-first.bsdf.microfacetAlpha)>.02f)params.flowTrust[i]=0;}}
 output[i]=make_float4(light.x,light.y,light.z,alpha);
}
