#pragma once
#include "terrain_material.slang"
#include "medium.slang"
#include "water_surface.slang"
#include "environment.slang"
#include "emissive.slang"
struct SurfacePayload {float distance;float2 bary;uint triangle;uint instance;};
[[vk::binding(0, 0)]] RaytracingAccelerationStructure world;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(2, 0)]] StructuredBuffer<float4> normals;
struct Camera {float4 inverseClip0;float4 inverseClip1;float4 inverseClip2;float4 inverseClip3;float4 origin;uint width;uint height;uint frame;uint padding;};
[[vk::binding(3, 0)]] ConstantBuffer<Camera> camera;
// 368-byte explicit continuation ABI; eight nested media survive dispatch boundaries.
struct PathState {
    float4 origin,direction,throughput,radiance;
    float4 absorptionIor[8];float4 scatteringPhase[8];uint4 mediumIds[2];
    uint mediumCount;float etaScale;uint previousDelta;uint padding;
};
[[vk::binding(4, 0)]] RWStructuredBuffer<PathState> paths;
SurfacePayload traceSurface(float3 origin,float3 direction,float maximum=4096) {
    RayDesc ray;ray.Origin=origin;ray.Direction=direction;ray.TMin=.001;ray.TMax=maximum;
    SurfacePayload hit;hit.distance=-1;hit.bary=0;hit.triangle=0;hit.instance=0;
    TraceRay(world,RAY_FLAG_NONE,255,0,0,0,ray,hit);return hit;
}
MediumStack loadMedia(PathState path) {
    MediumStack media;media.count=(int)path.mediumCount;
    for(int i=0;i<media.count;i++) {
        Medium m={path.absorptionIor[i].xyz,path.scatteringPhase[i].xyz,path.scatteringPhase[i].w,path.absorptionIor[i].w,path.mediumIds[i>>2][i&3]};media.entries[i]=m;
    }
    return media;
}
void storeMedia(inout PathState path,MediumStack media) {
    path.mediumCount=(uint)media.count;
    for(int i=0;i<media.count;i++) {
        Medium m=media.entries[i];path.absorptionIor[i]=float4(m.sigmaA,m.ior);path.scatteringPhase[i]=float4(m.sigmaS,m.g);path.mediumIds[i>>2][i&3]=m.id;
    }
}
Medium materialMedium(Material m){Medium medium={m.sigmaA,m.sigmaS,m.phaseG,m.ior,m.mediumId};return medium;}
bool crossMedium(inout MediumStack media,Material m,bool entering){return entering?media.enter(materialMedium(m)):media.exit(m.mediumId);}
// Straight shadow visibility through bounded interfaces, with Fresnel and RGB extinction.
// No secondary TraceRay call in any-hit/closest-hit; recursion remains one.
float3 visibility(float3 origin,float3 direction,MediumStack media,float maximum=4096) {
    float3 value=float3(1);
    for(int boundary=0;boundary<24;boundary++) {
        SurfacePayload hit=traceSurface(origin,direction,max(.001,maximum));float distance=hit.distance>=0?hit.distance:maximum;
        if(media.count>0){Medium m=media.entries[media.count-1];value*=expNeg(m.sigmaA+m.sigmaS,distance);}
        if(hit.distance<0)return value;
        TerrainSurface surface=terrainSurface(hit.triangle,hit.bary,hit.instance);Material m=surface.material;
        if(!dielectric(m)&&m.type!=DIFFUSE_TRANSMISSION)return float3(0);
        bool entering=dot(surface.geometryNormal,direction)<0;
        float eta=entering?m.ior/media.ior():media.outside(m.mediumId)/media.ior();
        float F=fresnelDielectric(dot(surface.geometryNormal,direction),eta);
        if(m.type==THIN_DIELECTRIC){F=2*F/(1+F);value*=expNeg(m.sigmaA,m.thickness/max(.01,abs(dot(surface.geometryNormal,direction))));}
        value*=m.type==DIFFUSE_TRANSMISSION?m.baseColor*m.transmission:float3((1-F)*m.transmission);
        if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&!crossMedium(media,m,entering))return float3(0);
        if(maxComponent(value)<1e-6)return float3(0);
        float3 point=origin+direction*hit.distance;float3 next=offsetRayOrigin(point,surface.geometryNormal,direction);
        maximum-=hit.distance+max(0,dot(next-point,direction));if(maximum<=.001)return value;origin=next;
    }
    return float3(0); // Conservative interface-limit handling.
}
bool finiteEnergy(float3 value){return all(isfinite(value))&&all(value>=0);}
void addRadiance(inout PathState path,float3 contribution){
    float3 sum=path.radiance.xyz+contribution;
    if(!finiteEnergy(contribution)||!finiteEnergy(sum)){path.padding=1;path.origin.w=0;return;}
    path.radiance.xyz=sum;
}
float4 pathOutput(PathState path){return float4(path.padding==0&&finiteEnergy(path.radiance.xyz)?path.radiance.xyz:float3(0),path.padding==0?1:0);}
void advance(inout PathState path) {
    if(path.origin.w==0||path.padding!=0)return;
    uint seed=asuint(path.radiance.w);MediumStack media=loadMedia(path);
    SurfacePayload hit=traceSurface(path.origin.xyz,path.direction.xyz);
    TerrainSurface surface=(TerrainSurface)0;bool entering=false;
    if(hit.distance>=0) {
        surface=terrainSurface(hit.triangle,hit.bary,hit.instance);entering=dot(surface.geometryNormal,path.direction.xyz)<0;
        if(!entering&&dielectric(surface.material)&&surface.material.type!=THIN_DIELECTRIC&&media.count==0)media.enter(materialMedium(surface.material));
    }
    float distance=hit.distance>=0?hit.distance:128;
    if(media.count>0) {
        Medium m=media.entries[media.count-1];float3 extinction=m.sigmaA+m.sigmaS;
        // Realtime finite-segment first-order scattering; full free-flight reference remains pending.
        if(maxComponent(m.sigmaS)>0) {
            MediumDistanceSample sample=sampleMediumDistance(extinction,distance,seed);float3 event=path.origin.xyz+path.direction.xyz*sample.distance;
            LightSample light=sampleOneLight(event,seed);
            // This first-order segment estimator has no competing phase continuation.
            // Applying surface MIS here would remove energy rather than reduce variance.
            if(light.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,light.wi),m.g)/(sample.pdf*light.pdf))*light.radiance*visibility(event,light.wi,media,max(.001,light.distance-.002)));
            LightSample held=sampleHeldLight(event);
            if(held.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,held.wi),m.g)/sample.pdf)*held.radiance*visibility(event,held.wi,media,max(.001,held.distance-.002)));
            LightSample emitter=sampleEmitter(event,seed);
            if(emitter.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,emitter.wi),m.g)/(sample.pdf*emitter.pdf))*emitter.radiance*visibility(event,emitter.wi,media,max(.001,emitter.distance-.002)));
        }
        path.throughput.xyz*=expNeg(extinction,distance);
    }
    if(hit.distance<0) {
        addRadiance(path,path.throughput.xyz*missRadiance(path.direction.xyz,path.direction.w,path.previousDelta!=0));path.origin.w=0;
    } else {
        Material m=surface.material;float3 point=path.origin.xyz+path.direction.xyz*hit.distance;
        if(m.type==WATER) {
            float4 controls=asfloat(assets.Load4(64));
            float3 waterNormal=terrainWaterNormal(surface.frame.n,point,camera.origin.xyz,controls.x,controls.z,asfloat(assets.Load(80)),controls.y,controls.w);
            if(dot(waterNormal,surface.geometryNormal)>0)surface.frame=makeFrame(waterNormal,surface.frame.t,surface.frame.b);
        }
        float3 wo=-path.direction.xyz;float3 n=dot(surface.frame.n,wo)>0?surface.frame.n:-surface.frame.n;
        Frame frame=makeFrame(n,surface.frame.t,surface.frame.b);
        float eta=entering?m.ior/media.ior():media.outside(m.mediumId)/media.ior();
        float emissionMis=path.previousDelta!=0||path.throughput.w==0?1:powerHeuristic(path.direction.w,emitterHitPdf(hit.triangle,path.origin.xyz,point));
        addRadiance(path,path.throughput.xyz*m.emission*emissionMis);
        // One stochastic sky/sun sample plus an independent held delta connection.
        for(int connection=0;connection<2;connection++){
        LightSample light=connection==0?sampleOneLight(point,seed):sampleHeldLight(point);
        BsdfEval direct=(BsdfEval)0;if(light.pdf>0)direct=evalBsdf(m,frame,wo,light.wi,eta);
        if(light.pdf>0&&direct.pdf>0&&validShadingHemisphere(surface.geometryNormal,n,wo,light.wi)) {
            MediumStack shadowMedia=media;bool visible=true;
            if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&dot(n,light.wi)<0)visible=crossMedium(shadowMedia,m,entering);
            float mis=lightMisWeight(light,direct.pdf,path.throughput.w<5);
            if(visible)addRadiance(path,path.throughput.xyz*direct.f*(abs(dot(n,light.wi))*mis/light.pdf)*light.radiance
                *visibility(offsetRayOrigin(point,surface.geometryNormal,light.wi),light.wi,shadowMedia,max(.001,light.distance-.002)));
        }
        }
        LightSample emitter=sampleEmitter(point,seed);
        if(emitter.pdf>0){
            BsdfEval e=evalBsdf(m,frame,wo,emitter.wi,eta);
            MediumStack shadowMedia=media;bool visible=true;
            if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&dot(n,emitter.wi)<0)visible=crossMedium(shadowMedia,m,entering);
            if(visible&&e.pdf>0&&validShadingHemisphere(surface.geometryNormal,n,wo,emitter.wi)){
                float mis=path.throughput.w<5?powerHeuristic(emitter.pdf,e.pdf):1;
                float3 start=offsetRayOrigin(point,surface.geometryNormal,emitter.wi);
                float maximum=emitter.distance-max(0,dot(start-point,emitter.wi))-.002;
                if(maximum>.001)addRadiance(path,path.throughput.xyz*e.f*(abs(dot(n,emitter.wi))*mis/emitter.pdf)*emitter.radiance*visibility(start,emitter.wi,shadowMedia,maximum));
            }
        }
        if(path.throughput.w>=5) {path.origin.w=0;storeMedia(path,media);path.radiance.w=asfloat(seed);return;}
        BsdfSample sample=sampleBsdf(m,frame,wo,seed,eta);
        bool valid=isfinite(sample.pdf)&&finiteEnergy(sample.weight)&&all(isfinite(sample.wi))&&sample.pdf>0&&validShadingHemisphere(surface.geometryNormal,n,wo,sample.wi);
        if(valid&&(sample.flags&TRANSMISSION)!=0&&dielectric(m)&&m.type!=THIN_DIELECTRIC) {
            valid=crossMedium(media,m,entering);path.etaScale*=sample.eta*sample.eta;
        }
        if(!isfinite(sample.pdf)||!finiteEnergy(sample.weight)||!all(isfinite(sample.wi)))path.padding=1;
        path.throughput.xyz*=sample.weight;
        if(!finiteEnergy(path.throughput.xyz)||!isfinite(path.etaScale)){path.padding=1;valid=false;}path.throughput.w+=1;
        path.origin=float4(offsetRayOrigin(point,surface.geometryNormal,sample.wi),valid?1:0);path.direction=float4(sample.wi,sample.pdf);path.previousDelta=(sample.flags&DELTA)!=0?1:0;
        if(path.throughput.w>=3) {
            float survival=clamp(maxComponent(path.throughput.xyz)*path.etaScale,.05,.95);
            if(rng(seed)>=survival)path.origin.w=0;else path.throughput.xyz/=survival;
        }
    }
    storeMedia(path,media);path.radiance.w=asfloat(seed);
}
