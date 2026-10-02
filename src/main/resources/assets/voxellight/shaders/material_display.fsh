#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D MaterialAlbedo;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialEmission;
uniform sampler2D MaterialDepth;
layout(std140) uniform MaterialSettings { vec4 Diagnostic; };
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 scene = texture(SceneColor, texCoord);
    float depth = texture(SceneDepth, texCoord).r;
    float materialDepth = texture(MaterialDepth, texCoord).r;
    vec4 normal = texture(MaterialNormal, texCoord);
    // Reject occluded/unsupported foreground without turning a depth epsilon into light leaking.
    int depthDifference = abs(int(floatBitsToUint(depth)) - int(floatBitsToUint(materialDepth)));
    bool valid = normal.a > 0.5 && depth > 0.0 && materialDepth > 0.0 && depthDifference <= 8;
    if (Diagnostic.x > 3.5) {
        // Green = supported matching surface. Magenta = captured but depth mismatch. Gray = uncaptured.
        vec3 overlay = valid ? vec3(0.0, 1.0, 0.2) : normal.a > 0.5 ? vec3(1.0, 0.0, 1.0) : vec3(0.3);
        fragColor = depth <= 0.0 ? scene : vec4(mix(scene.rgb, overlay, 0.75), scene.a);
        return;
    }
    if (!valid) { fragColor = scene; return; }
    vec4 material = texture(MaterialAlbedo, texCoord);
    vec2 emission = texture(MaterialEmission, texCoord).rg;
    vec3 color;
    if (Diagnostic.x < 0.5) color = material.rgb;
    else if (Diagnostic.x < 1.5) color = normalize(normal.xyz) * 0.5 + 0.5;
    else if (Diagnostic.x < 2.5) color = vec3(max(emission.x, emission.y));
    else {
        int flags = int(round(material.a * 255.0));
        color = vec3((flags & 1) != 0 ? 1.0 : 0.0, (flags & 2) != 0 ? 1.0 : 0.0, (flags & 8) != 0 ? 1.0 : 0.0);
        if ((flags & 4) != 0) color = max(color, vec3(0.35));
    }
    fragColor = vec4(color, scene.a);
}
