#define RT_CPU_FIXTURE 1
#define RT_VISIBILITY_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTraceFiltered(float3 origin,float3 direction,float maximum,int category){
    SurfacePayload hit;hit.distance=-1;hit.bary=0;hit.triangle=0;hit.instance=0;
    for(uint triangle=0;triangle<camera.padding;triangle++){
        uint flags=terrainVertex(triangle,0).flags;bool opaque=(flags&5)==0;if(category==1&&opaque||category==2&&!opaque)continue;
        float3 a=terrainVertex(triangle,0).p,b=terrainVertex(triangle,1).p,c=terrainVertex(triangle,2).p;
        float3 e=b-a,f=c-a,p=cross(direction,f);float det=dot(e,p);if(abs(det)<1e-8)continue;
        float3 s=origin-a;float u=dot(s,p)/det;float3 q=cross(s,e);float v=dot(direction,q)/det,d=dot(f,q)/det;
        if(u>=0&&v>=0&&u+v<=1&&d>=.001&&d<=maximum&&(hit.distance<0||d<hit.distance)&&visibleTexel(triangle,float2(u,v))){
            if(category==3){Material m=terrainSurface(triangle,float2(u,v),0).material;if(dielectric(m)||m.type==DIFFUSE_TRANSMISSION)continue;}
            hit.distance=d;hit.bary=float2(u,v);hit.triangle=triangle;}
    }return hit;
}
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return fixtureTraceFiltered(origin,direction,maximum,0);}
[shader("compute")] [numthreads(1,1,1)]
void visibility_transport(uint3 tid:SV_DispatchThreadID){
    if(camera.frame>0){MediumStack media;media.count=0;if(camera.frame==2){Medium m={float3(.2,.3,.4),float3(.1,.2,.3),0,1.33,77};media.entries[0]=m;media.count=1;}output[0]=float4(visibility(float3(0,0,.5),float3(0,0,1),media,1.499),1);return;}
    PathState path=(PathState)0;path.origin=float4(0,0,.5,1);path.direction=float4(0,0,-1,0);path.throughput=float4(1,1,1,5);path.previousDelta=1;path.etaScale=1;path.radiance.w=asfloat(17u);
    advance(path);output[0]=pathOutput(path);
}
