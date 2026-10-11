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
    if(camera.frame==4){
        uint failures=0;
        for(uint lane=0;lane<8;lane++)for(uint step=0;step<10;step++){
            uint count=step<9?8-step:8;
            PathState p=(PathState)0;p.origin=float4(1,2,3,1);p.direction=float4(0,0,1,.25);p.throughput=float4(.2,.3,.4,2);
            p.mediumCount=count;p.etaScale=1.7;p.previousDelta=1;p.channel=2;p.reserved1=123;p.radiance.w=asfloat(17u+step);
            for(uint i=0;i<count;i++){p.absorptionIor[i]=float4(i+1,i+2,i+3,1.33);p.scatteringPhase[i]=float4(i+.1,i+.2,i+.3,.4);p.mediumIds[i/4][i%4]=101+i;}
            storePath(lane,p);PathState q=loadPath(lane,loadHot(lane));
            failures+=q.mediumCount!=count||q.etaScale!=p.etaScale||q.previousDelta!=p.previousDelta||q.channel!=p.channel||q.reserved1!=p.reserved1||asuint(q.radiance.w)!=asuint(p.radiance.w)||any(q.origin!=p.origin)||any(q.direction!=p.direction)||any(q.throughput!=p.throughput);
            for(uint i=0;i<count;i++)failures+=any(q.absorptionIor[i]!=p.absorptionIor[i])||any(q.scatteringPhase[i]!=p.scatteringPhase[i])||q.mediumIds[i/4][i%4]!=101+i;
        }
        output[0]=float4(failures,0,0,0);return;
    }
    if(camera.frame==5){
        float error=0;uint failures=0;
        for(uint type=0;type<=9;type++)for(uint i=0;i<32;i++){
            Material m=defaultMaterial();m.type=type;m.baseColor=float3(.3,.5,.7);m.microfacetAlpha=.2;m.transmission=.8;
            Frame f=makeFrame(safeNormalize(float3(.8,0,.6)));float3 wo=safeNormalize(float3(float(i%3)-1,.2,1));
            LightSample light=(LightSample)0;light.pdf=.3;light.radiance=float3(2,3,4);light.wi=safeNormalize(float3(float(i%5)-2,.3,(i&1)!=0?1:-1));light.kind=i%4;
            float3 actual=directIntegrand(light,m,f,wo,float3(0,0,1),1.5,1,false,0,float3(0));
            BsdfEval e=evalBsdf(m,f,wo,light.wi,1.5);float3 expected=0;
            if(e.pdf>0&&validShadingHemisphere(float3(0,0,1),f.n,wo,light.wi))expected=light.radiance*e.f*(light.kind==3?1:powerHeuristic(directSurvival(1)*directBudget(1)*light.pdf,e.pdf))*abs(dot(f.n,light.wi));
            if(!all(isfinite(actual))||!all(isfinite(expected)))failures++;else error=max(error,maxComponent(abs(actual-expected)));
        }
        output[0]=float4(error,failures,0,0);return;
    }
    if(camera.frame==3){
        PathState a=(PathState)0;a.origin=float4(0,0,.5,1);a.direction=float4(0,0,-1,0);a.throughput=float4(1,1,1,0);a.previousDelta=1;a.etaScale=1;a.radiance.w=asfloat(17u);PathState b=a;
        SurfacePayload hit=fixtureTrace(a.origin.xyz,a.direction.xyz,4096);advance(a);advanceHit(b,hit,true,true);
        output[0]=float4(max(maxComponent(abs(a.radiance.xyz-b.radiance.xyz)),maxComponent(abs(a.throughput.xyz-b.throughput.xyz))),maxComponent(abs(a.direction.xyz-b.direction.xyz)),maxComponent(abs(a.origin.xyz-b.origin.xyz)),asuint(a.radiance.w)==asuint(b.radiance.w)&&a.mediumCount==b.mediumCount&&a.channel==b.channel&&a.origin.w==b.origin.w?0:1);return;
    }
    if(camera.frame>0){MediumStack media;media.count=0;if(camera.frame==2){Medium m={float3(.2,.3,.4),float3(.1,.2,.3),0,1.33,77};media.entries[0]=m;media.count=1;}output[0]=float4(visibility(float3(0,0,.5),float3(0,0,1),media,1.499),1);return;}
    PathState path=(PathState)0;path.origin=float4(0,0,.5,1);path.direction=float4(0,0,-1,0);path.throughput=float4(1,1,1,5);path.previousDelta=1;path.etaScale=1;path.radiance.w=asfloat(17u);
    advance(path);output[0]=pathOutput(path);
}
