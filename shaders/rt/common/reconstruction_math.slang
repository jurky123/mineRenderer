#pragma once
// Reflection through a planar interface is division-free, including grazing views.
float3 rrMirror(float3 endpoint,float3 planePoint,float3 unitNormal){return endpoint-2*unitNormal*dot(endpoint-planePoint,unitNormal);}
float3 rrTransmit(float3 incident,float3 facingNormal,float eta){
    float c=-dot(incident,facingNormal),k=1-eta*eta*(1-c*c);
    return k>=0?normalize(eta*incident+(eta*c-sqrt(k))*facingNormal):float3(0);
}
// Split-sum directional hemispherical GGX approximation (linear RGB reflectance).
// Roughness is sqrt(alpha), matching the common RR example convention.
float3 rrSpecularReflectance(float3 f0,float alpha,float cosine){
    float roughness=sqrt(clamp(alpha,0,1));
    float4 r=roughness*float4(-1,-.0275,-.572,.022)+float4(1,.0425,1.04,-.04);
    float a=min(r.x*r.x,exp2(-9.28*clamp(cosine,0,1)))*r.x+r.y;
    float2 ab=float2(-1.04,1.04)*a+r.zw;
    return clamp(f0*ab.x+ab.y*clamp(f0.y*50,0,1),0,1);
}

// 32B transient record: exact integer identity and barycentric float bit patterns.
struct GuideHitRecord {uint4 hit;uint4 meta;};
GuideHitRecord rrHitRecord(float distance,float2 bary,uint triangle,uint instance,uint frame,uint flags){GuideHitRecord r;r.hit=uint4(asuint(distance),asuint(bary),triangle);r.meta=uint4(instance,frame,flags,0);return r;}
bool rrHitValid(GuideHitRecord record,uint frame){return record.meta.y==frame&&(record.meta.z&1u)!=0;}
uint rrEligibility(float alpha,bool metallic,bool glass,float f0){uint flags=1;if(alpha<=.25&&(metallic||glass||f0>0))flags|=2;if(glass){flags|=8;if(alpha<=.04)flags|=4;}return flags;}
