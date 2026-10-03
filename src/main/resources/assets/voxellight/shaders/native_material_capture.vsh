#version 330
#extension GL_ARB_separate_shader_objects : require
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
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in ivec2 UV1;
in vec4 Normal;
layout(location=0) out vec2 texCoord;
layout(location=1) out vec4 unlitTint;
layout(location=2) flat out vec3 surfaceNormal;
layout(location=3) flat out ivec2 emissionFlags;
layout(location=4) out vec2 compatibilityLight;
layout(location=5) out vec3 materialPosition;
void main(){
    vec3 pos=Position+vec3(ChunkPosition-CameraBlockPos)+CameraOffset;
    materialPosition=pos;
    gl_Position=ProjMat*ModelViewMat*vec4(pos,1);
    uint packed=uint(UV1.x)&65535u | ((uint(UV1.y)&65535u)<<16);
    texCoord=UV0;
    unlitTint=vec4(float((packed>>16)&255u),float((packed>>8)&255u),float(packed&255u),255)/255.0;
    unlitTint.a=Color.a; // native per-vertex alpha remains part of cutout coverage
    surfaceNormal=Normal.xyz;
    int marker=int(round(Normal.w*127.0));
    emissionFlags=ivec2(marker-16,int(packed>>24));
    compatibilityLight=vec2(UV2)/240.0;
}
