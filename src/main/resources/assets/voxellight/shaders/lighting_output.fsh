#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D VoxelCloud;
uniform sampler2D LightingHdr;
uniform sampler2D SceneDepth;
uniform sampler2D EmissiveBloom;
uniform sampler2D MaterialEmission;
uniform sampler2D MaterialNormal;
uniform sampler2D VolumetricScatter;
layout(std140) uniform VolumetricSettings { vec4 VolumeParameters; vec4 VolumeQuality; };
layout(std140) uniform AtmosphereSettings { vec4 AtmosphereParameters;vec4 MediumControls; }; // density, camera above sea level, enabled, max distance
layout(std140) uniform LightingEnvironment { vec4 DirectColorStrength; vec4 SkyColorStrength; vec4 HorizonColorLower; };
layout(std140) uniform VisualSettings {
    vec4 ToneBloom; // exposure scale, filmic enabled, bloom strength, coverage blend enabled
    vec4 MaterialFade; // camera distance fade start/end
};
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];
    mat4 ViewToWorld;
    vec4 LightDirectionAndMask;
    vec4 Coverage;
    vec4 CascadeRanges;
    mat4 InvProjection;
    mat4 LightNormalMatrix[3];
    mat4 TerrainLightMatrix[3];mat4 TerrainNormalMatrix[3];
    mat4 NextLightMatrix[3];mat4 NextNormalMatrix[3];
    vec4 EpochBlend; // x visibility blend; y surface PCF radius; w enables fixed-angle terrain epochs.

};
layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};
layout(std140) uniform AoSettings {
    vec4 AoParameters; // Radius, strength, plane bias, maximum full-resolution screen radius.
    vec4 AoFilter; // Plane tolerance, normal threshold, enabled, debug view.
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
// These functions stay outside the shared water display function block.
float volumeGuideDistance(vec2 uv) {
    float depth=texture(SceneDepth,uv).r;
    if(depth<=0.0)return 1e6;
    vec4 p=InvProjection*vec4(uv*2.0-1.0,depth,1.0);
    return abs(p.w)>1e-7?length(p.xyz/p.w):1e6;
}
vec4 filteredVolume(vec2 uv,float receiverDistance) {
    ivec2 size=textureSize(VolumetricScatter,0);
    vec2 grid=uv*vec2(size)-.5, phase=fract(grid);
    ivec2 base=ivec2(floor(grid));
    vec4 sum=vec4(0);float weight=0.0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        ivec2 pixel=clamp(base+ivec2(x,y),ivec2(0),size-1);
        vec2 sampleUv=(vec2(pixel)+.5)/vec2(size);
        float guide=volumeGuideDistance(sampleUv);
        float w=(x==0?1.0-phase.x:phase.x)*(y==0?1.0-phase.y:phase.y)
            *exp(-abs(receiverDistance-guide)/max(.5,receiverDistance*.025));
        sum+=texelFetch(VolumetricScatter,pixel,0)*w;weight+=w;
    }
    // Reject silhouette-crossing samples instead of spreading fog over foreground geometry.
    return weight>1e-5?sum/weight:vec4(0,0,0,1);
}
// VOXELLIGHT_ENVIRONMENT_FUNCTIONS
vec3 linearToSrgb(vec3 c) {
    return mix(1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        c * 12.92, lessThanEqual(c, vec3(0.0031308)));
}
float fogAmount(float distanceToCamera, float start, float end) {
    if (distanceToCamera <= start) return 0.0;
    if (distanceToCamera >= end) return 1.0;
    return (distanceToCamera - start) / (end - start);
}
vec3 filmicCurve(vec3 x) {
    return (x*(.15*x+.05)+.004)/(x*(.15*x+.5)+.06)-.02/.3;
}
vec3 aerialPerspective(vec3 radiance,vec3 position,float skyAccess) {
    if(AtmosphereParameters.z<.5)return radiance;
    float distanceToCamera=length(position), integratedDensity=0.0;
    for(int i=0;i<4;i++) {
        float height=AtmosphereParameters.y+position.y*(float(i)+.5)/4.0;
        integratedDensity+=exp(-clamp(height/48.0,-1.0,4.0));
    }
    float opticalDepth=min(distanceToCamera,AtmosphereParameters.w)*AtmosphereParameters.x*integratedDensity*.25;
    float haze=(1.0-exp(-min(opticalDepth,2.0)))*clamp(skyAccess,0.0,1.0);
    // Analytic forward glow only. No claim of shadowed shafts or volumetric occlusion.
    float phase=pow(max(dot(position/max(distanceToCamera,.001),LightDirectionAndMask.xyz),0.0),12.0);
    vec3 scattered=HorizonColorLower.rgb*(.01+SkyColorStrength.a*1.5)
        +DirectColorStrength.rgb*DirectColorStrength.a*phase*MediumControls.y;
    return mix(radiance,scattered,haze);
}
vec3 mapColor(vec3 radiance,vec3 position,vec2 uv) {
    radiance = max(radiance + texture(EmissiveBloom,uv).rgb * ToneBloom.z,vec3(0.0)) * ToneBloom.x;
    vec3 mapped = ToneBloom.y>.5 ? clamp(filmicCurve(radiance)/filmicCurve(vec3(6.0)),0.0,1.0)
        : radiance/(vec3(1.0)+radiance);
    vec3 encoded = linearToSrgb(mapped);
    float fog = max(fogAmount(length(position), FogEnvironmentalStart, FogEnvironmentalEnd),
        fogAmount(max(length(position.xz), abs(position.y)), FogRenderDistanceStart, FogRenderDistanceEnd));
    if(UnderwaterControls.x>.5)fog=fogAmount(length(position),FogRenderDistanceStart,FogRenderDistanceEnd);
    // Native FogColor and main RGBA8 contain display-encoded color. Apply fog once, after tone mapping.
    return mix(encoded,FogColor.rgb,fog*FogColor.a);
}
vec3 displayColor(vec3 inputRadiance,vec3 position,float skyAccess,vec2 uv) {
    return mapColor(aerialPerspective(max(inputRadiance,vec3(0)),position,skyAccess),position,uv);
}
void main() {
    vec4 hdr = texture(LightingHdr, texCoord);
    float sceneDepth=texture(SceneDepth,texCoord).r;
    vec4 farPoint=InvProjection*vec4(texCoord*2.0-1.0,.00001,1);
    vec3 skyRay=normalize(mat3(ViewToWorld)*(farPoint.xyz/farPoint.w));
    if(sceneDepth<=0.0 && hdr.a<.5 && WeatherControls.x>.5){
        vec3 sky=environmentSky(skyRay,SkyColorStrength.rgb,HorizonColorLower.rgb,SkyColorStrength.a);
        vec4 cloud=MediumControls.z>.5?texture(VoxelCloud,texCoord):environmentCloud(skyRay,vec3(0),DirectColorStrength.rgb,DirectColorStrength.a,SkyColorStrength.rgb);
        sky=(MediumControls.z>.5?sky*(1-cloud.a)+cloud.rgb:mix(sky,cloud.rgb,cloud.a))*ToneBloom.x;
        fragColor=vec4(linearToSrgb(clamp(filmicCurve(max(sky,vec3(0)))/filmicCurve(vec3(6)),0.0,1.0)),1);return;
    }
    // Unsupported pixels keep their original native color without a SceneColor copy.
    if (hdr.a < 0.5) discard;
    if(AoFilter.w>.5) { fragColor=vec4(hdr.rgb,1.0);return; }
    float depth = texture(SceneDepth, texCoord).r;
    vec4 view = InvProjection * vec4(texCoord * 2.0 - 1.0, max(depth,.00001), 1.0);
    vec3 position = (ViewToWorld * vec4(view.xyz / view.w, 1.0)).xyz;
    vec4 cloud=MediumControls.z>.5?texture(VoxelCloud,texCoord):environmentCloud(normalize(position),position,DirectColorStrength.rgb,DirectColorStrength.a,SkyColorStrength.rgb);
    hdr.rgb=MediumControls.z>.5?hdr.rgb*(1-cloud.a)+cloud.rgb:mix(hdr.rgb,cloud.rgb,cloud.a);
    hdr.rgb=underwaterMedium(hdr.rgb,position,SkyColorStrength.rgb,DirectColorStrength.a);
    vec3 displayed;
    if(VolumeParameters.x>.5) {
        vec4 air=filteredVolume(texCoord,length(position));
        displayed=mapColor(hdr.rgb*air.a+air.rgb,position,texCoord);
    } else displayed=displayColor(hdr.rgb,position,texture(MaterialEmission,texCoord).b,texCoord);
    float nativeVisibility=texture(MaterialNormal,texCoord).a*3.0;
    if(nativeVisibility>=2.0)displayed=mix(FogColor.rgb,displayed,clamp(nativeVisibility-2.0,0.0,1.0));
    float coverage = ToneBloom.w>.5 ? 1.0-smoothstep(MaterialFade.x,MaterialFade.y,length(position)) : 1.0;
    fragColor = vec4(displayed, coverage);
}
