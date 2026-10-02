#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;
layout(location = 0) in vec2 texCoord;
layout(location = 1) in float vertexAlpha;
layout(location = 2) flat in float alphaCutout;

void main() {
    if (alphaCutout > 0.5 && texture(Sampler0, texCoord).a * vertexAlpha < 0.5) discard;
    // Solid geometry stays opaque like vanilla; only cutout depends on atlas alpha.
}
