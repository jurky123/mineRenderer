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
    float depth;
    if (panel < 1.0) depth = min(texture(ShadowMap, uv).r, texture(EntityShadowMap, uv).r);
    else if (panel < 2.0) depth = min(texture(MiddleShadowMap, uv).r, texture(MiddleEntityShadowMap, uv).r);
    else depth = min(texture(FarShadowMap, uv).r, texture(FarEntityShadowMap, uv).r);
    fragColor = vec4(vec3(depth), 1.0);
}
