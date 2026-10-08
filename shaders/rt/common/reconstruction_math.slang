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
