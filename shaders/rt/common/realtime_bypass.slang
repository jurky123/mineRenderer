#pragma once
// Compile-time FULL ABI hooks. No realtime policy/history/cache implementation included.
bool rtPolicy(){return false;}
uint rtProbe(uint index){return 0;}
uint rtHistory(uint index,uint bank){return 0;}
void rtCount(uint row,uint component){
#if !defined(RT_CPU_FIXTURE)
    if(row==0&&(uint(camera.origin.w)&2u)!=0){uint ignored;if(component==0)InterlockedAdd(pageFeedback[1600].x,1,ignored);else if(component==1)InterlockedAdd(pageFeedback[1600].y,1,ignored);}
#endif
}
bool rtFlatNormal(float3 shading,float3 geometric){return false;}
bool rtSparse(inout PathState path,TerrainSurface surface,SurfacePayload hit,float3 point,float3 n,MediumStack media){return false;}
bool rtTail(PathState path,Material m,float3 point,float3 n,MediumStack media,out float3 response,out bool training){response=0;training=false;return false;}
bool rtProbeFirst(PathState path){return false;}
void rtRecordProbe(PathState path,float3 wi,float pdf){}
void rtTrain(uint index){}
void rtSaveHistory(uint index,float3 color,float3 diffuse,float3 reflection,float3 refraction){}
