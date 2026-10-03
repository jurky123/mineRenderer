#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D AoInput;
uniform sampler2D SceneDepth;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3]; mat4 ViewToWorld;
    vec4 LightDirectionAndMask; vec4 Coverage; vec4 CascadeRanges;
    mat4 InvProjection; mat4 LightNormalMatrix[3];
    mat4 TerrainLightMatrix[3];mat4 TerrainNormalMatrix[3];
    mat4 NextLightMatrix[3];mat4 NextNormalMatrix[3];
    vec4 EpochBlend; // Visibility interpolation weight; w enables fixed-angle terrain epochs.

};
layout(std140) uniform AoSettings {
    vec4 AoParameters; // Radius, strength, plane bias, maximum full-resolution screen radius.
    vec4 AoFilter; // Plane tolerance, normal threshold, enabled, debug view.
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 aoResult;
vec2 aoSign(vec2 v) { return mix(vec2(-1),vec2(1),greaterThanEqual(v,vec2(0))); }
vec3 aoDecode(vec2 encoded) {
    vec2 p=encoded*2.0-1.0;
    vec3 n=vec3(p,1.0-abs(p.x)-abs(p.y));
    if(n.z<0.0)n.xy=(1.0-abs(n.yx))*aoSign(n.xy);
    return normalize(n);
}
vec2 aoEncode(vec3 n) {
    n/=abs(n.x)+abs(n.y)+abs(n.z);
    return (n.z>=0.0?n.xy:(1.0-abs(n.yx))*aoSign(n.xy))*.5+.5;
}
vec3 aoUnproject(vec2 uv,float depth) {
    vec4 p=InvProjection*vec4(uv*2.0-1.0,depth,1.0);
    return p.xyz/p.w;
}
// Guides correspond to the fixed (1,1) texel of each 2x2 group, clamped at odd dimensions.
vec3 aoGuidePosition(ivec2 halfPixel,float linearDepth,ivec2 fullSize) {
    vec2 uv=(vec2(min(halfPixel*2+1,fullSize-1))+.5)/vec2(fullSize);
    vec3 a=aoUnproject(uv,1.0),b=aoUnproject(uv,.5);
    vec3 ray=b-a;
    return a+ray*((-linearDepth-a.z)/ray.z);
}
float aoGuideWeight(vec3 center,vec3 normal,vec3 samplePosition,vec3 sampleNormal) {
    float facing=dot(normal,sampleNormal);
    float plane=abs(dot(normal,samplePosition-center));
    float tolerance=AoFilter.x+.002*abs(center.z);
    if(facing<AoFilter.y || plane>tolerance || length(samplePosition-center)>AoParameters.x)return 0.0;
    return pow(max(facing,0.0),8.0)*exp(-plane*plane/(tolerance*tolerance*.25));
}
void main() {
    ivec2 size=textureSize(AoInput,0),center=ivec2(gl_FragCoord.xy),fullSize=textureSize(SceneDepth,0);
    vec4 guide=texelFetch(AoInput,center,0);
    aoResult=guide;
    if(guide.a<=0.0)return;
    vec3 position=aoGuidePosition(center,guide.a,fullSize),normal=aoDecode(guide.gb);
    float sum=0.0,weight=0.0;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++) {
        ivec2 pixel=center+ivec2(x,y);
        if(any(lessThan(pixel,ivec2(0))) || any(greaterThanEqual(pixel,size)))continue;
        vec4 sample=texelFetch(AoInput,pixel,0);
        if(sample.a<=0.0)continue;
        float w=aoGuideWeight(position,normal,aoGuidePosition(pixel,sample.a,fullSize),aoDecode(sample.gb))
            *exp(-float(x*x+y*y)/4.0);
        sum+=sample.r*w;weight+=w;
    }
    if(weight>0.0)aoResult.r=sum/weight;
}
