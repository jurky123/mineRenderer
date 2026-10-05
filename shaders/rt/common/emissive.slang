#pragma once
// Sorted global triangle IDs; CDF uses area times native emission as a proposal.
uint emitterCount(){return assets.Load(124);}
uint emitterAddress(uint i){return assets.Load(156)+i*64;}
float emitterTotal(){uint n=emitterCount();return n>0?asfloat(assets.Load(emitterAddress(n-1)+12)):0;}
float emitterPdf(uint address,float3 point,float3 target){
    float4 a=asfloat(assets.Load4(address)),b=asfloat(assets.Load4(address+16)),c=asfloat(assets.Load4(address+32));
    float3 delta=target-point;float d2=dot(delta,delta);float cosine=abs(dot(safeNormalize(cross(b.xyz-a.xyz,c.xyz-a.xyz)),safeNormalize(delta)));
    return cosine>1e-7&&d2>1e-8?c.w/emitterTotal()/b.w*d2/cosine:0;
}
LightSample sampleEmitter(float3 point,inout uint seed){
    LightSample light=(LightSample)0;uint count=emitterCount();if(count==0)return light;
    float mass=rng(seed)*emitterTotal();uint lo=0,hi=count-1;
    while(lo<hi){uint mid=(lo+hi)/2;if(asfloat(assets.Load(emitterAddress(mid)+12))<mass)lo=mid+1;else hi=mid;}
    uint address=emitterAddress(lo);if(assets.Load(address+56)==1){
        float3 source=asfloat(assets.Load3(address));float3 delta=source-point;light.distance=length(delta);
        if(light.distance<.025)return light;light.wi=delta/light.distance;light.pdf=asfloat(assets.Load(address+44))/emitterTotal();
        light.radiance=asfloat(assets.Load3(address+16))/(light.distance*light.distance);light.kind=3;return light;
    }
    float root=sqrt(rng(seed));float2 bary=float2(root*(1-rng(seed)),0);bary.y=root-bary.x;
    float3 a=asfloat(assets.Load3(address)),b=asfloat(assets.Load3(address+16)),c=asfloat(assets.Load3(address+32));
    float3 target=a*(1-root)+b*bary.x+c*bary.y;uint2 ids=assets.Load2(address+48);
    if(!visibleTexel(ids.x,bary))return light;
    light.distance=length(target-point);light.wi=safeNormalize(target-point);light.pdf=emitterPdf(address,point,target);
    light.radiance=terrainSurface(ids.x,bary,ids.y).material.emission;light.kind=4;return light;
}
float emitterHitPdf(uint triangle,float3 point,float3 target){
    uint lo=0,hi=emitterCount();while(lo<hi){uint mid=(lo+hi)/2;uint id=assets.Load(emitterAddress(mid)+48);if(id<triangle)lo=mid+1;else hi=mid;}
    return lo<emitterCount()&&assets.Load(emitterAddress(lo)+48)==triangle&&assets.Load(emitterAddress(lo)+56)==0?emitterPdf(emitterAddress(lo),point,target):0;
}
