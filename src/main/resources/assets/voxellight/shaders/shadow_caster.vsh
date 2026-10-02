#version 330
#extension GL_ARB_separate_shader_objects : require

in vec3 Position;
in vec4 Color;
in vec2 UV0;
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};
layout(std140) uniform ChunkSection {
    mat4 ModelViewMat;
    float ChunkVisibility;
    ivec2 TextureSize;
    ivec3 ChunkPosition;
};
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
    // Subtract integers before converting to float: large world coordinates retain block precision.
    vec3 relative = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = LightMatrix * vec4(relative, 1.0);
    texCoord = UV0;
    vertexAlpha = Color.a;
    alphaCutout = ChunkVisibility; // Private caster flag: solid=0, cutout=1.
}
