#pragma once
#include "terrain_material.slang"
#include "medium.slang"
#include "water_surface.slang"
#include "environment.slang"
#include "emissive.slang"
struct SurfacePayload {float distance;float2 bary;uint triangle;uint instance;};
#ifndef RT_CPU_FIXTURE
[[vk::binding(0, 0)]] RaytracingAccelerationStructure world;
#else
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum);
#endif
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(2, 0)]] StructuredBuffer<float4> normals;
struct Camera {float4 inverseClip0;float4 inverseClip1;float4 inverseClip2;float4 inverseClip3;float4 origin;uint width;uint height;uint frame;uint padding;};
[[vk::binding(3, 0)]] ConstantBuffer<Camera> camera;
// 432-byte explicit continuation ABI; eight nested media survive dispatch boundaries.
struct PathState {
    float4 origin,direction,throughput,radiance;
    float4 absorptionIor[8];float4 scatteringPhase[8];uint4 mediumIds[2];
    uint mediumCount;float etaScale;uint previousDelta;uint padding;
    float4 diffuse,reflection,refraction;uint channel;uint reserved0,reserved1,reserved2;
};
[[vk::binding(4, 0)]] RWStructuredBuffer<PathState> paths;
[[vk::binding(8,0)]] RWStructuredBuffer<PathState> paths1;
[[vk::binding(9,0)]] RWStructuredBuffer<PathState> paths2;
[[vk::binding(10,0)]] RWStructuredBuffer<PathState> paths3;
[[vk::binding(11,0)]] RWStructuredBuffer<PathState> paths4;
[[vk::binding(12,0)]] RWStructuredBuffer<PathState> paths5;
[[vk::binding(13,0)]] RWStructuredBuffer<PathState> paths6;
[[vk::binding(14,0)]] RWStructuredBuffer<PathState> paths7;
PathState loadPath(uint index){uint pixels=camera.width*camera.height,lane=index/pixels,local=index%pixels;switch(lane){
case 0:return paths[local];
case 1:return paths1[local];
case 2:return paths2[local];
case 3:return paths3[local];
case 4:return paths4[local];
case 5:return paths5[local];
case 6:return paths6[local];
case 7:return paths7[local];
}return (PathState)0;}
void storePath(uint index,PathState path){uint pixels=camera.width*camera.height,lane=index/pixels,local=index%pixels;switch(lane){
case 0:paths[local]=path;break;
case 1:paths1[local]=path;break;
case 2:paths2[local]=path;break;
case 3:paths3[local]=path;break;
case 4:paths4[local]=path;break;
case 5:paths5[local]=path;break;
case 6:paths6[local]=path;break;
case 7:paths7[local]=path;break;
}}

#ifndef RT_CPU_FIXTURE
[[vk::binding(7,0)]] RWStructuredBuffer<uint4> pageFeedback;
#endif
void requestMissPages(float3 origin,float3 direction){
#ifndef RT_CPU_FIXTURE
    uint2 pixel=DispatchRaysIndex().xy;
    if(camera.frame%8!=0||DispatchRaysIndex().z!=0||pixel.x%16!=0||pixel.y%16!=0)return;
    for(uint step=0;step<4;step++){
        uint index;InterlockedAdd(pageFeedback[0].x,1,index);if(index>=512)return;
        int3 key=int3(floor((origin+direction*(32u<<step))/16));pageFeedback[index+1]=uint4(asuint(key.x),asuint(key.y),asuint(key.z),1);
    }
#endif
}
SurfacePayload traceSurface(float3 origin,float3 direction,float maximum=4096) {
#ifdef RT_CPU_FIXTURE
    return fixtureTrace(origin,direction,maximum);
#else
    RayDesc ray;ray.Origin=origin;ray.Direction=direction;ray.TMin=.001;ray.TMax=maximum;
    SurfacePayload hit;hit.distance=-1;hit.bary=0;hit.triangle=0;hit.instance=0;
    TraceRay(world,RAY_FLAG_NONE,255,0,0,0,ray,hit);return hit;
#endif
}
// Guard finite light connections against rounding back onto the receiver.
float3 connectionOrigin(float3 point,float3 normal,float3 direction){
    float3 facing=dot(normal,direction)>=0?normal:-normal;
    float margin=max(.002,maxComponent(abs(point))*9.5367431640625e-7);
    return offsetRayOrigin(point+facing*margin,normal,direction);
}
void heldProbe(PathState path,uint slot,float4 value){
#ifndef RT_CPU_FIXTURE
    uint2 pixel=DispatchRaysIndex().xy;
    if(path.throughput.w==0&&pixel.x==camera.width/2&&pixel.y==camera.height/2&&DispatchRaysIndex().z==0)output[camera.width*camera.height*max(1u,camera.padding)+slot]=value;
#endif
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
float3 visibility(float3 origin,float3 direction,MediumStack media,float maximum=4096,bool flameSource=false) {
    float3 sourceBlock=floor(origin+direction*maximum);
    float3 value=float3(1);
    for(int boundary=0;boundary<24;boundary++) {
        SurfacePayload hit=traceSurface(origin,direction,max(.001,maximum));float distance=hit.distance>=0?hit.distance:maximum;
        if(media.count>0){Medium m=media.entries[media.count-1];value*=expNeg(m.sigmaA+m.sigmaS,distance);}
        if(hit.distance<0)return value;
        TerrainSurface surface=terrainSurface(hit.triangle,hit.bary,hit.instance);Material m=surface.material;
        // The virtual flame point represents the entire source model. Its own stem
        // must not stamp a square umbra; other blocks and other torch bodies still occlude.
        float3 point=origin+direction*hit.distance;
        float3 owner=floor(point-terrainVertex(hit.triangle,0).n*.001);
        if(flameSource&&(surface.flags&32)!=0&&all(owner==sourceBlock)){
            float advanceDistance=hit.distance+.002;origin+=direction*advanceDistance;maximum-=advanceDistance;
            if(maximum<=.001)return value;continue;
        }
        if(!dielectric(m)&&m.type!=DIFFUSE_TRANSMISSION)return float3(0);
        bool entering=dot(surface.geometryNormal,direction)<0;
        float eta=entering?m.ior/media.ior():media.outside(m.mediumId)/media.ior();
        float F=fresnelDielectric(dot(surface.geometryNormal,direction),eta);
        if(m.type==THIN_DIELECTRIC){F=2*F/(1+F);value*=expNeg(m.sigmaA,m.thickness/max(.01,abs(dot(surface.geometryNormal,direction))));}
        value*=m.type==DIFFUSE_TRANSMISSION?m.baseColor*m.transmission:float3((1-F)*m.transmission);
        if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&!crossMedium(media,m,entering))return float3(0);
        if(maxComponent(value)<1e-6)return float3(0);
        float3 next=offsetRayOrigin(point,surface.geometryNormal,direction);
        maximum-=hit.distance+max(0,dot(next-point,direction));if(maximum<=.001)return value;origin=next;
    }
    return float3(0); // Conservative interface-limit handling.
}
bool finiteEnergy(float3 value){return all(isfinite(value))&&all(value>=0);}
void addRadiance(inout PathState path,float3 contribution,int directChannel=-1){
    float3 sum=path.radiance.xyz+contribution;
    if(!finiteEnergy(contribution)||!finiteEnergy(sum)){path.padding=1;path.origin.w=0;return;}
    path.radiance.xyz=sum;
    uint channel=directChannel>=0&&path.throughput.w==0?uint(directChannel):path.channel;
    if(channel==1)path.reflection.xyz+=contribution;else if(channel==2)path.refraction.xyz+=contribution;else path.diffuse.xyz+=contribution;
}
float4 pathOutput(PathState path){return float4(path.padding==0&&finiteEnergy(path.radiance.xyz)?path.radiance.xyz:float3(0),path.padding==0?1:0);}
uint directChannel(Material m,float3 n,float3 wi){return dot(n,wi)<0?2:(metal(m)||dielectric(m)?1:0);}
void primaryGuide(PathState path,float3 point,Material m,float3 normal){
#ifndef RT_CPU_FIXTURE
    if(path.throughput.w==0&&DispatchRaysIndex().z==0){
        uint pixels=camera.width*camera.height,index=DispatchRaysIndex().y*camera.width+DispatchRaysIndex().x;
        uint base=pixels*max(1u,camera.padding)+4;
        output[base+index]=float4(m.baseColor,m.type+1);
        output[base+pixels+index]=float4(normal,m.microfacetAlpha);
        output[base+pixels*2+index]=float4(point,length(point-camera.origin.xyz));
    }
#endif
}
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
            for(uint index=0;index<min(3u,flameCount());index++){
                LightSample flame=flameConnection(event,index,seed);
                if(flame.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,flame.wi),m.g)/sample.pdf)*flame.radiance*visibility(event,flame.wi,media,max(.001,flame.distance-.002),true));
            }
            LightSample emitter=sampleEmitter(event,seed);
            if(emitter.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,emitter.wi),m.g)/(sample.pdf*emitter.pdf))*emitter.radiance*visibility(event,emitter.wi,media,max(.001,emitter.distance-.002),emitter.kind==3));
        }
        path.throughput.xyz*=expNeg(extinction,distance);
    }
    if(hit.distance<0) {
        requestMissPages(path.origin.xyz,path.direction.xyz);
        addRadiance(path,path.throughput.xyz*missRadiance(path.direction.xyz,path.direction.w,path.previousDelta!=0));path.origin.w=0;
    } else {
        Material m=surface.material;float3 point=path.origin.xyz+path.direction.xyz*hit.distance;
        if(m.type==WATER) {
            float4 controls=asfloat(assets.Load4(64));
            float3 waterNormal=terrainWaterNormal(surface.frame.n,point,camera.origin.xyz,controls.x,controls.z,asfloat(assets.Load(80)),controls.y,controls.w);
            if(dot(waterNormal,surface.geometryNormal)>0)surface.frame=makeFrame(waterNormal,surface.frame.t,surface.frame.b);
        }
        primaryGuide(path,point,m,surface.frame.n);
        float3 wo=-path.direction.xyz;float3 n=dot(surface.frame.n,wo)>0?surface.frame.n:-surface.frame.n;
        Frame frame=makeFrame(n,surface.frame.t,surface.frame.b);
        float eta=entering?m.ior/media.ior():media.outside(m.mediumId)/media.ior();
        float emissionMis=path.previousDelta!=0||path.throughput.w==0?1:powerHeuristic(path.direction.w,emitterHitPdf(hit.triangle,path.origin.xyz,point));
        addRadiance(path,path.throughput.xyz*m.emission*emissionMis);
        // One stochastic sky/sun sample plus an independent held delta connection.
        for(int connection=0;connection<2+int(min(3u,flameCount()));connection++){
        LightSample light=connection==0?sampleOneLight(point,seed):connection==1?sampleHeldLight(point):flameConnection(point,uint(connection-2),seed);
        BsdfEval direct=(BsdfEval)0;if(light.pdf>0)direct=evalBsdf(m,frame,wo,light.wi,eta);
        if(connection==1){heldProbe(path,0,float4(light.radiance,light.distance));heldProbe(path,1,float4(direct.f,direct.pdf));heldProbe(path,2,float4(dot(n,light.wi),dot(surface.geometryNormal,light.wi),dot(n,wo),dot(surface.geometryNormal,wo)));}
        if(light.pdf>0&&direct.pdf>0&&validShadingHemisphere(surface.geometryNormal,n,wo,light.wi)) {
            MediumStack shadowMedia=media;bool visible=true;
            if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&dot(n,light.wi)<0)visible=crossMedium(shadowMedia,m,entering);
            float mis=lightMisWeight(light,direct.pdf,path.throughput.w<5);
            float3 start=connectionOrigin(point,surface.geometryNormal,light.wi);
            float maximum=light.distance-max(0,dot(start-point,light.wi))-.002;
            float3 transmittance=visible&&maximum>.001?visibility(start,light.wi,shadowMedia,maximum,connection>=2):float3(0);
            if(connection==1)heldProbe(path,3,float4(transmittance,visible?1:0));
            if(visible)addRadiance(path,path.throughput.xyz*direct.f*(abs(dot(n,light.wi))*mis/light.pdf)*light.radiance*transmittance,int(directChannel(m,n,light.wi)));
        }
        }
        LightSample emitter=sampleEmitter(point,seed);
        if(emitter.pdf>0){
            BsdfEval e=evalBsdf(m,frame,wo,emitter.wi,eta);
            MediumStack shadowMedia=media;bool visible=true;
            if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&dot(n,emitter.wi)<0)visible=crossMedium(shadowMedia,m,entering);
            if(visible&&e.pdf>0&&validShadingHemisphere(surface.geometryNormal,n,wo,emitter.wi)){
                float mis=emitter.kind==3||path.throughput.w>=5?1:powerHeuristic(emitter.pdf,e.pdf);
                float3 start=connectionOrigin(point,surface.geometryNormal,emitter.wi);
                float maximum=emitter.distance-max(0,dot(start-point,emitter.wi))-.002;
                if(maximum>.001)addRadiance(path,path.throughput.xyz*e.f*(abs(dot(n,emitter.wi))*mis/emitter.pdf)*emitter.radiance*visibility(start,emitter.wi,shadowMedia,maximum,emitter.kind==3),int(directChannel(m,n,emitter.wi)));
            }
        }
        if(path.throughput.w>=5) {path.origin.w=0;storeMedia(path,media);path.radiance.w=asfloat(seed);return;}
        BsdfSample sample=sampleBsdf(m,frame,wo,seed,eta);
        bool valid=isfinite(sample.pdf)&&finiteEnergy(sample.weight)&&all(isfinite(sample.wi))&&sample.pdf>0&&validShadingHemisphere(surface.geometryNormal,n,wo,sample.wi);
        if(valid&&(sample.flags&TRANSMISSION)!=0&&dielectric(m)&&m.type!=THIN_DIELECTRIC) {
            valid=crossMedium(media,m,entering);path.etaScale*=sample.eta*sample.eta;
        }
        if(!isfinite(sample.pdf)||!finiteEnergy(sample.weight)||!all(isfinite(sample.wi)))path.padding=1;
        if(path.throughput.w==0)path.channel=(sample.flags&TRANSMISSION)!=0?2:(sample.flags&(GLOSSY|SPECULAR))!=0?1:0;
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
