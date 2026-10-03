#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialDepth;
uniform sampler2D MaterialAlbedo;
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
bool aoSurface(ivec2 pixel,out float depth,out vec3 normal) {
    depth=texelFetch(MaterialDepth,pixel,0).r;
    float scene=texelFetch(SceneDepth,pixel,0).r;
    vec4 geometry=texelFetch(MaterialNormal,pixel,0) * vec4(2,2,2,3) - vec4(1,1,1,0);
    int flags=int(round(texelFetch(MaterialAlbedo,pixel,0).a*255.0));
    if(depth<=0.0 || scene<=0.0 || geometry.a<.5 || (flags&21)!=0
            || abs(int(floatBitsToUint(depth))-int(floatBitsToUint(scene)))>8)return false;
    normal=normalize(transpose(mat3(ViewToWorld))*geometry.xyz);
    return true;
}
// Cosine-weighted analytic horizon-slice integral, normalized by the unobstructed slice.
float aoIntegral(float h,float n) {
    float s=sin(h);
    return .5*cos(n)*s*s+sin(n)*(.5*h-.25*sin(2.0*h));
}
void main() {
    aoResult=vec4(1,.5,.5,-1);
    ivec2 fullSize=textureSize(SceneDepth,0),pixel=min(ivec2(gl_FragCoord.xy)*2+1,fullSize-1);
    float depth;vec3 normal;
    if(!aoSurface(pixel,depth,normal))return;
    vec2 uv=(vec2(pixel)+.5)/vec2(fullSize);
    vec3 position=aoUnproject(uv,depth);
    if(position.z>=-.01)return;
    vec3 towardCamera=normalize(-position);
    float screenRadius=clamp(AoParameters.x*abs(ProjMat[1][1])*float(fullSize.y)/(-2.0*position.z),2.0,AoParameters.w);
    float visible=0.0,total=0.0;
    // Four fixed slices, four quadratic steps per side; no frame jitter/history.
    for(int slice=0;slice<4;slice++) {
        float phi=float(slice)*.7853981634;
        vec2 direction=vec2(cos(phi),sin(phi));
        vec3 tangent=aoUnproject(uv+direction/vec2(fullSize),depth)-position;
        tangent=normalize(tangent-towardCamera*dot(tangent,towardCamera));
        float n=atan(dot(normal,tangent),max(dot(normal,towardCamera),.00001));
        float projectedLength=length(vec2(dot(normal,towardCamera),dot(normal,tangent)));
        float low=n-1.5707963268,high=n+1.5707963268;
        float negative=low,positive=high;
        for(int side=0;side<2;side++)for(int step=1;step<=4;step++) {
            float t=float(step)/4.0;
            vec2 sampleUv=uv+direction*(side==0?1.0:-1.0)*(1.0+screenRadius*t*t)/vec2(fullSize);
            if(any(lessThan(sampleUv,vec2(0))) || any(greaterThanEqual(sampleUv,vec2(1))))continue;
            ivec2 samplePixel=ivec2(sampleUv*vec2(fullSize));
            float otherDepth;vec3 otherNormal;
            if(!aoSurface(samplePixel,otherDepth,otherNormal))continue;
            vec3 delta=aoUnproject((vec2(samplePixel)+.5)/vec2(fullSize),otherDepth)-position;
            float distance=length(delta);
            if(distance<.001 || distance>=AoParameters.x || dot(normal,delta)<=AoParameters.z)continue;
            float angle=acos(clamp(dot(delta/distance,towardCamera),-1.0,1.0));
            float weight=1.0-smoothstep(AoParameters.x*.4,AoParameters.x,distance);
            if(side==0)positive=min(positive,mix(high,clamp(angle,0.0,high),weight));
            else negative=max(negative,mix(low,clamp(-angle,low,0.0),weight));
        }
        float unoccluded=aoIntegral(high,n)+aoIntegral(low,n);
        float bounded=aoIntegral(positive,n)+aoIntegral(negative,n);
        visible+=projectedLength*clamp(bounded/max(unoccluded,.00001),0.0,1.0);
        total+=projectedLength;
    }
    float visibility=clamp(visible/max(total,.00001),.35,1.0);
    aoResult=vec4(visibility,aoEncode(normal),-position.z);
}
