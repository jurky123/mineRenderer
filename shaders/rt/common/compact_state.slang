#pragma once
struct PackedPath48 {float3 origin;uint direction;float3 throughput;float pdf;uint state;float etaScale;uint seed;uint reserved;};
uint packPathDirection(float3 direction){
    float3 n=direction/max(1e-20,abs(direction.x)+abs(direction.y)+abs(direction.z));
    float2 p=n.xy;if(n.z<0)p=(1-abs(p.yx))*float2(p.x>=0?1:-1,p.y>=0?1:-1);
    uint2 q=uint2(round(saturate(p*.5+.5)*65535));return q.x|(q.y<<16);
}
float3 unpackPathDirection(uint word){
    float2 p=float2(word&65535,word>>16)*(2.0/65535)-1;
    float3 n=float3(p,1-abs(p.x)-abs(p.y));float t=max(0,-n.z);
    n.x+=n.x>=0?-t:t;n.y+=n.y>=0?-t:t;return normalize(n);
}
