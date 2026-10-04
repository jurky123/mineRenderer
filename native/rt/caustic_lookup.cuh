#pragma once
static __forceinline__ __device__ float3 causticIrradiance(float3 p,float3 n){
 if(params.referenceSpp||params.sun.y<.08f||n.y<.1f)return v(0,0,0);float cell=.25f;int x=(int)floorf(p.x/cell),z=(int)floorf(p.z/cell),slot=(x&63)|((z&63)<<6);auto record=params.caustics+slot*2;
 if(record[0].w<=0||fabsf(record[0].x-x*cell)>.01f||fabsf(record[0].z-z*cell)>.01f||fabsf(record[0].y-p.y)>.2f)return v(0,0,0);
 return v(record[1].x,record[1].y,record[1].z);
}
