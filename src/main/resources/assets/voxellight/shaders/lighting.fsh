#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D MaterialAlbedo;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialEmission;
uniform sampler2D MaterialDepth;
uniform sampler2D SceneDepth;
uniform sampler2D ShadowMap;
uniform sampler2D MiddleShadowMap;
uniform sampler2D FarShadowMap;
uniform sampler2D EntityShadowMap;
uniform sampler2D MiddleEntityShadowMap;
uniform sampler2D FarEntityShadowMap;
uniform usampler2D VoxelOpacity;
uniform sampler2D ShapeBounds;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];
    mat4 ViewToWorld;
    vec4 LightDirectionAndMask;
    vec4 Coverage;
    vec4 CascadeRanges;
};
layout(std140) uniform LocalLightSettings {
    vec4 GridOriginAndCount;
    vec4 GridBoundsAndEnabled;
    vec4 MoonLight;
    vec4 LightPositionRadius[16];
    vec4 LightColorStrength[16];
};
layout(std140) uniform LightingEnvironment {
    vec4 DirectColorStrength;
    vec4 SkyColorStrength;
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#ifdef TEMPORAL_SHADOW
layout(location = 1) out vec4 shadowTemporalInput;
bool dynamicAffected = false;
#endif

vec3 reconstruct(vec2 uv, float depth, mat4 inverseProjection) {
    vec4 position = inverseProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
    return position.xyz / position.w;
}

bool intersectsBox(vec3 origin, vec3 ray, vec3 lower, vec3 upper) {
    float enter = 0.00001, leave = 1.0;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(ray[axis]) < 0.0000001) {
            if (origin[axis] < lower[axis] || origin[axis] > upper[axis]) return false;
        } else {
            float a = (lower[axis] - origin[axis]) / ray[axis];
            float b = (upper[axis] - origin[axis]) / ray[axis];
            enter = max(enter, min(a, b)); leave = min(leave, max(a, b));
            if (enter > leave) return false;
        }
    }
    return enter <= leave;
}

// Exact cell traversal for full-block occluders, not a screen-space ray or fixed-length sample march.
bool visibleToEmitter(vec3 start, vec3 end) {
    vec3 p = start - GridOriginAndCount.xyz;
    vec3 target = end - GridOriginAndCount.xyz;
    ivec3 cell = ivec3(floor(p));
    ivec3 endCell = ivec3(floor(target));
    vec3 ray = target - p;
    ivec3 stepCell = ivec3(sign(ray));
    vec3 delta = 1.0 / max(abs(ray), vec3(0.0000001));
    vec3 edge = vec3(cell) + step(vec3(0.0), ray);
    vec3 crossing = (edge - p) / mix(vec3(0.0000001), ray, greaterThan(abs(ray), vec3(0.0000001)));
    crossing = mix(vec3(1e20), crossing, greaterThan(abs(ray), vec3(0.0000001)));
    for (int i = 0; i < 48; i++) {
        // Emissive full blocks radiate from their boundary: do not shadow themselves at the endpoint.
        if (all(equal(cell, endCell))) return true;
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(GridBoundsAndEnabled.xyz)))) return false;
        ivec2 atlasPixel = ivec2(cell.x + (cell.y % 10) * 80, cell.z + (cell.y / 10) * 80);
        uint shape = texelFetch(VoxelOpacity, atlasPixel, 0).r;
        if (shape == 0xffffffffu || shape == 0xfffffffeu) return false;
        if (shape != 0u && MoonLight.y > 0.5) {
            int row = int(shape >> 5u), count = int(shape & 31u);
            for (int box = 0; box < 16; box++) {
                if (box >= count) break;
                vec3 lower = texelFetch(ShapeBounds, ivec2(box * 2, row), 0).xyz;
                vec3 upper = texelFetch(ShapeBounds, ivec2(box * 2 + 1, row), 0).xyz;
                if (intersectsBox(p - vec3(cell), ray, lower, upper)) return false;
            }
        }
        float next = min(crossing.x, min(crossing.y, crossing.z));
        if (next >= 1.0) return true;
        bvec3 advance = lessThanEqual(crossing, vec3(next + 0.0000001));
        cell += ivec3(advance) * stepCell;
        crossing += vec3(advance) * delta;
    }
    return false; // Bounded traversal exhaustion cannot turn into a light leak.
}

float shadowOcclusion(sampler2D map, sampler2D entities, mat4 matrix, vec3 position, vec3 normal) {
    vec4 clip = matrix * vec4(position, 1.0);
    vec3 projected = clip.xyz / clip.w;
    vec2 shadowUv = projected.xy * 0.5 + 0.5;
    vec2 pixel = 1.0 / vec2(textureSize(map, 0));
    if (projected.z < 0.0 || projected.z > 1.0 || any(lessThan(shadowUv, pixel * 3.0))
        || any(greaterThan(shadowUv, vec2(1.0) - pixel * 3.0))) return 0.0;
    vec3 planeNormal = transpose(inverse(mat3(matrix))) * normal;
    vec2 gradient = abs(planeNormal.z) > 0.000001 ? clamp(-2.0 * planeNormal.xy / planeNormal.z, vec2(-16.0), vec2(16.0)) : vec2(0.0);
    vec2 grid = shadowUv / pixel - 0.5;
    vec2 base = floor(grid);
    vec2 phase = fract(grid);
    float blocked = 0.0;
    for (int y = -2; y <= 3; y++) {
        float wy = y == -2 ? 1.0 - phase.y : (y == 3 ? phase.y : 1.0);
        for (int x = -2; x <= 3; x++) {
            float wx = x == -2 ? 1.0 - phase.x : (x == 3 ? phase.x : 1.0);
            vec2 sampleUv = (base + vec2(x, y) + 0.5) * pixel;
            float receiverDepth = projected.z + dot(gradient, sampleUv - shadowUv);
            // All cascades have the same 255-block depth span; this bias is about 0.036 world blocks.
            float casterDepth = texture(map, sampleUv).r;
            if (MoonLight.z > 0.5) {
                float dynamicDepth = texture(entities, sampleUv).r;
#ifdef TEMPORAL_SHADOW
                // Includes animated block entities. Invalidate history wherever a dynamic tap contributes.
                if (dynamicDepth <= casterDepth && receiverDepth - 0.00014 > dynamicDepth) dynamicAffected = true;
#endif
                casterDepth = min(casterDepth, dynamicDepth);
            }
            blocked += (receiverDepth - 0.00014 > casterDepth ? 1.0 : 0.0) * wx * wy;
        }
    }
    vec2 edge = min(shadowUv, vec2(1.0) - shadowUv) / pixel;
    return blocked / 25.0 * smoothstep(3.0, 8.0, min(edge.x, edge.y));
}

float cascadeOcclusion(float distanceToCamera, vec3 position, vec3 normal) {
    // World-radius partitions: camera yaw/pitch never changes cascade selection.
    if (distanceToCamera < CascadeRanges.y) {
        float near = shadowOcclusion(ShadowMap, EntityShadowMap, LightMatrix[0], position, normal);
        if (distanceToCamera <= CascadeRanges.x) return near;
        float middle = shadowOcclusion(MiddleShadowMap, MiddleEntityShadowMap, LightMatrix[1], position, normal);
        return mix(near, middle, smoothstep(CascadeRanges.x, CascadeRanges.y, distanceToCamera));
    }
    if (distanceToCamera < CascadeRanges.w) {
        float middle = shadowOcclusion(MiddleShadowMap, MiddleEntityShadowMap, LightMatrix[1], position, normal);
        if (distanceToCamera <= CascadeRanges.z) return middle;
        float far = shadowOcclusion(FarShadowMap, FarEntityShadowMap, LightMatrix[2], position, normal);
        return mix(middle, far, smoothstep(CascadeRanges.z, CascadeRanges.w, distanceToCamera));
    }
    return shadowOcclusion(FarShadowMap, FarEntityShadowMap, LightMatrix[2], position, normal);
}


vec3 srgbToLinear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045)));
}
float energy(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    fragColor = vec4(0.0);
#ifdef TEMPORAL_SHADOW
    shadowTemporalInput = vec4(1.0,0.0,0.0,0.0);
#endif
    float depth = texture(SceneDepth, texCoord).r;
    float capturedDepth = texture(MaterialDepth, texCoord).r;
    vec4 geometryNormal = texture(MaterialNormal, texCoord);
    int difference = abs(int(floatBitsToUint(depth)) - int(floatBitsToUint(capturedDepth)));
    if (depth <= 0.0 || capturedDepth <= 0.0 || difference > 8 || geometryNormal.a < 0.5) {
        return;
    }
    vec4 material = texture(MaterialAlbedo, texCoord);
    vec4 properties = texture(MaterialEmission, texCoord);
    vec3 albedo = srgbToLinear(material.rgb);
    vec3 position = (ViewToWorld * vec4(reconstruct(texCoord, depth, inverse(ProjMat)), 1.0)).xyz;
    float distanceToCamera = length(position);
    vec3 normal = normalize(geometryNormal.xyz);
    int flags = int(round(material.a * 255.0));
    // Cutout foliage receives two-sided diffuse light. Never orient its normal to the camera:
    // crossed models contain opposing quads, and camera-facing flips change lighting at grazing angles.
    bool foliage = (flags & 1) != 0 && (flags & 16) == 0;
    float coverage = 1.0 - smoothstep(Coverage.x, Coverage.y, distanceToCamera);
    float occlusion = coverage > 0.0 && Coverage.z > 0.0 ? cascadeOcclusion(distanceToCamera, position, normal) : 0.0;
    float visibility = 1.0 - occlusion * coverage;
    float skyAccess = clamp(properties.b, 0.0, 1.0);
    vec3 sky = SkyColorStrength.rgb * SkyColorStrength.a * skyAccess
        * mix(0.45, 1.0, (foliage ? abs(normal.y) : normal.y) * 0.5 + 0.5);
    float directFacing = dot(normal, LightDirectionAndMask.xyz);
    vec3 direct = DirectColorStrength.rgb * DirectColorStrength.a
        * (foliage ? abs(directFacing) : max(directFacing, 0.0)) * skyAccess;
    // Keep all native block-light sources, including ones beyond the 16-light selection.
    // Replace this baseline only if the selected colored, visible reference has greater energy.
    vec3 blockBaseline = vec3(1.0, 0.78, 0.55) * 0.8 * properties.a * properties.a;
    vec3 selectedLocal = vec3(0.0);
    float localCoverage = 1.0 - smoothstep(16.0, 24.0, distanceToCamera);
    if (GridBoundsAndEnabled.w > 0.5 && localCoverage > 0.0) {
        for (int i = 0; i < 16; i++) {
            if (i >= int(GridOriginAndCount.w)) break;
            vec3 toLight = LightPositionRadius[i].xyz - position;
            float distanceToLight = length(toLight), radius = LightPositionRadius[i].w;
            if (distanceToLight >= radius || distanceToLight < 0.001) continue;
            float lightFacing = dot(normal, toLight / distanceToLight);
            float lambert = foliage ? abs(lightFacing) : max(lightFacing, 0.0);
            if (lambert <= 0.0 || LightColorStrength[i].w <= 0.0) continue;
            // Offset toward the emitter side for thin foliage, independent of the viewing side.
            vec3 emitterNormal = foliage && lightFacing < 0.0 ? -normal : normal;
            if (!visibleToEmitter(position + emitterNormal * 0.04, LightPositionRadius[i].xyz)) continue;
            float falloff = 1.0 - distanceToLight / radius;
            selectedLocal += LightColorStrength[i].rgb * LightColorStrength[i].w
                * falloff * falloff * lambert * 0.7 * localCoverage;
        }
    }
    float baselineEnergy = energy(blockBaseline);
    float replacement = smoothstep(baselineEnergy, baselineEnergy * 1.25 + 0.001, energy(selectedLocal));
    vec3 local = mix(blockBaseline, selectedLocal, replacement);
    // Reference emission color uses authored albedo, not the already-lit scene or block light.
    vec3 emission = albedo * max(properties.r, properties.g) * 2.4;
    vec3 radiance = albedo * (vec3(0.012) + sky + direct * visibility + local) + emission;
    fragColor = vec4(radiance, 1.0);
#ifdef TEMPORAL_SHADOW
    shadowTemporalInput = vec4(visibility, albedo * direct);
    // Display coverage stays valid; .75 marks pixels that must never enter shadow history.
    if (dynamicAffected || (flags & 24) != 0) fragColor.a = 0.75;
#endif
}
