#pragma once
#include "terrain_material.slang"
#include "medium.slang"
#include "water_surface.slang"
#include "environment.slang"
#include "emissive.slang"
float3 rtPreviousSurface(float3 a,float3 b,float3 c,float2 bary,float3 previousTranslation){return a*(1-bary.x-bary.y)+b*bary.x+c*bary.y+previousTranslation;}
struct SurfacePayload {float distance;float2 bary;uint triangle;uint instance;};
#ifndef RT_CPU_FIXTURE
[[vk::binding(0, 0)]] RaytracingAccelerationStructure world;
[[vk::binding(17,0)]] RaytracingAccelerationStructure opaqueWorld;
#else
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum);
#ifdef RT_VISIBILITY_FIXTURE
SurfacePayload fixtureTraceFiltered(float3 origin,float3 direction,float maximum,int category);
#endif
#endif
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(2, 0)]] StructuredBuffer<float4> normals;
uint triangleIndex(uint instance,uint base,uint geometryIndex,uint primitive){return base+asuint(normals[559240u+instance*3+2])[geometryIndex]+primitive;}
struct Camera {float4 inverseClip0;float4 inverseClip1;float4 inverseClip2;float4 inverseClip3;float4 origin;uint width;uint height;uint frame;uint padding;};
[[vk::binding(3, 0)]] ConstantBuffer<Camera> camera;
// 432-byte explicit continuation ABI; eight nested media survive dispatch boundaries.
struct PathState {
    float4 origin,direction,throughput,radiance;
    float4 absorptionIor[8];float4 scatteringPhase[8];uint4 mediumIds[2];
    uint mediumCount;float etaScale;uint previousDelta;uint padding;
    float4 diffuse,reflection,refraction;uint channel;uint reserved0,reserved1,reserved2;
};
// 64-byte hot continuation: radiance stays in output, AOVs are accumulated separately.
struct PathHot {
    float4 origin,direction,throughput;
    uint state;float etaScale;uint seed;uint reserved;
};
struct PathAov {float4 diffuse,reflection,refraction;};
[[vk::binding(16,0)]] RWStructuredBuffer<PathAov> pathAovs;
struct MediumStorage {float4 absorptionIor[8];float4 scatteringPhase[8];uint4 mediumIds[2];};
[[vk::binding(4,0)]] RWStructuredBuffer<PathHot> paths;
[[vk::binding(8,0)]] RWStructuredBuffer<PathHot> paths1;
[[vk::binding(9,0)]] RWStructuredBuffer<PathHot> paths2;
[[vk::binding(10,0)]] RWStructuredBuffer<PathHot> paths3;
[[vk::binding(11,0)]] RWStructuredBuffer<PathHot> paths4;
[[vk::binding(12,0)]] RWStructuredBuffer<PathHot> paths5;
[[vk::binding(13,0)]] RWStructuredBuffer<PathHot> paths6;
[[vk::binding(14,0)]] RWStructuredBuffer<PathHot> paths7;
[[vk::binding(15,0)]] RWStructuredBuffer<MediumStorage> pathMedia;
PathHot loadHot(uint index){uint pixels=camera.width*camera.height,lane=index/pixels,local=index%pixels;switch(lane){
case 0:return paths[local];
case 1:return paths1[local];
case 2:return paths2[local];
case 3:return paths3[local];
case 4:return paths4[local];
case 5:return paths5[local];
case 6:return paths6[local];
case 7:return paths7[local];
}return (PathHot)0;}
PathState loadPath(uint index){
    PathHot hot=loadHot(index);PathState path=(PathState)0;
    path.origin=hot.origin;
    path.direction=hot.direction;
    path.throughput=hot.throughput;
    path.radiance=float4(0,0,0,asfloat(hot.seed));path.reserved0=index;
    path.mediumCount=hot.state&15u;
    path.etaScale=hot.etaScale;
    path.previousDelta=(hot.state>>4)&1u;
    path.padding=(hot.state>>5)&1u;
    path.channel=(hot.state>>6)&3u;
    if(path.mediumCount>0){MediumStorage media=pathMedia[index];
        for(uint i=0;i<path.mediumCount;i++){path.absorptionIor[i]=media.absorptionIor[i];path.scatteringPhase[i]=media.scatteringPhase[i];}
        path.mediumIds[0]=media.mediumIds[0];path.mediumIds[1]=media.mediumIds[1];
    }
    return path;
}
void storePath(uint index,PathState path){PathHot hot;
    hot.origin=path.origin;
    hot.direction=path.direction;
    hot.throughput=path.throughput;
    hot.seed=asuint(path.radiance.w);
    hot.state=path.mediumCount|(path.previousDelta<<4)|(path.padding<<5)|(path.channel<<6);hot.reserved=0;
    hot.etaScale=path.etaScale;
    if(path.mediumCount>0){MediumStorage media=(MediumStorage)0;
        for(uint i=0;i<path.mediumCount;i++){media.absorptionIor[i]=path.absorptionIor[i];media.scatteringPhase[i]=path.scatteringPhase[i];}
        media.mediumIds[0]=path.mediumIds[0];media.mediumIds[1]=path.mediumIds[1];pathMedia[index]=media;
    }
    uint pixels=camera.width*camera.height,lane=index/pixels,local=index%pixels;switch(lane){
case 0:paths[local]=hot;break;
case 1:paths1[local]=hot;break;
case 2:paths2[local]=hot;break;
case 3:paths3[local]=hot;break;
case 4:paths4[local]=hot;break;
case 5:paths5[local]=hot;break;
case 6:paths6[local]=hot;break;
case 7:paths7[local]=hot;break;
}}

#if !defined(RT_CPU_FIXTURE)||defined(RT_QUEUE_FIXTURE)
[[vk::binding(7,0)]] RWStructuredBuffer<uint4> pageFeedback;
#endif
void requestMissPages(float3 origin,float3 direction,uint pathIndex){
#ifndef RT_CPU_FIXTURE
    uint local=pathIndex%(camera.width*camera.height);uint2 pixel=uint2(local%camera.width,local/camera.width);
    if(camera.frame%8!=0||pathIndex>=camera.width*camera.height||pixel.x%16!=0||pixel.y%16!=0)return;
    for(uint step=0;step<4;step++){
        uint index;InterlockedAdd(pageFeedback[0].x,1,index);if(index>=512)return;
        int3 key=int3(floor((origin+direction*(32u<<step))/16));pageFeedback[index+1]=uint4(asuint(key.x),asuint(key.y),asuint(key.z),1);
    }
#endif
}
SurfacePayload traceSurface(float3 origin,float3 direction,float maximum=4096,uint mask=255,bool shadow=false,bool skipOpaque=false) {
#ifdef RT_CPU_FIXTURE
#ifdef RT_VISIBILITY_FIXTURE
    return fixtureTraceFiltered(origin,direction,maximum,skipOpaque?1:0);
#else
    return fixtureTrace(origin,direction,maximum);
#endif
#else
    if(shadow&&(uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[513].x,1,ignored);}
    RayDesc ray;ray.Origin=origin;ray.Direction=direction;ray.TMin=.001;ray.TMax=maximum;
    SurfacePayload hit;hit.distance=-1;hit.bary=0;hit.triangle=0;hit.instance=0;
    TraceRay(world,skipOpaque&&(uint(camera.origin.w)&256u)==0?RAY_FLAG_CULL_OPAQUE:RAY_FLAG_NONE,mask,0,1,0,ray,hit);return hit;
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
    uint local=path.reserved0%(camera.width*camera.height);uint2 pixel=uint2(local%camera.width,local/camera.width);
    if(path.throughput.w==0&&pixel.x==camera.width/2&&pixel.y==camera.height/2&&path.reserved0<camera.width*camera.height)output[camera.width*camera.height*max(1u,camera.padding)+slot]=value;
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
#if !defined(RT_CPU_FIXTURE)||defined(RT_VISIBILITY_FIXTURE)
bool opaqueBlocked(float3 origin,float3 direction,float maximum){
    if((uint(camera.origin.w)&8u)==0)return false;
#ifdef RT_CPU_FIXTURE
    return fixtureTraceFiltered(origin,direction,maximum,3).distance>=0;
#else
#ifndef RT_VIS_BENCH
    if((uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[513].x,1,ignored);InterlockedAdd(pageFeedback[540].x,1,ignored);
        if((uint(camera.origin.w)&512u)!=0){uint slot;InterlockedAdd(pageFeedback[541].x,1,slot);if(slot<256){pageFeedback[544u+slot]=asuint(float4(origin,maximum));pageFeedback[800u+slot]=asuint(float4(direction,0));}}
    }
#endif
    RayDesc ray;ray.Origin=origin;ray.Direction=direction;ray.TMin=.001;ray.TMax=max(.001,maximum);
#ifdef RT_RAY_QUERY
    RayQuery<RAY_FLAG_FORCE_OPAQUE|RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(opaqueWorld,RAY_FLAG_NONE,254,ray);while(query.Proceed()){}
    return query.CommittedStatus()==COMMITTED_TRIANGLE_HIT;
#else
    uint blocked=1;
#ifdef RT_VIS_BENCH
    // Replay remains opaque-only so TraceRay and Query test identical geometry.
    TraceRay(opaqueWorld,RAY_FLAG_FORCE_OPAQUE|RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH|RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,254,3,0,1,ray,blocked);
#else
    // One solid-blocker traversal: opaque hardware hits, alpha cutout, ignore transmission.
    TraceRay(world,RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH|RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,254,4,1,1,ray,blocked);
#endif
    return blocked!=0;
#endif
#endif
}
#endif
// Straight shadow visibility through bounded interfaces, with Fresnel and RGB extinction.
// No secondary TraceRay call in any-hit/closest-hit; recursion remains one.
float3 visibility(float3 origin,float3 direction,MediumStack media,float maximum=4096,bool flameSource=false) {
#if !defined(RT_CPU_FIXTURE)||defined(RT_VISIBILITY_FIXTURE)
    bool fast=!flameSource&&(uint(camera.origin.w)&8u)!=0;
    if(fast&&opaqueBlocked(origin,direction,maximum))return float3(0);
    if(fast&&(uint(camera.origin.w)&16u)==0){
        if(media.count==0)return float3(1);Medium m=media.entries[media.count-1];return expNeg(m.sigmaA+m.sigmaS,maximum);
    }
#endif
    float3 sourceBlock=floor(origin+direction*maximum);
    float3 value=float3(1);
    for(int boundary=0;boundary<24;boundary++) {
        SurfacePayload hit=traceSurface(origin,direction,max(.001,maximum),254,true,
#if defined(RT_CPU_FIXTURE)&&!defined(RT_VISIBILITY_FIXTURE)
            false
#else
            fast
#endif
        );float distance=hit.distance>=0?hit.distance:maximum;
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
#if defined(RT_CPU_FIXTURE)&&!defined(RT_STATE_FIXTURE)
    float3 sum=path.radiance.xyz+contribution;
#else
    float3 sum=output[path.reserved0].xyz+contribution;
#endif
    if(!finiteEnergy(contribution)||!finiteEnergy(sum)){path.padding=1;path.origin.w=0;return;}
#if defined(RT_CPU_FIXTURE)&&!defined(RT_STATE_FIXTURE)
    path.radiance.xyz=sum;
#else
    output[path.reserved0]=float4(sum,1);
#endif
    uint channel=directChannel>=0&&path.throughput.w==0?uint(directChannel):path.channel;
#if defined(RT_CPU_FIXTURE)&&!defined(RT_STATE_FIXTURE)
    if(channel==1)path.reflection.xyz+=contribution;else if(channel==2)path.refraction.xyz+=contribution;else path.diffuse.xyz+=contribution;
#else
    if(channel==1)pathAovs[path.reserved0].reflection.xyz+=contribution;
    else if(channel==2)pathAovs[path.reserved0].refraction.xyz+=contribution;
    else pathAovs[path.reserved0].diffuse.xyz+=contribution;
#endif
}
float4 pathOutput(PathState path){
#if defined(RT_CPU_FIXTURE)&&!defined(RT_STATE_FIXTURE)
    float3 radiance=path.radiance.xyz;
#else
    float3 radiance=output[path.reserved0].xyz;
#endif
    return float4(path.padding==0&&finiteEnergy(radiance)?radiance:float3(0),path.padding==0?1:0);
}
uint directChannel(Material m,float3 n,float3 wi){return dot(n,wi)<0?2:(metal(m)||dielectric(m)?1:0);}
void advance(inout PathState path) {
    if(path.origin.w==0||path.padding!=0)return;
#ifndef RT_CPU_FIXTURE
    if((uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[514u+uint(path.throughput.w)].x,1,ignored);}
#endif
    uint seed=asuint(path.radiance.w);MediumStack media=loadMedia(path);
    SurfacePayload hit=traceSurface(path.origin.xyz,path.direction.xyz,4096,path.throughput.w==0?255:254);
    TerrainSurface surface=(TerrainSurface)0;bool entering=false;
    if(hit.distance>=0) {
        surface=terrainSurface(hit.triangle,hit.bary,hit.instance);entering=dot(surface.geometryNormal,path.direction.xyz)<0;
#ifndef RT_CPU_FIXTURE
        if(path.throughput.w==0&&DispatchRaysIndex().z==0&&(uint(camera.origin.w)&1u)!=0){
            uint local=DispatchRaysIndex().y*camera.width+DispatchRaysIndex().x;
            uint base=camera.width*camera.height*max(1u,camera.padding)+4;
            float3 point=path.origin.xyz+path.direction.xyz*hit.distance;
            output[base+local]=float4(surface.material.baseColor,float(surface.material.type+1));
            output[base+camera.width*camera.height+local]=float4(entering?surface.geometryNormal:-surface.geometryNormal,surface.material.microfacetAlpha);
            output[base+camera.width*camera.height*2+local]=float4(point,hit.distance);
            float4 currentTranslation=normals[559240u+hit.instance*3],previousTranslation=normals[559240u+hit.instance*3+1];
            float3 previousPoint=point;
            if((surface.flags&128)!=0&&previousTranslation.w>0){
                uint offset=67108864u+hit.triangle*120;
                float3 a=asfloat(readWords3(geometry,offset)),b=asfloat(readWords3(geometry,offset+40)),c=asfloat(readWords3(geometry,offset+80));
                previousPoint=rtPreviousSurface(a,b,c,hit.bary,previousTranslation.xyz);
            }
            output[base+camera.width*camera.height*6+local]=float4(previousPoint,(surface.flags&128)!=0?previousTranslation.w:abs(previousTranslation.w));
        }
#endif
        if(!entering&&dielectric(surface.material)&&surface.material.type!=THIN_DIELECTRIC&&media.count==0)media.enter(materialMedium(surface.material));
    }
#if defined(RT_SER) && !defined(RT_CPU_FIXTURE)
    ReorderThread(hit.distance>=0?surface.material.type:15,4);
#endif
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
            uint firstFlame=nearestFlame(event,0xffffffffu),secondFlame=nearestFlame(event,firstFlame);
            for(uint index=0;index<min(3u,flameCount());index++){
                LightSample flame=flameConnection(event,index,seed,firstFlame,secondFlame);
                if(flame.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,flame.wi),m.g)/sample.pdf)*flame.radiance*visibility(event,flame.wi,media,max(.001,flame.distance-.002),true));
            }
            LightSample emitter=sampleEmitter(event,seed);
            if(emitter.pdf>0)addRadiance(path,path.throughput.xyz*expNeg(extinction,sample.distance)*m.sigmaS*(hg(dot(path.direction.xyz,emitter.wi),m.g)/(sample.pdf*emitter.pdf))*emitter.radiance*visibility(event,emitter.wi,media,max(.001,emitter.distance-.002),emitter.kind==3));
        }
        path.throughput.xyz*=expNeg(extinction,distance);
    }
    if(hit.distance<0) {
        requestMissPages(path.origin.xyz,path.direction.xyz,path.reserved0);
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
        uint firstFlame=nearestFlame(point,0xffffffffu),secondFlame=nearestFlame(point,firstFlame);
        for(int connection=0;connection<2+int(min(3u,flameCount()));connection++){
        LightSample light=connection==0?sampleOneLight(point,seed):connection==1?sampleHeldLight(point):flameConnection(point,uint(connection-2),seed,firstFlame,secondFlame);
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

#if !defined(RT_CPU_FIXTURE)||defined(RT_QUEUE_FIXTURE)
bool compactPaths(){return (uint(camera.origin.w)&4u)!=0;}
bool compactBounce(uint bounce){uint mask=(uint(camera.origin.w)>>12)&63u;return compactPaths()&&(mask==0||(mask&(1u<<bounce))!=0);}
void appendContinuation(uint pathIndex,PathState path){
    uint bounce=uint(path.throughput.w);if(!compactBounce(bounce)||path.origin.w==0||path.padding!=0||bounce>=6)return;
#ifdef RT_QUEUE_FIXTURE
    // CPU fixture replaces only GPU atomic reservation; packing/guards are production code.
    uint slot=pageFeedback[520u+bounce].x++;
#else
    uint slot;InterlockedAdd(pageFeedback[520u+bounce].x,1,slot);
#endif
    uint capacity=camera.width*camera.height*max(1u,camera.padding);
    // Each surviving path appends once: slot is bounded by capacity, with no dropping.
    pageFeedback[1568u+(bounce&1u)*capacity+slot]=uint4(pathIndex,0,0,0);
    // The atomic reservation is also the indirect dispatch width: no per-path Max.
}
#endif
