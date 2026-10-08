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
    v.p=asfloat(readWords3(geometry,offset));v.uv=asfloat(geometry.Load2(offset+12));v.n=asfloat(readWords3(geometry,offset+20));
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
uint dynamicTexel(uint flags,float2 uv){uint slot=flags>>20,dimension=assets.Load(204);uint2 pixel=min(uint2(saturate(uv)*dimension),uint2(dimension-1));return assets.Load(assets.Load(200)+(slot*dimension*dimension+pixel.y*dimension+pixel.x)*4);}
 bool visibleTexel(uint triangle,float2 bary) {
    TerrainVertex a=terrainVertex(triangle,0);
    if((a.flags&1)==0)return true;
    float2 uv=terrainUv(a,terrainVertex(triangle,1),terrainVertex(triangle,2),bary);
    if((a.flags&128)!=0)return cutoutVisible(dynamicTexel(a.flags,uv)>>24,a.tint>>24,a.flags);
    if(((atlasTexel(1,uv)>>24)&3)!=0)return true; // Glass/water alpha is transmission, not cutout.
    return cutoutVisible(atlasTexel(0,uv)>>24,a.tint>>24,a.flags);
}
// Light proposals need radiance only, not BSDF, wetness, normal maps or a tangent frame.
float3 terrainEmission(uint triangle,float2 bary){
    uint base=triangle*120,flags=geometry.Load(base+36);if((flags&128u)!=0)return float3(0);
    float2 uv=asfloat(geometry.Load2(base+12))*(1-bary.x-bary.y)+asfloat(geometry.Load2(base+52))*bary.x+asfloat(geometry.Load2(base+92))*bary.y;
    uint id=atlasTexel(1,uv);if((flags&32u)!=0&&((id>>24)&4u)==0)return float3(0);
    float strength=(((id>>24)&4u)!=0?((id>>16)&255u)/254.0:(flags>>16)/15.0)*8.0;
    if(strength==0)return float3(0);
    return linearRgb(atlasTexel(0,uv))*linearRgb(geometry.Load(base+32))*strength;
}
struct TerrainSurface {Material material;Frame frame;float3 geometryNormal;uint flags;};
TerrainSurface terrainSurface(uint triangle,float2 bary,uint instance,bool deferFrame,out bool deferred) {
    deferred=false;
    TerrainVertex a=terrainVertex(triangle,0),b=terrainVertex(triangle,1),c=terrainVertex(triangle,2);
    float2 uv=terrainUv(a,b,c,bary);
    if((a.flags&128)!=0){Material m=(Material)0;m.type=DIFFUSE;m.baseColor=linearRgb(dynamicTexel(a.flags,uv))*linearRgb(a.tint);m.microfacetAlpha=.5;m.ior=1.5;m.mediumId=1+instance;float3 normal=safeNormalize(a.n*(1-bary.x-bary.y)+b.n*bary.x+c.n*bary.y);float3 ng=safeNormalize(cross(b.p-a.p,c.p-a.p));if(dot(normal,normal)<.1)normal=ng;if(dot(normal,ng)<0)normal=-normal;TerrainSurface result={m,makeFrame(normal),ng,a.flags};return result;}
    uint id=atlasTexel(1,uv);uint profile=paletteWord(id,0);
    float3 e1=b.p-a.p,e2=c.p-a.p;float3 ng=safeNormalize(cross(e1,e2));
    float3 n=safeNormalize(a.n*(1-bary.x-bary.y)+b.n*bary.x+c.n*bary.y);
    if(dot(n,n)<.1)n=ng;if(dot(n,ng)<0)n=-n;
    float3 color=linearRgb(atlasTexel(0,uv))*linearRgb(a.tint);
    Material material=decodeMaterial(id,profile,color,a.flags,uint4(paletteWord(id,1),paletteWord(id,2),paletteWord(id,3),0),paletteWord(id,4),true);
    material.emission=color*((((id>>24)&4)!=0?((id>>16)&255)/254.0:(a.flags>>16)/15.0)*8.0);
    // Flame sources illuminate through the separate point proposal. Wood/metal is reflective.
    // Only explicit LabPBR emission can override this native flame-body rule.
    if((a.flags&32)!=0&&((id>>24)&4)==0)material.emission=float3(0);
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
    // A reusable flat diffuse guide does not need UV tangent construction. Exact paths
    // complete the original frame after the history decision; textured normals stay exact.
    if(deferFrame&&(material.type==DIFFUSE||material.type==ROUGH_DIFFUSE)&&material.coatWeight==0&&material.transmission==0&&material.microfacetAlpha>=.15&&maxComponent(material.emission)==0&&dot(n,ng)>.99999){
        uint packed=atlasTexel(2,uv);float2 xy=float2(packed&255,(packed>>8)&255)/127.5-1;deferred=dot(xy,xy)<.0001;
    }
    if(deferred){TerrainSurface result={material,makeFrame(n),ng,a.flags};return result;}
    Frame base=makeFrame(n);float3 tangent=base.t,bitangent=base.b;
    float2 du=b.uv-a.uv,dv=c.uv-a.uv;float determinant=du.x*dv.y-du.y*dv.x;
    if(abs(determinant)>1e-10) {
        tangent=(e1*dv.y-e2*du.y)/determinant;bitangent=(e2*du.x-e1*dv.x)/determinant;
        tangent=safeNormalize(tangent-n*dot(tangent,n));bitangent=safeNormalize(bitangent-n*dot(bitangent,n));
        uint packed=atlasTexel(2,uv);float2 xy=float2(packed&255,(packed>>8)&255)/127.5-1;
        n=safeNormalize(tangent*xy.x+bitangent*xy.y+n*sqrt(max(0,1-dot(xy,xy))));
    }
    if(dot(n,ng)<=0)n=ng;
    TerrainSurface result={material,makeFrame(n,tangent,bitangent),ng,a.flags};return result;
}

TerrainSurface terrainSurface(uint triangle,float2 bary,uint instance){bool deferred;return terrainSurface(triangle,bary,instance,false,deferred);}
Frame terrainCompleteFrame(uint triangle,float2 bary){
    TerrainVertex a=terrainVertex(triangle,0),b=terrainVertex(triangle,1),c=terrainVertex(triangle,2);
    float2 uv=terrainUv(a,b,c,bary);float3 e1=b.p-a.p,e2=c.p-a.p;
    float3 ng=safeNormalize(cross(e1,e2)),n=safeNormalize(a.n*(1-bary.x-bary.y)+b.n*bary.x+c.n*bary.y);
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
    return makeFrame(n,tangent,bitangent);
}
