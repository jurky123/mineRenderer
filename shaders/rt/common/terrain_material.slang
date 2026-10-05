#pragma once
#include "bsdf.slang"
#include "surface.slang"
[[vk::binding(5, 0)]] ByteAddressBuffer geometry;
[[vk::binding(6, 0)]] ByteAddressBuffer assets;
// Native RtGeometryStream vertices: p@0, uv@12, n@20, tint@32, flags@36, stride 40.
struct TerrainVertex {float3 p;float2 uv;float3 n;uint tint;uint flags;};
TerrainVertex terrainVertex(uint triangle,uint vertex) {
    uint offset=triangle*120+vertex*40;
    TerrainVertex v;
    v.p=asfloat(geometry.Load3(offset));v.uv=asfloat(geometry.Load2(offset+12));v.n=asfloat(geometry.Load3(offset+20));
    v.tint=geometry.Load(offset+32);v.flags=geometry.Load(offset+36);return v;
}
uint atlasTexel(uint slot,float2 uv) {
    uint4 shape=assets.Load4(slot*16);
    uint2 pixel=min(uint2(saturate(uv)*float2(shape.xy)),shape.xy-1);
    return assets.Load(shape.z+(pixel.y*shape.x+pixel.x)*4);
}
uint paletteWord(uint id,uint plane) { return assets.Load(assets.Load(56)+((id&65535)+plane*65536)*4); }
float linearChannel(float s){return s<=.04045?s/12.92:pow((s+.055)/1.055,2.4);}
float3 linearRgb(uint value){float3 s=float3(value&255,(value>>8)&255,(value>>16)&255)/255.0;return float3(linearChannel(s.x),linearChannel(s.y),linearChannel(s.z));}
float2 terrainUv(TerrainVertex a,TerrainVertex b,TerrainVertex c,float2 bary){return a.uv*(1-bary.x-bary.y)+b.uv*bary.x+c.uv*bary.y;}
bool visibleTexel(uint triangle,float2 bary) {
    TerrainVertex a=terrainVertex(triangle,0);
    if((a.flags&1)==0)return true;
    float2 uv=terrainUv(a,terrainVertex(triangle,1),terrainVertex(triangle,2),bary);
    if(((atlasTexel(1,uv)>>24)&3)!=0)return true; // Glass/water alpha is transmission, not cutout.
    return cutoutVisible(atlasTexel(0,uv)>>24,a.tint>>24,a.flags);
}
struct TerrainSurface {Material material;Frame frame;float3 geometryNormal;uint flags;};
TerrainSurface terrainSurface(uint triangle,float2 bary,uint instance) {
    TerrainVertex a=terrainVertex(triangle,0),b=terrainVertex(triangle,1),c=terrainVertex(triangle,2);
    float2 uv=terrainUv(a,b,c,bary);uint id=atlasTexel(1,uv);uint profile=paletteWord(id,0);
    float3 e1=b.p-a.p,e2=c.p-a.p;float3 ng=safeNormalize(cross(e1,e2));
    float3 n=safeNormalize(a.n*(1-bary.x-bary.y)+b.n*bary.x+c.n*bary.y);
    if(dot(n,n)<.1)n=ng;if(dot(n,ng)<0)n=-n;
    Frame base=makeFrame(n);float3 tangent=base.t,bitangent=base.b;
    float2 du=b.uv-a.uv,dv=c.uv-a.uv;float determinant=du.x*dv.y-du.y*dv.x;
    if(abs(determinant)>1e-10) {
        tangent=(e1*dv.y-e2*du.y)/determinant;bitangent=(e2*du.x-e1*dv.x)/determinant;
        tangent=safeNormalize(tangent-n*dot(tangent,n));bitangent=safeNormalize(bitangent-n*dot(bitangent,n));
        uint packed=atlasTexel(2,uv);float2 xy=float2(packed&255,(packed>>8)&255)/127.5-1;
        n=safeNormalize(tangent*xy.x+bitangent*xy.y+n*sqrt(max(0,1-dot(xy,xy))));
    }
    if(dot(n,ng)<=0)n=ng;
    float3 color=linearRgb(atlasTexel(0,uv))*linearRgb(a.tint);
    Material material=decodeMaterial(id,profile,color,a.flags,uint4(paletteWord(id,1),paletteWord(id,2),paletteWord(id,3),0),paletteWord(id,4),true);
    material.emission=color*((((id>>24)&4)!=0?((id>>16)&255)/254.0:(a.flags>>16)/15.0)*2.4);
    if(((id>>24)&3)==1&&maxComponent(material.sigmaA)==0)material.sigmaA=-log(max(float3(.001),color))*.7;
    if((a.flags&8)!=0&&dielectric(material))material.type=THIN_DIELECTRIC;
    // Minecraft water is continuous across section BLAS boundaries. Its entry and exit
    // must not change volume identity just because the mesh belongs to another section.
    material.mediumId=material.type==WATER?0xfffffffeu:1+(instance<<16)+(material.type<<8)+(uint)round(material.ior*255/3);
    float rain=asfloat(assets.Load(68));
    if(rain>0&&!dielectric(material)&&material.type!=DIFFUSE_TRANSMISSION) {
        float wet=rain*((a.flags>>8)&15)/15.0*smoothstep(.2,.9,ng.y);
        material.coatWeight=max(material.coatWeight,wet*(1-material.porosity)*.6);
        if(wet>0){material.coatIOR=1.333;material.coatAlpha=.025;}
        material.baseColor*=1-wet*material.porosity*.18;
        if(material.coatWeight>0)material.type=metal(material)?COATED_CONDUCTOR:COATED_DIFFUSE;
    }
    TerrainSurface result={material,makeFrame(n,tangent,bitangent),ng,a.flags};return result;
}
