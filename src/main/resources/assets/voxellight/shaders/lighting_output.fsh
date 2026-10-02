#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D LightingHdr;
uniform sampler2D SceneDepth;
uniform sampler2D EmissiveBloom;
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
void main() {
    vec4 hdr = texture(LightingHdr, texCoord);
    // Unsupported pixels keep their original native color without a SceneColor copy.
    if (hdr.a < 0.5) discard;
    if(AoFilter.w>.5) { fragColor=vec4(hdr.rgb,1.0);return; }
    float depth = texture(SceneDepth, texCoord).r;
    vec4 view = InvProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 position = (ViewToWorld * vec4(view.xyz / view.w, 1.0)).xyz;
    vec3 radiance = max(hdr.rgb + texture(EmissiveBloom,texCoord).rgb * ToneBloom.z,vec3(0.0)) * ToneBloom.x;
    vec3 mapped = ToneBloom.y>.5 ? clamp(filmicCurve(radiance)/filmicCurve(vec3(6.0)),0.0,1.0)
        : radiance/(vec3(1.0)+radiance);
    vec3 encoded = linearToSrgb(mapped);
    float fog = max(fogAmount(length(position), FogEnvironmentalStart, FogEnvironmentalEnd),
        fogAmount(max(length(position.xz), abs(position.y)), FogRenderDistanceStart, FogRenderDistanceEnd));
    // Native FogColor and main RGBA8 contain display-encoded color. Apply fog once, after tone mapping.
    float coverage = ToneBloom.w>.5 ? 1.0-smoothstep(MaterialFade.x,MaterialFade.y,length(position)) : 1.0;
    fragColor = vec4(mix(encoded, FogColor.rgb, fog * FogColor.a), coverage);
}
