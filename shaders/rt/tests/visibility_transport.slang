#define RT_CPU_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){
    SurfacePayload hit;hit.distance=-1;hit.bary=0;hit.triangle=0;hit.instance=0;
    for(uint triangle=0;triangle<camera.padding;triangle++){
        float3 a=terrainVertex(triangle,0).p,b=terrainVertex(triangle,1).p,c=terrainVertex(triangle,2).p;
        float3 e=b-a,f=c-a,p=cross(direction,f);float det=dot(e,p);if(abs(det)<1e-8)continue;
        float3 s=origin-a;float u=dot(s,p)/det;float3 q=cross(s,e);float v=dot(direction,q)/det,d=dot(f,q)/det;
        if(u>=0&&v>=0&&u+v<=1&&d>=.001&&d<=maximum&&(hit.distance<0||d<hit.distance)){hit.distance=d;hit.bary=float2(u,v);hit.triangle=triangle;}
    }return hit;
}
[shader("compute")] [numthreads(1,1,1)]
void visibility_transport(uint3 tid:SV_DispatchThreadID){
    PathState path=(PathState)0;path.origin=float4(0,0,.5,1);path.direction=float4(0,0,-1,0);path.throughput=float4(1,1,1,5);path.previousDelta=1;path.etaScale=1;path.radiance.w=asfloat(17u);
    advance(path);output[0]=pathOutput(path);
}
