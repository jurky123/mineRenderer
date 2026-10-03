// Appended to the active native terrain fragment shader after renaming its main.
uniform sampler2D WaterHdr;
uniform sampler2D WaterDepth;
uniform sampler2D EmissiveBloom;
in float waterSkyAccess;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform WaterSettings {
    vec4 WaterStill;vec4 WaterFlow;vec4 WaterCamera;vec4 WaterParameters;
};
layout(std140) uniform VisualSettings {vec4 ToneBloom;vec4 MaterialFade;};
layout(std140) uniform AtmosphereSettings {vec4 AtmosphereParameters;};
layout(std140) uniform LightingEnvironment {vec4 DirectColorStrength;vec4 SkyColorStrength;vec4 HorizonColorLower;};
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;
    mat4 InvProjection;mat4 LightNormalMatrix[3];
    mat4 TerrainLightMatrix[3];mat4 TerrainNormalMatrix[3];
    mat4 NextLightMatrix[3];mat4 NextNormalMatrix[3];
    vec4 EpochBlend; // x visibility blend; y surface PCF radius; w enables fixed-angle terrain epochs.

};
// VOXELLIGHT_VISUAL_FUNCTIONS
bool waterSprite(vec2 uv,vec4 bounds){
    // Interpolated UV may round just outside an atlas edge shared by fluid quads.
    return all(greaterThanEqual(uv,bounds.xy-vec2(1e-6))) && all(lessThanEqual(uv,bounds.zw+vec2(1e-6)));
}
vec3 waterPosition(vec2 uv,float depth) {
    vec4 view=InvProjection*vec4(uv*2.0-1.0,depth,1.0);
    return (ViewToWorld*vec4(view.xyz/view.w,1.0)).xyz;
}
bool background(vec2 uv,vec3 surface,out vec3 radiance,out float thickness) {
    vec4 hdr=texture(WaterHdr,uv);float depth=texture(WaterDepth,uv).r;
    vec3 back=waterPosition(uv,depth);
    float distanceBack=length(back);
    if(depth<=0.0 || hdr.a<=0.0 || abs(hdr.a-distanceBack)>max(.04,distanceBack*.002) || distanceBack<=length(surface))return false;
    radiance=hdr.rgb;thickness=clamp(distanceBack-length(surface),0.0,16.0);return true;
}
// Reflect only current supported HDR surfaces; offscreen/sky/missing-data rays use sky fallback.
bool reflectionGuide(vec3 point,out vec2 uv,out float gap) {
    vec3 view=transpose(mat3(ViewToWorld))*point;
    vec4 clip=ProjMat*vec4(view,1);
    if(clip.w<=.001)return false;
    uv=clip.xy/clip.w*.5+.5;
    if(any(lessThan(uv,vec2(.002))) || any(greaterThan(uv,vec2(.998))))return false;
    float depth=texture(WaterDepth,uv).r;
    if(depth<=0.0)return false;
    vec3 hit=waterPosition(uv,depth);
    gap=length(point)-length(hit);
    return true;
}
vec4 screenReflection(vec3 surface,vec3 normal,vec3 direction) {
    int steps=int(WaterParameters.w);
    if(steps<=0 || normal.y<.5)return vec4(0);
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
            vec4 hdr=texture(WaterHdr,uv);
            vec3 hit=waterPosition(uv,texture(WaterDepth,uv).r);
            float distance=length(hit),tolerance=max(.12,distance*.004);
            if(hdr.a<=0.0 || abs(hdr.a-distance)>max(.04,distance*.002) || gap<0.0 || gap>tolerance)return vec4(0);
            float border=min(min(uv.x,1.0-uv.x),min(uv.y,1.0-uv.y));
            float confidence=smoothstep(.005,.08,border)*(1.0-smoothstep(36.0,48.0,hi))
                *(1.0-smoothstep(tolerance*.5,tolerance,gap));
            return vec4(hdr.rgb,confidence);
        }
        previousValid=true;previousGap=gap;previousT=t;
    }
    return vec4(0);
}
vec2 waveSlope(vec2 position,float phase) {
    // Periodic, wind-biased ripples with independent harmonics; suppress unresolved wavelengths.
    // All spatial frequencies wrap at 64 blocks; evaluate derivatives before any fragment rejection.
    const vec2 directions[6]=vec2[6](vec2(7,3),vec2(13,5),vec2(19,-7),vec2(-9,17),vec2(31,11),vec2(-23,29));
    const float rates[6]=float[6](2,3,5,-4,7,-9);
    const float weights[6]=float[6](.32,.25,.18,.12,.08,.05);
    vec2 slope=vec2(0);
    for(int i=0;i<6;i++) {
        vec2 k=directions[i]*.09817477042;
        float angle=dot(position,k)+rates[i]*phase+float(i)*1.718;
        float footprint=max(abs(dot(dFdx(position),k)),abs(dot(dFdy(position),k)));
        float resolved=1.0-smoothstep(.5,2.5,footprint);
        slope+=normalize(k)*cos(angle)*weights[i]*resolved;
    }
    return WaterParameters.z*slope;
}
void main() {
    // Derivatives require all quad/helper lanes, including pixels which later use native fallback.
    // Evaluating them after background/sprite rejection created native-blue seams and dashes.
    vec2 uv=gl_FragCoord.xy/vec2(textureSize(WaterHdr,0));
    vec3 surface=waterPosition(uv,gl_FragCoord.z);
    vec3 crossed=cross(dFdx(surface),dFdy(surface));
    vec2 slope=waveSlope(surface.xz+WaterCamera.xz,WaterCamera.w);
    voxellightNativeMain();
    if(!waterSprite(texCoord0,WaterStill) && !waterSprite(texCoord0,WaterFlow))return;
    vec3 base;float thickness;
    if(!background(uv,surface,base,thickness))return;
    if(dot(crossed,crossed)<=0.0 || any(isnan(crossed)) || any(isinf(crossed)) || length(surface)<.001)return;
    vec3 normal=normalize(crossed);
    vec3 viewDirection=normalize(-surface);
    if(dot(normal,viewDirection)<0.0)normal=-normal;
    if(abs(normal.y)>.8) {
        normal=normalize(normal+vec3(slope.x,0,slope.y));
    }
    vec3 viewNormal=transpose(mat3(ViewToWorld))*normal;
    vec2 refracted=uv+viewNormal.xy*min(10.0,thickness*3.0)/vec2(textureSize(WaterHdr,0));
    vec3 shifted;float shiftedThickness;
    if(all(greaterThanEqual(refracted,vec2(0))) && all(lessThanEqual(refracted,vec2(1))) && background(refracted,surface,shifted,shiftedThickness)) {base=shifted;thickness=shiftedThickness;}
    vec3 transmission=exp(-vec3(.18,.065,.028)*thickness);
    vec3 reflected=reflect(-viewDirection,normal);
    vec3 sky=mix(HorizonColorLower.rgb,SkyColorStrength.rgb,clamp(reflected.y,0.0,1.0))*SkyColorStrength.a*waterSkyAccess;
    float specular=pow(max(dot(reflected,LightDirectionAndMask.xyz),0.0),64.0);
    sky+=DirectColorStrength.rgb*DirectColorStrength.a*specular*waterSkyAccess;
    vec4 sceneReflection=screenReflection(surface,normal,reflected);
    sky=mix(sky,sceneReflection.rgb,sceneReflection.a);
    vec3 body=vec3(.015,.09,.12)*(.01+SkyColorStrength.a*waterSkyAccess);
    vec3 transmitted=base*transmission+body*(1.0-transmission);
    float fresnel=.02+.98*pow(1.0-clamp(dot(normal,viewDirection),0.0,1.0),5.0);
    vec3 radiance=mix(transmitted,sky,fresnel);
    float coverage=(ToneBloom.w>.5?1.0-smoothstep(WaterParameters.x,WaterParameters.y,length(surface)):1.0)*clamp(ChunkVisibility,0.0,1.0);
    // Fully admitted water replaces the native fragment with a transmitted HDR background, tone mapped once.
    fragColor=mix(fragColor,vec4(displayColor(radiance,surface,waterSkyAccess,uv),1.0),coverage);
}
