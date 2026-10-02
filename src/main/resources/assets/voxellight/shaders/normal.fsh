#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneSampler;
layout(std140) uniform Projection {
    mat4 ProjMat;
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 reconstruct(vec2 uv, float depth, mat4 inverseProjection) {
    vec4 position = inverseProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
    return position.xyz / position.w;
}

void main() {
    float centerDepth = texture(SceneSampler, texCoord).r;
    if (centerDepth <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 pixel = 1.0 / vec2(textureSize(SceneSampler, 0));
    float leftDepth = texture(SceneSampler, texCoord - vec2(pixel.x, 0.0)).r;
    float rightDepth = texture(SceneSampler, texCoord + vec2(pixel.x, 0.0)).r;
    float downDepth = texture(SceneSampler, texCoord - vec2(0.0, pixel.y)).r;
    float upDepth = texture(SceneSampler, texCoord + vec2(0.0, pixel.y)).r;
    // Prefer the neighbor on the same surface to reduce silhouette artifacts.
    float xSign = abs(leftDepth - centerDepth) < abs(rightDepth - centerDepth) ? -1.0 : 1.0;
    float ySign = abs(downDepth - centerDepth) < abs(upDepth - centerDepth) ? -1.0 : 1.0;
    float xDepth = xSign < 0.0 ? leftDepth : rightDepth;
    float yDepth = ySign < 0.0 ? downDepth : upDepth;
    // Do not reconstruct infinite sky positions on single-pixel silhouettes.
    if (xDepth <= 0.0) xDepth = centerDepth;
    if (yDepth <= 0.0) yDepth = centerDepth;
    mat4 inverseProjection = inverse(ProjMat);
    vec3 center = reconstruct(texCoord, centerDepth, inverseProjection);
    vec3 dx = (reconstruct(texCoord + vec2(pixel.x * xSign, 0.0),
                          xDepth, inverseProjection) - center) * xSign;
    vec3 dy = (reconstruct(texCoord + vec2(0.0, pixel.y * ySign),
                          yDepth, inverseProjection) - center) * ySign;
    vec3 normal = cross(dx, dy);
    float magnitude = length(normal);
    if (magnitude > 0.00000001) normal /= magnitude;
    else normal = vec3(0.0, 0.0, 1.0);
    if (dot(normal, center) > 0.0) normal = -normal;
    fragColor = vec4(normal * 0.5 + 0.5, 1.0);
}
