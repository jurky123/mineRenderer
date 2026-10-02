#version 330
#extension GL_ARB_separate_shader_objects : require
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(location=0) out vec2 texCoord;
layout(location=1) out vec4 unlitTint;
layout(location=2) out vec3 surfaceNormal;
layout(location=3) flat out ivec2 overlayCoords;
layout(location=4) out vec2 compatibilityLight;
void main() {
    // Native model positions are already posed, camera-relative world coordinates.
    gl_Position=ProjMat * ModelViewMat * vec4(Position,1.0);
    texCoord=UV0;
    unlitTint=Color * ColorModulator;
    surfaceNormal=Normal;
    overlayCoords=UV1;
    compatibilityLight=vec2(UV2)/240.0;
}
