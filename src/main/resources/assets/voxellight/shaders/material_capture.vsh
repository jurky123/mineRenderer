#version 330
#extension GL_ARB_separate_shader_objects : require
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in vec4 Normal;
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ChunkSection {
    mat4 ModelViewMat;
    float ChunkVisibility;
    ivec2 TextureSize;
    ivec3 ChunkPosition;
};
layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec4 unlitTint;
layout(location = 2) flat out vec3 surfaceNormal;
layout(location = 3) flat out ivec2 emissionFlags;
void main() {
    vec3 pos = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    texCoord = UV0;
    unlitTint = Color;
    surfaceNormal = Normal.xyz;
    emissionFlags = UV1;
}
