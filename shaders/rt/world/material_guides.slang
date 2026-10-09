#include "../common/material_transport.slang"
#include "../common/primary_state.slang"
#include "../common/reconstruction_math.slang"
// Counters use existing reserved feedback header; only sampled counter frames pay atomics.
void guideCount(uint column){if((uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[1617u+column/4][column%4],1,ignored);}}
bool guideHistoryValid(SurfacePayload hit,TerrainSurface surface){return (surface.flags&128)==0||normals[559240u+hit.instance*3+1].w>0;}
float3 previousGuidePoint(float3 point,SurfacePayload hit,TerrainSurface surface){
    float4 previous=normals[559240u+hit.instance*3+1];
    if((surface.flags&128)==0||previous.w<=0)return point;
    uint offset=67108864u+hit.triangle*120;
    return rtPreviousSurface(asfloat(readWords3(geometry,offset)),asfloat(readWords3(geometry,offset+40)),asfloat(readWords3(geometry,offset+80)),hit.bary,previous.xyz);
}
[shader("raygeneration")]
void material_guides(){
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x;
    uint count=camera.width*camera.height,base=count*max(1u,camera.padding)+4;
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;PathState path=(PathState)0;path.origin=float4(camera.origin.xyz,1);path.direction=float4(primaryDirection(index,seed),0);
    guideCount(0);SurfacePayload first;TerrainSurface surface=(TerrainSurface)0;
    float3 p,prevP,n;bool reflectEligible,transmitEligible;
#ifdef RT_GUIDE_FAST
    uint4 record=asuint(output[base+8*count+index]),meta=asuint(output[base+9*count+index]);
    output[base+8*count+index]=0;output[base+9*count+index]=0;
    GuideHitRecord hitRecord;hitRecord.hit=record;hitRecord.meta=meta;bool valid=rrHitValid(hitRecord,camera.frame);
    if(valid){
        guideCount(1);first.distance=asfloat(record.x);first.bary=asfloat(record.yz);first.triangle=record.w;first.instance=meta.x;
        if(first.distance<0){guideCount(3);return;}
        reflectEligible=(meta.z&2u)!=0;transmitEligible=(meta.z&4u)!=0;
        if(!reflectEligible&&!transmitEligible){guideCount((meta.z&8u)!=0?12:4);return;}
        p=output[base+2*count+index].xyz;prevP=output[base+6*count+index].xyz;n=output[base+count+index].xyz;
        if(transmitEligible){surface=terrainSurface(first.triangle,first.bary,first.instance);guideCount(13);}
    }else{
        guideCount(2);guideCount(5);first=traceSurface(path.origin.xyz,path.direction.xyz,4096,255);
        if(first.distance<0){guideCount(3);return;}
        surface=terrainSurface(first.triangle,first.bary,first.instance);guideCount(13);
        p=path.origin.xyz+path.direction.xyz*first.distance;prevP=previousGuidePoint(p,first,surface);n=surface.frame.n;
        reflectEligible=surface.material.microfacetAlpha<=.25&&(metal(surface.material)||dielectric(surface.material)||surface.material.f0>0);
        transmitEligible=dielectric(surface.material)&&surface.material.microfacetAlpha<=.04;
    }
#else
    guideCount(5);first=traceSurface(path.origin.xyz,path.direction.xyz,4096,255);
    if(first.distance<0){guideCount(3);return;}
    surface=terrainSurface(first.triangle,first.bary,first.instance);guideCount(13);
    p=path.origin.xyz+path.direction.xyz*first.distance;prevP=previousGuidePoint(p,first,surface);n=surface.frame.n;
    reflectEligible=surface.material.microfacetAlpha<=.25&&(metal(surface.material)||dielectric(surface.material)||surface.material.f0>0);
    transmitEligible=dielectric(surface.material)&&surface.material.microfacetAlpha<=.04;
    if(!reflectEligible&&!transmitEligible){guideCount(dielectric(surface.material)?12:4);return;}
#endif
    if(dot(n,path.direction.xyz)>0)n=-n;
    bool foregroundHistoryValid=output[base+6*count+index].w>0;
    // Deterministic reflected endpoint, separate from stochastic radiance sampling.
    if(reflectEligible){
        float3 direction=reflect(path.direction.xyz,n);
        guideCount(6);SurfacePayload reflected=traceSurface(p+n*.002,direction,4096,254);
        if(reflected.distance>=0){
            float3 q=p+n*.002+direction*reflected.distance;
            guideCount(7);guideCount(13);TerrainSurface target=terrainSurface(reflected.triangle,reflected.bary,reflected.instance);
            float3 prevQ=previousGuidePoint(q,reflected,target);
            output[base+8*count+index]=float4(rrMirror(q,p,n),reflected.distance);
            output[base+9*count+index]=float4(rrMirror(prevQ,prevP,n),foregroundHistoryValid&&guideHistoryValid(reflected,target)?1:0);
        }else guideCount(8);
    }
    if(!transmitEligible)return;
    // Follow smooth interfaces deterministically. No radiance, NEE, RNG or cache work.
    float3 origin=path.origin.xyz,direction=path.direction.xyz;float distance=0;
    SurfacePayload hit=first;TerrainSurface current=surface;bool transmitted=false;
    for(uint interface=0;interface<6;interface++){
        if(!dielectric(current.material)||current.material.microfacetAlpha>.04)break;
        float3 point=origin+direction*hit.distance,normal=current.geometryNormal;
        bool entering=dot(normal,direction)<0;normal=entering?normal:-normal;
        float3 next=current.material.type==THIN_DIELECTRIC?direction:rrTransmit(direction,normal,entering?1/current.material.ior:current.material.ior);
        if(dot(next,next)<.5){guideCount(11);return;} // TIR: keep the foreground tuple.
        distance+=hit.distance;origin=point+next*.002;direction=next;
        guideCount(9);hit=traceSurface(origin,direction,4096,254);if(hit.distance<0){guideCount(8);return;}
        guideCount(13);
        current=terrainSurface(hit.triangle,hit.bary,hit.instance);transmitted=true;
    }
    if(transmitted&&!dielectric(current.material)){
        guideCount(10);
        float3 endpoint=origin+direction*hit.distance,previous=previousGuidePoint(endpoint,hit,current);
        // Virtual depth remains on the camera ray. Endpoint motion is a local approximation
        // for bent/multiple interfaces; a stationary camera and scene give exact zero motion.
        float3 virtualPoint=path.origin.xyz+path.direction.xyz*(distance+hit.distance);
        output[base+index]=float4(current.material.baseColor,float(current.material.type+1));
        output[base+count+index]=float4(dot(current.frame.n,direction)<0?current.frame.n:-current.frame.n,current.material.microfacetAlpha);
        output[base+2*count+index]=float4(virtualPoint,distance+hit.distance);
        output[base+6*count+index]=float4(virtualPoint+previous-endpoint,foregroundHistoryValid&&guideHistoryValid(hit,current)?1:0);
        // Foreground specular reflectance/roughness remains owned by the interface.
    }else guideCount(14);
}
