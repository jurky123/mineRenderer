#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D ShadowMap;
uniform sampler2D MiddleShadowMap;
uniform sampler2D FarShadowMap;
uniform sampler2D EntityShadowMap;
uniform sampler2D MiddleEntityShadowMap;
uniform sampler2D FarEntityShadowMap;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    float panel = texCoord.x * 3.0;
    vec2 uv = vec2(fract(panel), texCoord.y);
    // Raw current terrain epoch; dynamic depth uses another light direction and cannot be min-reduced here.
    float depth;
    if (panel < 1.0) depth = texture(ShadowMap, uv).r;
    else if (panel < 2.0) depth = texture(MiddleShadowMap, uv).r;
    else depth = texture(FarShadowMap, uv).r;
    fragColor = vec4(vec3(depth), 1.0);
}
