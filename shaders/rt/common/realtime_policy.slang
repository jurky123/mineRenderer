#pragma once
// All realtime state lives after the existing continuation queues in pageFeedback.
// Reference and CPU parity fixtures bypass the entire policy.
bool rtPolicy(){
#if defined(RT_CPU_FIXTURE)&&!defined(RT_POLICY_FIXTURE)
    return false;
#else
    return pageFeedback[1568].x!=0;
#endif
}
uint rtCapacity(){return camera.width*camera.height*max(1u,camera.padding);}
uint rtProbe(uint index){return 1648u+rtCapacity()*4+index*3;}
uint rtHistory(uint index,uint bank){return 1648u+rtCapacity()*7+(bank*camera.width*camera.height+index)*8;}
uint rtCacheBase(){return 1648u+rtCapacity()*7+camera.width*camera.height*16;}
uint rtRequestBase(){return rtCacheBase()+655360u;}
uint rtRequest(uint index){return rtRequestBase()+(pageFeedback[rtProbe(index)].w-1u)*6;}
void rtCount(uint row,uint component){
#if !defined(RT_CPU_FIXTURE)
    if((uint(camera.origin.w)&2u)!=0){uint ignored;if(component==0)InterlockedAdd(pageFeedback[1600+row].x,1,ignored);else if(component==1)InterlockedAdd(pageFeedback[1600+row].y,1,ignored);else if(component==2)InterlockedAdd(pageFeedback[1600+row].z,1,ignored);else InterlockedAdd(pageFeedback[1600+row].w,1,ignored);}
#endif
}
// Neutral RG8 normal (127/128) is not exactly zero after signed decoding.
bool rtFlatNormal(float3 shading,float3 geometric){return abs(dot(shading,geometric))>.9999;}
bool rtDiffuse(Material m){return (m.type==DIFFUSE||m.type==ROUGH_DIFFUSE)&&m.coatWeight==0&&m.transmission==0&&m.microfacetAlpha>=.15&&maxComponent(m.emission)==0;}
float4 rtSh(float3 wi){return float4(.2820947918,.4886025119*wi.x,.4886025119*wi.y,.4886025119*wi.z);}
float4 rtIrradianceBasis(float3 n){return rtSh(n)*float4(Pi,2*Pi/3,2*Pi/3,2*Pi/3);}
float3 rtShIrradiance(float4 c0,float4 cx,float4 cy,float4 cz,float3 n){float4 basis=rtIrradianceBasis(n);return max(float3(0),c0.xyz*basis.x+cx.xyz*basis.y+cy.xyz*basis.z+cz.xyz*basis.w)/Pi;}
uint rtNormalBucket(float3 n){float3 a=abs(n);uint axis=a.x>a.y&&a.x>a.z?0:a.y>a.z?1:2;return axis*2+(n[axis]<0?1:0);}
// Four slots per cell bucket, unchanged 65536-slot / 10 MiB allocation.
// Plane coordinates are cell-local; normal-map quantization cannot perturb the key.
uint rtPlaneKey(float3 point,float3 n){int3 cell=int3(floor(point/2));uint bucket=rtNormalBucket(n),axis=bucket/2;return (asuint(int(round((point[axis]-cell[axis]*2)*64)))<<3)|bucket;}
uint rtCacheBucket(float3 point,float3 normal){int3 cell=int3(floor(point/2));uint hash=directHash(asuint(cell.x)^directHash(asuint(cell.y))^directHash(asuint(cell.z))^rtNormalBucket(normal)*0x9e3779b9u);return rtCacheBase()+(hash&16383u)*40;}
bool rtCacheMatches(uint address,float3 point,float3 n){
    uint4 key=pageFeedback[address+1];int3 cell=int3(floor(point/2));
    if(any(asint(key.xyz)!=cell)||key.w!=rtPlaneKey(point,n))return false;
    float4 anchor=asfloat(pageFeedback[address+2]),normal=asfloat(pageFeedback[address+3]);float3 delta=point-anchor.xyz;
    return dot(normal.xyz,n)>=.97&&abs(dot(delta,n))<=.08&&dot(delta,delta)<=2.25;
}
uint rtCacheAddress(float3 point,float3 normal){
    uint base=rtCacheBucket(point,normal),choice=base,oldest=0;
    for(uint way=0;way<4;way++){
        uint address=base+way*10;uint4 meta=pageFeedback[address];
        if(meta.y==pageFeedback[1568].y&&rtCacheMatches(address,point,normal))return address;
        uint age=meta.y!=pageFeedback[1568].y?0xffffffffu:camera.frame-meta.z;
        if(age>oldest){oldest=age;choice=address;}
    }
    return choice;
}
// Axis-aligned quad identity survives its diagonal while retaining owner and exact plane.
// Irregular/slanted geometry keeps triangle identity and conservative matching.
uint rtFaceIdentity(uint owner,uint triangle,float3 a,float3 b,float3 c,float3 point){
    float3 n=safeNormalize(cross(b-a,c-a));uint axis=rtNormalBucket(n)/2;
    if(abs(n[axis])<.99999)return directHash(owner^directHash(triangle));
    int3 tile=int3(floor(point));tile[axis]=int(round(a[axis]*1024));
    return directHash(owner^directHash(asuint(tile.x))^directHash(asuint(tile.y))^directHash(asuint(tile.z))^rtNormalBucket(n)*0x9e3779b9u);
}
uint rtSurfaceIdentity(SurfacePayload hit,float3 point){
#ifdef RT_CPU_FIXTURE
    return hit.triangle;
#else
    TerrainVertex a=terrainVertex(hit.triangle,0),b=terrainVertex(hit.triangle,1),c=terrainVertex(hit.triangle,2);
    float4 pose=normals[559240u+hit.instance*3],previous=normals[559240u+hit.instance*3+1];
    return rtFaceIdentity(uint(abs(previous.w)),hit.triangle,a.p,b.p,c.p,point-pose.xyz);
#endif
}
float rtRelativeVariance(float mean,float variance){return variance/max(.0001,mean*mean);}
// Error of the stored temporal mean, rather than the noise of one path.
uint rtInterval(float confidence,float variance){float error=variance/max(1.,confidence);return confidence>=8&&error<.025?4:confidence>=4&&error<.1?2:1;}
bool rtNear(float3 point){float3 delta=point-camera.origin.xyz;return dot(delta,delta)<1024;}
uint rtTtl(float3 point){return rtNear(point)?128u:256u;}
bool rtSameSurface(float3 point,float3 normal,float3 albedo,uint triangle,uint material,uint epoch,uint address,float distance){
    uint4 id=pageFeedback[address+6];if(id.x!=triangle||id.y!=material||id.z!=epoch){rtCount(9,0);return false;}
    float4 p=asfloat(pageFeedback[address+4]);float footprint=max(.025,distance*3/max(1u,camera.height));
    float3 delta=point-p.xyz;if(!(p.w>0&&dot(delta,delta)<footprint*footprint)){rtCount(9,1);return false;}
    float4 n=asfloat(pageFeedback[address+5]);if(!(dot(normal,n.xyz)>.97)){rtCount(9,2);return false;}
    float4 a=asfloat(pageFeedback[address+7]);
    float difference=maxComponent(abs(albedo-a.xyz));
    bool match=uint(a.w)==DIFFUSE?((all(albedo>=.02)&&all(a.xyz>=.02))||difference<=1e-6):difference<.08;
    if(!match)rtCount(9,3);return match;
}
uint rtPrevious(float3 point,float3 normal,float3 albedo,uint triangle,uint material,float distance){
    if(pageFeedback[1574].w==0)return 0;
    float3 local=point-asfloat(pageFeedback[1574]).xyz;float4 clip=asfloat(pageFeedback[1570])*local.x+asfloat(pageFeedback[1571])*local.y+asfloat(pageFeedback[1572])*local.z+asfloat(pageFeedback[1573]);
    if(clip.w<=0)return 0;float2 uv=clip.xy/clip.w*.5+.5;if(any(uv<0)||any(uv>=1))return 0;
    uint2 pixel=uint2(uv*float2(camera.width,camera.height));uint address=rtHistory(pixel.y*camera.width+pixel.x,(camera.frame+1)&1u);
    uint best=rtSameSurface(point,normal,albedo,triangle,material,pageFeedback[1568].y,address,distance)?address:0;
    float confidence=best>0?asfloat(pageFeedback[best]).w:0;
    // Preserve a mature nearest candidate; search only when it lacks enough observations.
    if(confidence>=8)return best;
    // Jitter may move the matching triangle into an adjacent history pixel.
    // Only search the four texels surrounding the projected pixel center.
    int2 corner=int2(floor(uv*float2(camera.width,camera.height)-.5));
    for(uint tap=0;tap<4;tap++){
        int2 candidate=corner+int2(tap&1u,tap>>1);if(any(candidate<0)||any(candidate>=int2(camera.width,camera.height))||all(candidate==int2(pixel)))continue;
        uint nearby=rtHistory(uint(candidate.y)*camera.width+uint(candidate.x),(camera.frame+1)&1u);
        if(rtSameSurface(point,normal,albedo,triangle,material,pageFeedback[1568].y,nearby,distance)){
            float count=asfloat(pageFeedback[nearby]).w;
            if(count>confidence){confidence=count;best=nearby;}
        }
    }
    if(best>0&&best!=address)rtCount(11,0);return best;
}
bool rtSparse(inout PathState path,TerrainSurface surface,SurfacePayload hit,float3 point,float3 n,MediumStack media){
    if(!rtPolicy()||(pageFeedback[1568].x&2u)==0||camera.padding!=1||path.throughput.w!=0)return false;uint probe=rtProbe(path.reserved0);
    pageFeedback[probe+1]=uint4(0,surface.material.materialId,pageFeedback[1568].y,surface.flags);
    if((surface.flags&128)!=0){rtCount(3,2);rtCount(0,1);return false;}
    if(!rtDiffuse(surface.material)||media.count>0){pageFeedback[probe+1].w|=128u;rtCount(2,media.count>0?2:3);rtCount(0,1);return false;}
    uint identity=rtSurfaceIdentity(hit,point);pageFeedback[probe+1].x=identity;
    pageFeedback[probe+2]=asuint(float4(surface.material.baseColor,surface.material.type));
    uint previous=rtPrevious(point,n,surface.material.baseColor,identity,surface.material.materialId,hit.distance);pageFeedback[probe].z=previous;
    if(previous>0&&(abs(asfloat(pageFeedback[previous+5]).w-surface.material.microfacetAlpha)>.02||asfloat(pageFeedback[previous+7]).w!=surface.material.type))previous=0;pageFeedback[probe].z=previous;
    if(previous==0){rtCount(3,0);rtCount(0,1);return false;}
    float4 color=asfloat(pageFeedback[previous]),diffuse=asfloat(pageFeedback[previous+1]);uint interval=rtInterval(color.w,diffuse.w);
    rtCount(10,color.w<4?0:color.w<8?1:color.w<16?2:3);
    uint age=camera.frame-pageFeedback[previous+6].w;
    if(interval==1){rtCount(0,3);rtCount(7,color.w<4?1:2);}
    if(pageFeedback[1568].z!=0&&interval>2){interval=2;rtCount(7,3);}
    if((pageFeedback[1568].x&2u)==0||age>=interval||((directHash(path.reserved0)&(interval-1))==(camera.frame&(interval-1)))){rtCount(0,1);return false;}
    if(!all(isfinite(color.xyz))){rtCount(3,1);rtCount(0,1);return false;}
    float3 oldAlbedo=asfloat(pageFeedback[previous+7]).xyz;
    bool pureDiffuse=surface.material.type==DIFFUSE;
    bool demodulated=pureDiffuse&&all(oldAlbedo>=.02)&&all(surface.material.baseColor>=.02);
    if(pureDiffuse&&!demodulated&&maxComponent(abs(oldAlbedo-surface.material.baseColor))>1e-6){rtCount(3,1);rtCount(0,1);return false;}
    float3 ratio=demodulated?surface.material.baseColor/oldAlbedo:float3(1);
    output[path.reserved0]=float4(color.xyz*ratio,1);PathAov aov;aov.diffuse=float4(diffuse.xyz*ratio,0);aov.reflection=float4(asfloat(pageFeedback[previous+2]).xyz,0);aov.refraction=float4(asfloat(pageFeedback[previous+3]).xyz,0);pathAovs[path.reserved0]=aov;
    pageFeedback[probe].x=2;path.origin.w=0;rtCount(0,2);return true;
}
// One mutually exclusive reason per rejected query: epoch, count, age, geometry, mean error.
uint rtCacheStatus(uint address,float3 point,float3 n){
    uint4 meta=pageFeedback[address];
    if(meta.y!=pageFeedback[1568].y)return 1;
    if(!rtCacheMatches(address,point,n))return 4;
    if(meta.w<24)return 2;
    if(camera.frame-meta.z>rtTtl(point))return 3;
    if(meta.x!=0){rtCount(11,3);return 4;}
    float4 stats=asfloat(pageFeedback[address+8]);
    if(!all(isfinite(stats.xy))||stats.y<0||rtRelativeVariance(stats.x,stats.y)/meta.w>=.05)return 5;
    return 0;
}
bool rtCacheValid(uint address,float3 point,float3 n){return rtCacheStatus(address,point,n)==0;}
// Claim a cell/plane before tracing its suffix. Claim and coefficient publication use
// separate words: same-frame duplicate paths neither reserve tickets nor train again.
bool rtClaim(uint address,float3 point,float3 n){
    uint old,stamp=camera.frame+1;
    uint prior=pageFeedback[address+9].x;if(prior==stamp){rtCount(8,0);return false;}
#ifdef RT_CPU_FIXTURE
    old=pageFeedback[address].x;pageFeedback[address].x=1;
#else
    InterlockedCompareExchange(pageFeedback[address].x,0,1,old);
#endif
    if(old!=0){rtCount(8,0);return false;}
#ifndef RT_CPU_FIXTURE
    DeviceMemoryBarrier();
#endif
    uint4 meta=pageFeedback[address];bool matching=meta.y==pageFeedback[1568].y&&rtCacheMatches(address,point,n);
    bool claimed=pageFeedback[address+9].x==stamp;
    bool occupied=!matching&&meta.y==pageFeedback[1568].y&&meta.w>0&&camera.frame-meta.z<48;
    if(!claimed&&!occupied){
        if(!matching){pageFeedback[address+1]=uint4(asuint(int3(floor(point/2))),rtPlaneKey(point,n));pageFeedback[address+2]=asuint(float4(point,0));pageFeedback[address+3]=asuint(float4(n,0));pageFeedback[address]=uint4(1,pageFeedback[1568].y,camera.frame,0);}
        pageFeedback[address+9].x=stamp;
    }
#ifndef RT_CPU_FIXTURE
    DeviceMemoryBarrier();uint released;InterlockedExchange(pageFeedback[address].x,0,released);
#else
    pageFeedback[address].x=0;
#endif
    if(claimed){rtCount(8,0);return false;}if(occupied){rtCount(8,1);return false;}rtCount(11,2);return true;
}
// Query once per eligible B1/B2 hit; reserve a bounded number of exact training suffixes.
// Pending training paths never query the cache, preventing recursive cache-only learning.
bool rtTail(PathState path,Material m,float3 point,float3 n,MediumStack media,out float3 response,out bool training){
    response=0;training=false;if(!rtPolicy()||(pageFeedback[1568].x&1u)==0||(path.throughput.w==0&&((pageFeedback[1568].x&4u)==0||m.type!=DIFFUSE))||path.throughput.w>2||media.count>0||m.type!=DIFFUSE||!rtDiffuse(m))return false;
    uint probe=rtProbe(path.reserved0);if((pageFeedback[probe].x&1u)!=0)return false;
    uint address=rtCacheAddress(point,n);rtCount(1,0);uint status=rtCacheStatus(address,point,n);bool valid=status==0;
    if(status>0){if(status<5)rtCount(5,status-1);else rtCount(6,0);
        if(status==4){uint4 key=pageFeedback[address+1];rtCount(8,any(asint(key.xyz)!=int3(floor(point/2)))?2:3);}
    }
    uint refresh=valid?(rtNear(point)?8u:16u):1u;
    // Spread the bounded budget across the image instead of letting the first waves consume it.
    uint budget=max(1u,pageFeedback[1568].w);refresh*=max(1u,(rtCapacity()+budget-1)/budget);
    if((directHash(path.reserved0^camera.frame*1237u)%refresh)==0&&rtClaim(address,point,n)){
        uint ticket;
#ifdef RT_CPU_FIXTURE
        ticket=pageFeedback[1604].x++;
#else
        InterlockedAdd(pageFeedback[1604].x,1,ticket);
#endif
        if(ticket<pageFeedback[1568].w){training=true;pageFeedback[probe].x|=1;pageFeedback[probe].y=uint(path.throughput.w);pageFeedback[probe].w=ticket+1;uint request=rtRequestBase()+ticket*6;pageFeedback[request]=uint4(address,path.reserved0,0,0);pageFeedback[request+4]=asuint(float4(point,0));pageFeedback[request+5]=asuint(float4(n,0));rtCount(2,0);return false;}rtCount(2,1);
    }
    if(!valid){rtCount(1,3);return false;}
    response=rtShIrradiance(asfloat(pageFeedback[address+4]),asfloat(pageFeedback[address+5]),asfloat(pageFeedback[address+6]),asfloat(pageFeedback[address+7]),n);
    if(!finiteEnergy(response)){rtCount(6,1);return false;}rtCount(1,1);return true;
}
bool rtProbeFirst(PathState path){if(!rtPolicy())return false;return (pageFeedback[rtProbe(path.reserved0)].x&1u)!=0&&path.throughput.w==pageFeedback[rtProbe(path.reserved0)].y+1;}
void rtRecordProbe(PathState path,float3 wi,float pdf){
    uint request=rtRequest(path.reserved0);pageFeedback[request+1]=asuint(output[path.reserved0]);pageFeedback[request+2]=asuint(float4(path.throughput.xyz,0));pageFeedback[request+3]=asuint(float4(wi,pdf));
}
void rtTrain(uint index){
    if(!rtPolicy())return;uint p=rtProbe(index);if((pageFeedback[p].x&1u)==0)return;
    uint request=rtRequest(index);float4 color=output[index],prefix=asfloat(pageFeedback[request+1]),beta=asfloat(pageFeedback[request+2]),direction=asfloat(pageFeedback[request+3]);
    if(color.w<.5||any(beta.xyz<=1e-7)||direction.w<=0){rtCount(6,2);return;}float3 radiance=max(float3(0),(color.xyz-prefix.xyz)/beta.xyz);if(!finiteEnergy(radiance)){rtCount(6,2);return;}
    float3 point=asfloat(pageFeedback[request+4]).xyz,n=asfloat(pageFeedback[request+5]).xyz;uint address=pageFeedback[request].x,old;
#ifdef RT_CPU_FIXTURE
    if(address==0)address=rtCacheAddress(point,n);
#endif
#ifdef RT_CPU_FIXTURE
    old=pageFeedback[address].x;pageFeedback[address].x=1;
#else
    InterlockedCompareExchange(pageFeedback[address].x,0,1,old);
#endif
    if(old!=0){rtCount(6,3);return;}
#ifndef RT_CPU_FIXTURE
    DeviceMemoryBarrier();
#endif
    uint4 meta=pageFeedback[address],key=pageFeedback[address+1];int3 cell=int3(floor(point/2));float4 anchor=asfloat(pageFeedback[address+2]),normal=asfloat(pageFeedback[address+3]);
    bool sameCell=meta.y==pageFeedback[1568].y&&rtCacheMatches(address,point,n);
    uint ttl=rtTtl(point);bool matching=sameCell&&meta.w>0&&camera.frame-meta.z<=ttl;
    if(!sameCell&&meta.y==pageFeedback[1568].y&&camera.frame-meta.z<48&&meta.w>0){pageFeedback[address].x=0;rtCount(7,0);return;}
    uint count=matching?min(meta.w+1,pageFeedback[1568].z!=0?64u:128u):1u;float alpha=1.0/count;float4 basis=rtSh(direction.xyz)/direction.w;
    float3 sh[4]={radiance*basis.x,radiance*basis.y,radiance*basis.z,radiance*basis.w};
    for(uint i=0;i<4;i++){float3 previous=matching?asfloat(pageFeedback[address+4+i]).xyz:float3(0);pageFeedback[address+4+i]=asuint(float4(lerp(previous,sh[i],alpha),0));}
    float sample=luminance(rtShIrradiance(float4(sh[0],0),float4(sh[1],0),float4(sh[2],0),float4(sh[3],0),n));float4 stats=matching?asfloat(pageFeedback[address+8]):float4(0);float delta=sample-stats.x;
    pageFeedback[address+8]=asuint(float4(stats.x+alpha*delta,(1-alpha)*(stats.y+alpha*delta*delta),0,0));pageFeedback[address+1]=uint4(asuint(cell),rtPlaneKey(point,n));if(!matching){pageFeedback[address+2]=asuint(float4(point,0));pageFeedback[address+3]=asuint(float4(n,0));}

    pageFeedback[address]=uint4(1,pageFeedback[1568].y,camera.frame,count);
#ifndef RT_CPU_FIXTURE
    DeviceMemoryBarrier();uint released;InterlockedExchange(pageFeedback[address].x,0,released);
#else
    pageFeedback[address].x=0;
#endif
    rtCount(1,2);
}
void rtSaveHistory(uint index,float3 color,float3 diffuse,float3 reflection,float3 refraction){
    if(!rtPolicy()||(pageFeedback[1568].x&2u)==0)return;uint p=rtProbe(index);uint type=uint(asfloat(pageFeedback[p+2]).w);if(pageFeedback[p+1].z==0||(pageFeedback[p+1].w&128u)!=0||(type!=DIFFUSE&&type!=ROUGH_DIFFUSE))return;uint address=rtHistory(index,camera.frame&1u),previous=pageFeedback[p].z;uint base=rtCapacity()+4;
    float4 position=output[base+camera.width*camera.height*2+index],normal=output[base+camera.width*camera.height+index];
    float3 albedo=asfloat(pageFeedback[p+2]).xyz;float confidence=1,mean=luminance(type==DIFFUSE?color/max(float3(.02),albedo):color),variance=0;uint sampleFrame=camera.frame;float alpha=1;
    if(previous>0){float4 old=asfloat(pageFeedback[previous]),moments=asfloat(pageFeedback[previous+2]);if((pageFeedback[p].x&2u)!=0){confidence=old.w;variance=asfloat(pageFeedback[previous+1]).w;mean=moments.w;sampleFrame=pageFeedback[previous+6].w;}else{confidence=min(old.w+1,32.);alpha=1/confidence;float delta=mean-moments.w;float oldVariance=asfloat(pageFeedback[previous+1]).w*max(.0001,moments.w*moments.w);float v=(1-alpha)*(oldVariance+alpha*delta*delta);mean=moments.w+alpha*delta;variance=rtRelativeVariance(mean,v);}}
    if(previous>0&&(pageFeedback[p].x&2u)==0){
        float3 oldAlbedo=asfloat(pageFeedback[previous+7]).xyz;
        float3 ratio=type==DIFFUSE&&all(oldAlbedo>=.02)&&all(albedo>=.02)?albedo/oldAlbedo:float3(1);
        color=lerp(asfloat(pageFeedback[previous]).xyz*ratio,color,alpha);
        diffuse=lerp(asfloat(pageFeedback[previous+1]).xyz*ratio,diffuse,alpha);
        reflection=lerp(asfloat(pageFeedback[previous+2]).xyz,reflection,alpha);
        refraction=lerp(asfloat(pageFeedback[previous+3]).xyz,refraction,alpha);
    }
    pageFeedback[address]=asuint(float4(color,confidence));pageFeedback[address+1]=asuint(float4(diffuse,variance));pageFeedback[address+2]=asuint(float4(reflection,mean));pageFeedback[address+3]=asuint(float4(refraction,0));pageFeedback[address+4]=asuint(position);pageFeedback[address+5]=asuint(normal);pageFeedback[address+6]=uint4(pageFeedback[p+1].xyz,sampleFrame);pageFeedback[address+7]=pageFeedback[p+2];rtCount(3,3);
}
