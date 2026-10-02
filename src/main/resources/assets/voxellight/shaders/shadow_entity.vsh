#version 330
#extension GL_ARB_separate_shader_objects : require
in vec3 Position;
in vec4 Color;
in vec2 UV0;
layout(std140) uniform ShadowSettings {
    mat4 LightMatrix;
    mat4 ViewToWorld;
    vec4 LightDirectionAndMask;
    vec4 Coverage;
};
layout(location = 0) out vec2 texCoord;
layout(location = 1) out float vertexAlpha;
layout(location = 2) flat out float alphaCutout;
void main() {
    // Native model poses have already been applied in camera-relative world coordinates.
    gl_Position = LightMatrix * vec4(Position, 1.0);
    texCoord = UV0;
    vertexAlpha = Color.a;
    alphaCutout = 1.0;
}
