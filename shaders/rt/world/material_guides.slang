#include "../common/material_transport.slang"
#include "../common/primary_state.slang"
#include "../common/reconstruction_math.slang"
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
    PathState path=primaryPath(index);
    SurfacePayload first=traceSurface(path.origin.xyz,path.direction.xyz,4096,255);
    if(first.distance<0)return;
    TerrainSurface surface=terrainSurface(first.triangle,first.bary,first.instance);
    float3 p=path.origin.xyz+path.direction.xyz*first.distance;
    float3 prevP=previousGuidePoint(p,first,surface),n=surface.frame.n;
    if(dot(n,path.direction.xyz)>0)n=-n;
    // Deterministic reflected endpoint, separate from stochastic radiance sampling.
    if(surface.material.microfacetAlpha<=.25&&(metal(surface.material)||dielectric(surface.material)||surface.material.f0>0)){
        float3 direction=reflect(path.direction.xyz,n);
        SurfacePayload reflected=traceSurface(p+n*.002,direction,4096,254);
        if(reflected.distance>=0){
            float3 q=p+n*.002+direction*reflected.distance;
            TerrainSurface target=terrainSurface(reflected.triangle,reflected.bary,reflected.instance);
            float3 prevQ=previousGuidePoint(q,reflected,target);
            output[base+8*count+index]=float4(rrMirror(q,p,n),reflected.distance);
            output[base+9*count+index]=float4(rrMirror(prevQ,prevP,n),1);
        }
    }
    // Follow smooth interfaces deterministically. No radiance, NEE, RNG or cache work.
    float3 origin=path.origin.xyz,direction=path.direction.xyz;float distance=0;
    SurfacePayload hit=first;TerrainSurface current=surface;bool transmitted=false;
    for(uint interface=0;interface<6;interface++){
        if(!dielectric(current.material)||current.material.microfacetAlpha>.04)break;
        float3 point=origin+direction*hit.distance,normal=current.geometryNormal;
        bool entering=dot(normal,direction)<0;normal=entering?normal:-normal;
        float3 next=current.material.type==THIN_DIELECTRIC?direction:rrTransmit(direction,normal,entering?1/current.material.ior:current.material.ior);
        if(dot(next,next)<.5)return; // TIR: keep the foreground tuple.
        distance+=hit.distance;origin=point+next*.002;direction=next;
        hit=traceSurface(origin,direction,4096,254);if(hit.distance<0)return;
        current=terrainSurface(hit.triangle,hit.bary,hit.instance);transmitted=true;
    }
    if(transmitted&&!dielectric(current.material)){
        float3 endpoint=origin+direction*hit.distance,previous=previousGuidePoint(endpoint,hit,current);
        // Virtual depth remains on the camera ray. Endpoint motion is a local approximation
        // for bent/multiple interfaces; a stationary camera and scene give exact zero motion.
        float3 virtualPoint=path.origin.xyz+path.direction.xyz*(distance+hit.distance);
        output[base+index]=float4(current.material.baseColor,float(current.material.type+1));
        output[base+count+index]=float4(dot(current.frame.n,direction)<0?current.frame.n:-current.frame.n,current.material.microfacetAlpha);
        output[base+2*count+index]=float4(virtualPoint,distance+hit.distance);
        output[base+6*count+index]=float4(virtualPoint+previous-endpoint,1);
        // Foreground specular reflectance/roughness remains owned by the interface.
    }
}
