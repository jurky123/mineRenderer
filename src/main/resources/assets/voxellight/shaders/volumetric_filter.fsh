#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D VolumeInput;
uniform sampler2D SceneDepth;
layout(std140) uniform VolumeFilterSettings { vec4 FilterDirection; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;
    mat4 InvProjection;mat4 LightNormalMatrix[3];
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float guide(vec2 uv) {
    float depth=texture(SceneDepth,uv).r;
    if(depth<=0.0)return 1e6;
    vec4 p=InvProjection*vec4(uv*2.0-1.0,depth,1);
    return abs(p.w)>1e-7?length(p.xyz/p.w):1e6;
}
void main() {
    ivec2 size=textureSize(VolumeInput,0),center=clamp(ivec2(gl_FragCoord.xy),ivec2(0),size-1);
    float receiver=guide((vec2(center)+.5)/vec2(size));
    vec4 sum=vec4(0);float total=0.0;
    for(int i=-2;i<=2;i++) {
        ivec2 pixel=clamp(center+ivec2(FilterDirection.xy)*i,ivec2(0),size-1);
        float distance=guide((vec2(pixel)+.5)/vec2(size));
        float kernel=i==0?6.0:(abs(i)==1?4.0:1.0);
        float weight=kernel*exp(-abs(receiver-distance)/max(.5,receiver*FilterDirection.z));
        sum+=texelFetch(VolumeInput,pixel,0)*weight;total+=weight;
    }
    // Positive normalized weights preserve both radiance and [0,1] transmittance.
    fragColor=sum/max(total,1e-6);
}
