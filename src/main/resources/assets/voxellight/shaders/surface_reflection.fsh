#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneDepth;uniform sampler2D CurrentHdr;uniform sampler2D SceneHzb;
uniform sampler2D MaterialNormal;uniform sampler2D MaterialPbr;uniform sampler2D MaterialTable;uniform sampler2D MaterialAlbedo;uniform sampler2D MaterialEmission;
layout(std140) uniform SurfaceSettings{vec4 ReflectionControls;vec4 ReflectionTrace;};
layout(std140) uniform PbrSettings {vec4 PbrControls;};
layout(std140) uniform LightingEnvironment {vec4 DirectColorStrength;vec4 SkyColorStrength;vec4 HorizonColorLower;};
layout(std140) uniform Projection {mat4 ProjMat;};
layout(std140) uniform ShadowResolveSettings {mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;mat4 InvProjection;mat4 LightNormalMatrix[3];mat4 TerrainLightMatrix[3];mat4 TerrainNormalMatrix[3];mat4 NextLightMatrix[3];mat4 NextNormalMatrix[3];vec4 EpochBlend;};
// VOXELLIGHT_ENVIRONMENT_FUNCTIONS
layout(location=0) in vec2 texCoord;layout(location=0) out vec4 fragColor;
vec3 waterPosition(vec2 uv,float depth){vec4 p=InvProjection*vec4(uv*2.0-1.0,depth,1);return (ViewToWorld*vec4(p.xyz/p.w,1)).xyz;}
bool reflectionGuide(vec3 point,out vec2 uv,out float gap) {
    vec3 view=transpose(mat3(ViewToWorld))*point;
    vec4 clip=ProjMat*vec4(view,1);
    if(clip.w<=.001)return false;
    uv=clip.xy/clip.w*.5+.5;
    if(any(lessThan(uv,vec2(.002))) || any(greaterThan(uv,vec2(.998))))return false;
    float depth=texture(SceneDepth,uv).r;
    if(depth<=0.0)return false;
    vec3 hit=waterPosition(uv,depth);
    gap=length(point)-length(hit);
    return true;
}
vec4 linearReflection(vec3 surface,vec3 normal,vec3 direction) {
    int steps=int(ReflectionControls.x);
    if(steps<=0)return vec4(0);
    vec3 origin=surface+normal*.08;
    float previousT=0.0,previousGap=-1e6;
    bool previousValid=false;
    for(int i=1;i<=32;i++) {
        if(i>steps)break;
        float fraction=float(i)/float(steps);
        float t=.15+47.85*fraction*fraction;
        vec2 uv;float gap;
        if(!reflectionGuide(origin+direction*t,uv,gap))return vec4(0);
        if(previousValid && previousGap<0.0 && gap>=0.0) {
            float lo=previousT,hi=t;
            for(int refine=0;refine<5;refine++) {
                float middle=(lo+hi)*.5;vec2 refinedUv;float refinedGap;
                if(!reflectionGuide(origin+direction*middle,refinedUv,refinedGap))return vec4(0);
                if(refinedGap>=0.0)hi=middle;else lo=middle;
            }
            if(!reflectionGuide(origin+direction*hi,uv,gap))return vec4(0);
            vec4 hdr=texture(CurrentHdr,uv);
            vec3 hit=waterPosition(uv,texture(SceneDepth,uv).r);
            float distance=length(hit),tolerance=max(.12,distance*.004);
            if(hdr.a<=0.0 || gap<0.0 || gap>tolerance)return vec4(0);
            float border=min(min(uv.x,1.0-uv.x),min(uv.y,1.0-uv.y));
            float confidence=smoothstep(.005,.08,border)*(1.0-smoothstep(36.0,48.0,hi))
                *(1.0-smoothstep(tolerance*.5,tolerance,gap));
            return vec4(hdr.rgb,confidence);
        }
        previousValid=true;previousGap=gap;previousT=t;
    }
    return vec4(0);
}
// Solve the ray's projected crossing of a screen-cell boundary (perspective, not linear UV).
float boundaryTime(float originAxis,float rayAxis,float originW,float rayW,float uv,float current) {
    float ndc=uv*2.0-1.0,denominator=rayAxis-ndc*rayW;
    if(abs(denominator)<1e-9)return 48.0;
    float crossing=(ndc*originW-originAxis)/denominator;
    return crossing>current?min(crossing,48.0):48.0;
}
vec4 hzbReflection(vec3 surface,vec3 normal,vec3 direction) {
    if(ReflectionControls.x<=0.0)return vec4(0);
    vec3 origin=surface+normal*.08;
    mat3 rotation=transpose(mat3(ViewToWorld));
    vec4 start=ProjMat*vec4(rotation*origin,1),ray=ProjMat*vec4(rotation*direction,0);
    vec2 screenDirection=ray.xy*start.w-start.xy*ray.w;
    ivec2 fullSize=textureSize(SceneDepth,0);
    int top=min(5,int(ReflectionTrace.y)),level=top;
    float t=.15;
    for(int visit=0;visit<128;visit++) {
        if(visit>=int(ReflectionTrace.z) || t>=48.0)break;
        vec4 point=start+ray*t;
        if(point.w<=.001)return vec4(0);
        vec3 projected=point.xyz/point.w;
        vec2 uv=projected.xy*.5+.5;
        if(projected.z<0.0 || projected.z>1.0 || any(lessThan(uv,vec2(.002))) || any(greaterThan(uv,vec2(.998))))return vec4(0);
        ivec2 size=level<0?fullSize:textureSize(SceneHzb,level);
        int span=1<<(level+1); // level0 covers2x2 full-resolution pixels
        ivec2 cell=clamp(ivec2(uv*vec2(fullSize)+sign(screenDirection)*1e-5)/span,ivec2(0),size-1);
        ivec2 lower=cell*span,upper=(cell+1)*span;
        // Odd-sized mip tails merge into the last cell; use its actual screen footprint.
        if(cell.x==size.x-1)upper.x=fullSize.x;
        if(cell.y==size.y-1)upper.y=fullSize.y;
        vec2 border=vec2(screenDirection.x>0.0?upper.x:lower.x,screenDirection.y>0.0?upper.y:lower.y)/vec2(fullSize);
        float end=min(boundaryTime(start.x,ray.x,start.w,ray.w,border.x,t),boundaryTime(start.y,ray.y,start.w,ray.w,border.y,t));
        vec4 endpoint=start+ray*end;
        if(endpoint.w<=.001) { if(level>=0){level--;continue;}return vec4(0); }
        float endDepth=endpoint.z/endpoint.w;
        float nearest=level<0?texelFetch(SceneDepth,cell,0).r:texelFetch(SceneHzb,cell,level).r;
        // Reversed-Z: skip only when the whole ray segment is in front of every surface.
        if(nearest>0.0 && min(projected.z,endDepth)<=nearest+1e-7) {
            if(level>=0){level--;continue;}
            float denominator=ray.z-nearest*ray.w;
            if(abs(denominator)>1e-9 && projected.z>=nearest-1e-7 && endDepth<=nearest+1e-7) {
                float hitT=(nearest*start.w-start.z)/denominator;
                if(hitT>=t-1e-5 && hitT<=end+1e-5) {
                    vec2 hitUv;float gap;
                    if(!reflectionGuide(origin+direction*hitT,hitUv,gap))return vec4(0);
                    vec4 hdr=texture(CurrentHdr,hitUv);
                    vec3 hit=waterPosition(hitUv,texture(SceneDepth,hitUv).r);
                    float distance=length(hit),tolerance=max(.12,distance*.004);
                    if(hdr.a<=0.0 || abs(gap)>tolerance)return vec4(0);
                    float borderFade=min(min(hitUv.x,1.0-hitUv.x),min(hitUv.y,1.0-hitUv.y));
                    float confidence=smoothstep(.005,.08,borderFade)*(1.0-smoothstep(36.0,48.0,hitT))
                        *(1.0-smoothstep(tolerance*.5,tolerance,abs(gap)));
                    return vec4(hdr.rgb,confidence);
                }
            }
        }
        t=end+max(1e-5,end*1e-6);
        level=min(level+1,top);
    }
    return vec4(0); // Bounded/offscreen/unsupported hit falls back to sky.
}
vec4 screenReflection(vec3 surface,vec3 normal,vec3 direction) {
    return ReflectionTrace.x>.5?hzbReflection(surface,normal,direction):linearReflection(surface,normal,direction);
}

vec3 octNormal(vec2 e){vec3 n=vec3(e*2.0-1.0,1.0-abs(e.x*2.0-1.0)-abs(e.y*2.0-1.0));if(n.z<0.0)n.xy=(1.0-abs(n.yx))*sign(n.xy);return normalize(n);}
vec3 linearAlbedo(vec3 a){return mix(a/12.92,pow((a+.055)/1.055,vec3(2.4)),step(vec3(.04045),a));}
vec3 conductorF0(int metal,vec3 albedo) {
    // LabPBR predefined conductors; RGB optical constants, normal-incidence Fresnel.
    const vec3 ns[8]=vec3[](vec3(2.9114,2.9497,2.5845),vec3(.18299,.42108,1.3734),vec3(1.3456,.96521,.61722),vec3(3.1071,3.1812,2.3230),vec3(.27105,.67693,1.3164),vec3(1.91,1.83,1.44),vec3(2.3757,2.0847,1.8453),vec3(.15943,.14512,.13547));
    const vec3 ks[8]=vec3[](vec3(3.0893,2.9318,2.7670),vec3(3.4242,2.3459,1.7704),vec3(7.4746,6.3995,5.3031),vec3(3.3314,3.3291,3.1350),vec3(3.6092,2.6248,2.2921),vec3(3.51,3.4,3.18),vec3(4.2655,3.7153,3.1365),vec3(3.9291,3.19,2.3808));
    if(metal<230||metal>237)return albedo;
    vec3 n=ns[metal-230],k=ks[metal-230];return ((n-1.0)*(n-1.0)+k*k)/((n+1.0)*(n+1.0)+k*k);
}

void main(){
 fragColor=vec4(0);float d=texture(SceneDepth,texCoord).r;vec4 gn=texture(MaterialNormal,texCoord);vec4 albedo=texture(MaterialAlbedo,texCoord);int flags=int(round(albedo.a*255.0));
 if(d<=0.0||gn.a<.16||(flags&21)!=0||PbrControls.x<.5)return;
 vec4 packed=texture(MaterialPbr,texCoord);ivec2 id=ivec2(round(packed.rg*255.0));vec4 pbr=texelFetch(MaterialTable,id,0);vec4 properties=texture(MaterialEmission,texCoord);
 vec3 n=octNormal(packed.ba),p=waterPosition(texCoord,d),v=normalize(-p);float alpha=max(.045,pow(1.0-pbr.r,2.0));
 float porosity=pbr.b*255.0<=64.0?pbr.b*255.0/64.0:0.0;float wet=PbrControls.y*properties.b*smoothstep(.2,.9,gn.y*2.0-1.0);alpha=mix(alpha,max(.045,alpha*.2),wet*(1.0-porosity));
 if(alpha>.5||dot(n,v)<=0.0)return;
 vec3 ray=reflect(-v,n);vec4 hit=screenReflection(p,n,ray);if(hit.a<=0.0)return;
 int reflectance=int(round(pbr.g*255.0));vec3 f0=reflectance>=230?conductorF0(reflectance,linearAlbedo(albedo.rgb)):vec3(pbr.g);
 vec3 fresnel=f0+(1.0-f0)*pow(1.0-max(dot(n,v),0.0),5.0);
 vec3 fallback=mix(HorizonColorLower.rgb,SkyColorStrength.rgb,max(ray.y,0.0))*SkyColorStrength.a*properties.b;
 float weight=hit.a*(1.0-smoothstep(.15,.5,alpha))*mix(1.0,.45,alpha);
 fragColor=vec4((hit.rgb-fallback)*fresnel*weight,1);
}
