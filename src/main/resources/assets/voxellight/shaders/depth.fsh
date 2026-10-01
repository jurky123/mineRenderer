#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    // Diagnostic reversed-Z visualization, not linear distance or lighting.
    float depth = texture(SceneSampler, texCoord).r;
    float shade = log2(1.0 + 65535.0 * clamp(depth, 0.0, 1.0)) / 16.0;
    fragColor = vec4(vec3(shade), 1.0);
}
