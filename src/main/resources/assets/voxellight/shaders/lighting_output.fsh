#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D LightingHdr;
uniform sampler2D SceneDepth;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];
    mat4 ViewToWorld;
    vec4 LightDirectionAndMask;
    vec4 Coverage;
    vec4 CascadeRanges;
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
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
vec3 linearToSrgb(vec3 c) {
    return mix(1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        c * 12.92, lessThanEqual(c, vec3(0.0031308)));
}
float fogAmount(float distanceToCamera, float start, float end) {
    if (distanceToCamera <= start) return 0.0;
    if (distanceToCamera >= end) return 1.0;
    return (distanceToCamera - start) / (end - start);
}
void main() {
    vec4 hdr = texture(LightingHdr, texCoord);
    // Unsupported pixels keep their original native color without a SceneColor copy.
    if (hdr.a < 0.5) discard;
    float depth = texture(SceneDepth, texCoord).r;
    vec4 view = inverse(ProjMat) * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 position = (ViewToWorld * vec4(view.xyz / view.w, 1.0)).xyz;
    vec3 mapped = max(hdr.rgb, vec3(0.0)) / (vec3(1.0) + max(hdr.rgb, vec3(0.0)));
    vec3 encoded = linearToSrgb(mapped);
    float fog = max(fogAmount(length(position), FogEnvironmentalStart, FogEnvironmentalEnd),
        fogAmount(max(length(position.xz), abs(position.y)), FogRenderDistanceStart, FogRenderDistanceEnd));
    // Native FogColor and main RGBA8 contain display-encoded color. Apply fog once, after tone mapping.
    fragColor = vec4(mix(encoded, FogColor.rgb, fog * FogColor.a), 1.0);
}
