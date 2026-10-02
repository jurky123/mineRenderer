#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 unlitTint;
layout(location = 2) flat in vec3 surfaceNormal;
layout(location = 3) flat in ivec2 emissionFlags;
layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outEmission;
vec3 srgbToLinear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045)));
}
vec3 linearToSrgb(vec3 c) {
    return mix(1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, c * 12.92, lessThanEqual(c, vec3(0.0031308)));
}
void main() {
    vec4 texel = texture(Sampler0, texCoord);
    int flags = emissionFlags.y >> 4;
    if ((flags & 1) != 0 && texel.a * unlitTint.a < 0.5) discard;
    // Encoded unlit albedo preserves dark RGBA8 colors. Decode once in the future lighting resolve.
    // Excludes native lightmap, face shading, vertex AO and fog.
    outAlbedo = vec4(linearToSrgb(srgbToLinear(texel.rgb) * srgbToLinear(unlitTint.rgb)), float(flags) / 255.0);
    outNormal = vec4(normalize(surfaceNormal), 1.0);
    // Material strengths only. This is deliberately not a claim of emissive RGB radiance.
    outEmission = vec4(float(emissionFlags.x) / 15.0, float(emissionFlags.y & 15) / 15.0, 0.0, 1.0);
}
