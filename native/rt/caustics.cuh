// Bounded directional photon cache: actual current wave normals and Snell refraction.
// One frame owns deposition; a separate launch resolves it before receiver sampling.
static __forceinline__ __device__ void causticPhoton(unsigned i,unsigned& seed){
 countOperation(7);float cell=.25f;int cx=(int)floorf(params.camera.x/cell)-32,cz=(int)floorf(params.camera.z/cell)-32;
 int gx=cx+(i&63),gz=cz+((i>>6)&63);float3 destination=v((gx+random(seed))*cell,params.camera.y,(gz+random(seed))*cell);
 if(params.sun.y<.08f)return;float3 direction=mul(params.sun,-1),origin=add(destination,mul(params.sun,64/params.sun.y));rt::Vec power=rv(params.sunColor)*(params.sun.y*cloudVisibility(destination));rt::MediumStack stack;bool refracted=false;
 for(int bounce=0;bounce<12;bounce++){
  auto hit=trace(origin,direction,256);if(!hit.hit)return;
  if(stack.count)power=power*rt::expNeg(stack.entries[stack.count-1].sigmaA+stack.entries[stack.count-1].sigmaS,hit.distance);
  if(!rt::dielectric(hit.bsdf)){
   if(!refracted||rt::metal(hit.bsdf))return;int x=(int)floorf(hit.p.x/cell),z=(int)floorf(hit.p.z/cell);if(x<cx||x>=cx+64||z<cz||z>=cz+64)return;int slot=(x&63)|((z&63)<<6);auto tag=params.caustics+slot*2; // admission preinitialized, one receiver sheet per column
   if(fabsf(tag[0].y-hit.p.y)>.2f&&tag[0].w>0)return;
   atomicExch(&tag[0].y,hit.p.y);atomicExch(&tag[0].w,1.f);
   atomicAdd(&tag[1].x,power.x);atomicAdd(&tag[1].y,power.y);atomicAdd(&tag[1].z,power.z);atomicAdd(&tag[1].w,1.f);return;
  }
  auto m=hit.bsdf;bool entering=dot3(hit.geometryNormal,direction)<0;float from=stack.ior(),to=entering?m.ior:stack.outside(m.mediumId),eta=from/to;float3 n=dot3(hit.n,direction)<0?hit.n:mul(hit.n,-1);float cosine=fmaxf(0,-dot3(n,direction)),k=1-eta*eta*(1-cosine*cosine);if(k<=0)return;
  float F=rt::fresnelDielectric(cosine,to/from);if(m.type==rt::THIN_DIELECTRIC)F=2*F/(1+F);power=power*((1-F)*m.transmission);if(m.type==rt::THIN_DIELECTRIC){power=power*rt::expNeg(m.sigmaA,m.thickness/fmaxf(.01f,cosine));}
  else{direction=norm(add(mul(direction,eta),mul(n,eta*cosine-sqrtf(k))));if(entering){if(!stack.enter({m.sigmaA,m.sigmaS,m.phaseG,m.ior,m.mediumId}))return;}else if(!stack.exit(m.mediumId))return;}
  refracted=true;origin=spawn(hit,direction);
 }
}
