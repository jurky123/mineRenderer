#include "device.cuh"
extern "C" __global__ void __miss__radiance(){payload()->hit=0;}
extern "C" __global__ void __anyhit__surface(){
 const auto* data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());int i=optixGetPrimitiveIndex()*3;float2 b=optixGetTriangleBarycentrics();const auto& a=data->vertices[i];const auto& c=data->vertices[i+1];const auto& d=data->vertices[i+2];
 if(a.flags&1){float2 uv=make_float2(a.uv.x*(1-b.x-b.y)+c.uv.x*b.x+d.uv.x*b.y,a.uv.y*(1-b.x-b.y)+c.uv.y*b.x+d.uv.y*b.y);if(data->textureSlot<0&&((sample(params.ids,params.idsWidth,params.idsHeight,uv)>>24)&3)>0)return;if(!rt::cutoutVisible(hitTexture(data,uv)>>24,a.tint>>24,a.flags))optixIgnoreIntersection();}
}
extern "C" __global__ void __closesthit__surface(){
 countOperation(6);auto* p=payload();p->hit=1;p->distance=optixGetRayTmax();p->p=add(optixGetWorldRayOrigin(),mul(optixGetWorldRayDirection(),p->distance));
 const auto* data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());int i=optixGetPrimitiveIndex()*3;float2 b=optixGetTriangleBarycentrics();auto a=data->vertices[i],c=data->vertices[i+1],d=data->vertices[i+2];float w=1-b.x-b.y;
 p->n=norm(optixTransformNormalFromObjectToWorldSpace(add(add(mul(a.n,w),mul(c.n,b.x)),mul(d.n,b.y))));float2 uv=make_float2(a.uv.x*w+c.uv.x*b.x+d.uv.x*b.y,a.uv.y*w+c.uv.y*b.x+d.uv.y*b.y);
 unsigned tex=hitTexture(data,uv),id=data->textureSlot<0?sample(params.ids,params.idsWidth,params.idsHeight,uv):0,profile=data->textureSlot<0?params.lut[id&65535]:0xff200a33;
 if(dot3(p->n,p->n)<.1f)p->n=norm(optixTransformNormalFromObjectToWorldSpace(cross3(add(c.p,mul(a.p,-1)),add(d.p,mul(a.p,-1)))));
 p->geometryNormal=norm(optixTransformNormalFromObjectToWorldSpace(cross3(add(c.p,mul(a.p,-1)),add(d.p,mul(a.p,-1)))));
 if(dot3(p->n,p->geometryNormal)<0)p->n=mul(p->n,-1);
 rt::Frame baseFrame(rv(p->n));p->tangent=cv(baseFrame.t);p->bitangent=cv(baseFrame.b);
 unsigned packedNormal=data->textureSlot<0?sample(params.normalMap,params.idsWidth,params.idsHeight,uv):0xff008080;
 float nx=(packedNormal&255)/127.5f-1,ny=((packedNormal>>8)&255)/127.5f-1;
 float3 e1=add(c.p,mul(a.p,-1)),e2=add(d.p,mul(a.p,-1));float ux=c.uv.x-a.uv.x,uy=c.uv.y-a.uv.y,vx=d.uv.x-a.uv.x,vy=d.uv.y-a.uv.y,det=ux*vy-uy*vx;
 if(fabsf(det)>1.e-10f){float3 tangent=optixTransformVectorFromObjectToWorldSpace(mul(add(mul(e1,vy),mul(e2,-uy)),1/det));tangent=norm(add(tangent,mul(p->n,-dot3(tangent,p->n))));float3 bitangent=optixTransformVectorFromObjectToWorldSpace(mul(add(mul(e2,ux),mul(e1,-vx)),1/det));bitangent=norm(add(bitangent,mul(p->n,-dot3(bitangent,p->n))));p->tangent=tangent;p->bitangent=bitangent;p->n=norm(add(add(mul(tangent,nx),mul(bitangent,ny)),mul(p->n,sqrtf(fmaxf(0,1-nx*nx-ny*ny)))));}
 if(dot3(p->n,p->geometryNormal)<=0)p->n=p->geometryNormal;
 p->color=prod(rgb(tex),rgb(a.tint));p->roughness=1-(profile&255)/255.f;p->f0=((profile>>8)&255)/255.f;p->metal=(profile>>8)&255;p->flags=a.flags;
 unsigned type=(id>>24)&3;if(type==2)p->n=waterNormal(p->n,p->p);p->transmission=type?1:0;p->ior=type==2?1.333f:1.5f;p->absorption=type==2?v(.16f,.06f,.035f):v(-logf(fmaxf(.05f,p->color.x))*.7f,-logf(fmaxf(.05f,p->color.y))*.7f,-logf(fmaxf(.05f,p->color.z))*.7f);
 p->emission=mul(p->color,(((id>>24)&4)?((id>>16)&255)/254.f:(a.flags>>16)/15.f)*2.4f);
 p->objectId=optixGetInstanceId();p->primitiveId=optixGetPrimitiveIndex();p->bsdf=decodeMaterial(id,profile,p->color,p->flags);
 p->bsdf.emission=rv(p->emission);p->roughness=sqrtf(p->bsdf.microfacetAlpha);p->transmission=rt::dielectric(p->bsdf)?p->bsdf.transmission:0;p->ior=p->bsdf.ior;p->absorption=cv(p->bsdf.sigmaA);
 if(type==1&&rt::maxComponent(p->bsdf.sigmaA)==0)p->bsdf.sigmaA=rv(p->absorption=v(-logf(fmaxf(.001f,p->color.x))*.7f,-logf(fmaxf(.001f,p->color.y))*.7f,-logf(fmaxf(.001f,p->color.z))*.7f));
 if(p->flags&8&&rt::dielectric(p->bsdf))p->bsdf.type=rt::THIN_DIELECTRIC;
 // Medium identity excludes per-texel roughness IDs; textured entry/exit share their object/class/IOR volume.
 p->bsdf.mediumId=1+(p->objectId<<16)+(p->bsdf.type<<8)+(unsigned)roundf(p->ior*255/3);
 if(params.rain>0&&!rt::dielectric(p->bsdf)&&p->bsdf.type!=rt::DIFFUSE_TRANSMISSION){auto& m=p->bsdf;float skyAccess=((a.flags>>8)&15)/15.f;float wet=params.rain*skyAccess*smooth(.2f,.9f,p->geometryNormal.y);m.coatWeight=fmaxf(m.coatWeight,wet*(1-m.porosity)*.6f);if(wet>0){m.coatIOR=1.333f;m.coatAlpha=.025f;}m.baseColor=m.baseColor*(1-wet*m.porosity*.18f);if(m.coatWeight>0)m.type=rt::metal(m)?rt::COATED_CONDUCTOR:rt::COATED_DIFFUSE;}


}
// Isolated A/B geometry kernel. The application world continues to use exact compiled triangles.
extern "C" __global__ void __intersection__cube(){auto data=reinterpret_cast<const RtHitData*>(optixGetSbtDataPointer());auto cube=reinterpret_cast<RtCube*>(data->vertices)[optixGetPrimitiveIndex()];float3 o=optixGetObjectRayOrigin(),d=optixGetObjectRayDirection();float3 lo=v((cube.minimum.x-o.x)/d.x,(cube.minimum.y-o.y)/d.y,(cube.minimum.z-o.z)/d.z),hi=v((cube.maximum.x-o.x)/d.x,(cube.maximum.y-o.y)/d.y,(cube.maximum.z-o.z)/d.z);float near=fmaxf(fmaxf(fminf(lo.x,hi.x),fminf(lo.y,hi.y)),fminf(lo.z,hi.z)),far=fminf(fminf(fmaxf(lo.x,hi.x),fmaxf(lo.y,hi.y)),fmaxf(lo.z,hi.z));if(near<=far){float t=near>=optixGetRayTmin()?near:far;if(t>=optixGetRayTmin()&&t<=optixGetRayTmax())optixReportIntersection(t,0);}}
extern "C" __global__ void __closesthit__cube(){auto p=payload();p->hit=1;p->distance=optixGetRayTmax();}
