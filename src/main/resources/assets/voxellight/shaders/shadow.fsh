#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D ShadowMap;
uniform sampler2D MiddleShadowMap;
uniform sampler2D FarShadowMap;
uniform sampler2D NextShadowMap;
uniform sampler2D MiddleNextShadowMap;
uniform sampler2D FarNextShadowMap;
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
    mat4 InvProjection;
    mat4 LightNormalMatrix[3];
    mat4 TerrainLightMatrix[3];mat4 TerrainNormalMatrix[3];
    mat4 NextLightMatrix[3];mat4 NextNormalMatrix[3];
    vec4 EpochBlend; // Visibility interpolation weight; w enables fixed-angle terrain epochs.

};
layout(std140) uniform LocalLightSettings {
    vec4 GridOriginAndCount;
    vec4 GridBoundsAndEnabled;
    vec4 MoonLight;
    vec4 LightPositionRadius[16];
    vec4 LightColorStrength[16];
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

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

float shadowOcclusion(sampler2D map, sampler2D entities, mat4 matrix, mat3 normalMatrix, vec3 position, vec3 normal) {
    vec4 clip = matrix * vec4(position, 1.0);
    vec3 projected = clip.xyz / clip.w;
    vec2 shadowUv = projected.xy * 0.5 + 0.5;
    vec2 pixel = 1.0 / vec2(textureSize(map, 0));
    if (projected.z < 0.0 || projected.z > 1.0 || any(lessThan(shadowUv, pixel * 3.0))
        || any(greaterThan(shadowUv, vec2(1.0) - pixel * 3.0))) return 0.0;
    vec3 planeNormal = normalMatrix * normal;
    vec2 gradient = abs(planeNormal.z) > 0.000001 ? clamp(-2.0 * planeNormal.xy / planeNormal.z, vec2(-16.0), vec2(16.0)) : vec2(0.0);
    vec2 grid = shadowUv / pixel - 0.5;
    vec2 base = floor(grid);
    vec2 phase = fract(grid);
    // Keep the accepted ~0.036-block bias despite different cascade depth spans.
    float depthBias = 0.0357 * length(vec3(matrix[0].z, matrix[1].z, matrix[2].z));
    float blocked = 0.0;
    for (int y = -2; y <= 3; y++) {
        float wy = y == -2 ? 1.0 - phase.y : (y == 3 ? phase.y : 1.0);
        for (int x = -2; x <= 3; x++) {
            float wx = x == -2 ? 1.0 - phase.x : (x == 3 ? phase.x : 1.0);
            vec2 sampleUv = (base + vec2(x, y) + 0.5) * pixel;
            float receiverDepth = projected.z + dot(gradient, sampleUv - shadowUv);
            float casterDepth = texture(map, sampleUv).r;
            if (MoonLight.z > 0.5) {
                float dynamicDepth = texture(entities, sampleUv).r;
#ifdef TEMPORAL_SHADOW
                // Includes animated block entities. Invalidate history wherever a dynamic tap contributes.
                if (dynamicDepth <= casterDepth && receiverDepth - depthBias > dynamicDepth) dynamicAffected = true;
#endif
                casterDepth = min(casterDepth, dynamicDepth);
            }
            blocked += (receiverDepth - depthBias > casterDepth ? 1.0 : 0.0) * wx * wy;
        }
    }
    vec2 edge = min(shadowUv, vec2(1.0) - shadowUv) / pixel;
    return blocked / 25.0 * smoothstep(3.0, 8.0, min(edge.x, edge.y));
}

float epochMapOcclusion(sampler2D map, sampler2D entities, int cascade, mat4 matrix, mat3 normalMatrix, vec3 position, vec3 normal) {
    vec4 clip = matrix * vec4(position, 1.0);
    vec3 projected = clip.xyz / clip.w;
    vec2 shadowUv = projected.xy * 0.5 + 0.5;
    vec2 pixel = 1.0 / vec2(textureSize(map, 0));
    if (projected.z < 0.0 || projected.z > 1.0 || any(lessThan(shadowUv, pixel * 3.0))
        || any(greaterThan(shadowUv, vec2(1.0) - pixel * 3.0))) return 0.0;
    vec3 planeNormal = normalMatrix * normal;
    vec2 gradient = abs(planeNormal.z) > 0.000001 ? clamp(-2.0 * planeNormal.xy / planeNormal.z, vec2(-16.0), vec2(16.0)) : vec2(0.0);
    vec2 grid = shadowUv / pixel - 0.5;
    vec2 base = floor(grid);
    vec2 phase = fract(grid);
    // Keep the accepted ~0.036-block bias despite different cascade depth spans.
    float depthBias = 0.0357 * length(vec3(matrix[0].z, matrix[1].z, matrix[2].z));
    vec3 dynamicCenter=(LightMatrix[cascade]*vec4(position,1)).xyz;
    // Inverse-transpose normal transform also supplies inverse light-space offsets.
    mat3 toDynamic=mat3(LightMatrix[cascade])*transpose(normalMatrix);
    float dynamicBias=.0357*length(vec3(LightMatrix[cascade][0].z,LightMatrix[cascade][1].z,LightMatrix[cascade][2].z));
    float blocked = 0.0;
    for (int y = -2; y <= 3; y++) {
        float wy = y == -2 ? 1.0 - phase.y : (y == 3 ? phase.y : 1.0);
        for (int x = -2; x <= 3; x++) {
            float wx = x == -2 ? 1.0 - phase.x : (x == 3 ? phase.x : 1.0);
            vec2 sampleUv = (base + vec2(x, y) + 0.5) * pixel;
            float receiverDepth = projected.z + dot(gradient, sampleUv - shadowUv);
            float casterDepth = texture(map, sampleUv).r;
            bool dynamicBlocked=false;
            if(MoonLight.z>.5) {
                vec3 samplePoint=dynamicCenter+toDynamic*vec3((sampleUv-shadowUv)*2.0,receiverDepth-projected.z);
                vec2 dynamicUv=samplePoint.xy*.5+.5;
                if(samplePoint.z>=0.0 && samplePoint.z<=1.0 && all(greaterThanEqual(dynamicUv,vec2(0))) && all(lessThanEqual(dynamicUv,vec2(1))))
                    dynamicBlocked=samplePoint.z-dynamicBias>texture(entities,dynamicUv).r;
#ifdef TEMPORAL_SHADOW
                if(dynamicBlocked)dynamicAffected=true;
#endif
            }
            blocked += (receiverDepth-depthBias>casterDepth || dynamicBlocked ? 1.0 : 0.0) * wx * wy;
        }
    }
    vec2 edge = min(shadowUv, vec2(1.0) - shadowUv) / pixel;
    return blocked / 25.0 * smoothstep(3.0, 8.0, min(edge.x, edge.y));
}

float epochOcclusion(sampler2D first,sampler2D next,sampler2D entities,int cascade,vec3 position,vec3 normal) {
    if(EpochBlend.w<.5)return shadowOcclusion(first,entities,LightMatrix[cascade],mat3(LightNormalMatrix[cascade]),position,normal);
    float terrain=epochMapOcclusion(first,entities,cascade,TerrainLightMatrix[cascade],mat3(TerrainNormalMatrix[cascade]),position,normal);
    if(EpochBlend.x>0.0)terrain=mix(terrain,epochMapOcclusion(next,entities,cascade,NextLightMatrix[cascade],mat3(NextNormalMatrix[cascade]),position,normal),EpochBlend.x);
    return terrain;
}

float cascadeOcclusion(float distanceToCamera, vec3 position, vec3 normal) {
    // World-radius partitions: camera yaw/pitch never changes cascade selection.
    if (distanceToCamera < CascadeRanges.y) {
        float near = epochOcclusion(ShadowMap, NextShadowMap, EntityShadowMap, 0, position, normal);
        if (distanceToCamera <= CascadeRanges.x) return near;
        float middle = epochOcclusion(MiddleShadowMap, MiddleNextShadowMap, MiddleEntityShadowMap, 1, position, normal);
        return mix(near, middle, smoothstep(CascadeRanges.x, CascadeRanges.y, distanceToCamera));
    }
    if (distanceToCamera < CascadeRanges.w) {
        float middle = epochOcclusion(MiddleShadowMap, MiddleNextShadowMap, MiddleEntityShadowMap, 1, position, normal);
        if (distanceToCamera <= CascadeRanges.z) return middle;
        float far = epochOcclusion(FarShadowMap, FarNextShadowMap, FarEntityShadowMap, 2, position, normal);
        return mix(middle, far, smoothstep(CascadeRanges.z, CascadeRanges.w, distanceToCamera));
    }
    return epochOcclusion(FarShadowMap, FarNextShadowMap, FarEntityShadowMap, 2, position, normal);
}

float planeError(float center, float nearDepth, float farDepth) {
    // Hardware depth is affine across a projected triangle. Two-sample extrapolation distinguishes
    // a sloped same-surface neighbor from another block that happens to have a closer depth.
    return nearDepth > 0.0 && farDepth > 0.0 ? abs(2.0 * nearDepth - farDepth - center) : 1e20;
}

void main() {
    vec4 color = texture(SceneColor, texCoord);
    float depth = texture(SceneDepth, texCoord).r;
    if (depth <= 0.0) {
        fragColor = LightDirectionAndMask.w > 0.5 ? vec4(1.0) : color;
        return;
    }
    mat4 inverseProjection = InvProjection;
    vec3 viewPosition = reconstruct(texCoord, depth, inverseProjection);
    vec3 position = (ViewToWorld * vec4(viewPosition, 1.0)).xyz;
    float distanceToCamera = length(position);
    float coverage = 1.0 - smoothstep(Coverage.x, Coverage.y, distanceToCamera);
    float localCoverage = 1.0 - smoothstep(16.0, 24.0, distanceToCamera);
    if (LightDirectionAndMask.w > 1.5) {
        float nearWeight = 1.0 - smoothstep(CascadeRanges.x, CascadeRanges.y, distanceToCamera);
        float farWeight = smoothstep(CascadeRanges.z, CascadeRanges.w, distanceToCamera);
        vec3 ranges = vec3(0.2, 0.85, 0.3) * nearWeight
            + vec3(1.0, 0.6, 0.15) * (1.0 - nearWeight) * (1.0 - farWeight)
            + vec3(0.25, 0.45, 1.0) * (1.0 - nearWeight) * farWeight;
        fragColor = vec4(mix(color.rgb * 0.35, ranges, 0.65 * coverage), color.a);
        return;
    }
    vec2 scenePixel = 1.0 / vec2(textureSize(SceneDepth, 0));
    float leftDepth = texture(SceneDepth, texCoord - vec2(scenePixel.x, 0.0)).r;
    float rightDepth = texture(SceneDepth, texCoord + vec2(scenePixel.x, 0.0)).r;
    float downDepth = texture(SceneDepth, texCoord - vec2(0.0, scenePixel.y)).r;
    float upDepth = texture(SceneDepth, texCoord + vec2(0.0, scenePixel.y)).r;
    float leftError = planeError(depth, leftDepth, texture(SceneDepth, texCoord - vec2(scenePixel.x * 2.0, 0.0)).r);
    float rightError = planeError(depth, rightDepth, texture(SceneDepth, texCoord + vec2(scenePixel.x * 2.0, 0.0)).r);
    float downError = planeError(depth, downDepth, texture(SceneDepth, texCoord - vec2(0.0, scenePixel.y * 2.0)).r);
    float upError = planeError(depth, upDepth, texture(SceneDepth, texCoord + vec2(0.0, scenePixel.y * 2.0)).r);
    float tolerance = max(depth * 0.000001, 0.0000000001);
    float xSign = abs(leftError - rightError) <= tolerance
        ? (abs(leftDepth - depth) < abs(rightDepth - depth) ? -1.0 : 1.0) : (leftError < rightError ? -1.0 : 1.0);
    float ySign = abs(downError - upError) <= tolerance
        ? (abs(downDepth - depth) < abs(upDepth - depth) ? -1.0 : 1.0) : (downError < upError ? -1.0 : 1.0);
    float xDepth = xSign < 0.0 ? leftDepth : rightDepth;
    float yDepth = ySign < 0.0 ? downDepth : upDepth;
    if (xDepth <= 0.0) xDepth = depth;
    if (yDepth <= 0.0) yDepth = depth;
    vec3 dx = mat3(ViewToWorld) * (reconstruct(texCoord + vec2(scenePixel.x * xSign, 0.0), xDepth, inverseProjection) - viewPosition);
    vec3 dy = mat3(ViewToWorld) * (reconstruct(texCoord + vec2(0.0, scenePixel.y * ySign), yDepth, inverseProjection) - viewPosition);
    vec3 normal = cross(dx, dy);
    if (dot(normal, position) > 0.0) normal = -normal;
    float normalLength = length(normal);
    normal = normalLength > 0.000000000001 ? normal / normalLength : vec3(0.0, 1.0, 0.0);
    // Continuously stabilize near-axis block normals; a hard 0.98 threshold used to switch abruptly.
    vec3 axis = abs(normal);
    vec3 dominant = axis.x >= axis.y && axis.x >= axis.z ? vec3(sign(normal.x), 0.0, 0.0)
        : (axis.y >= axis.z ? vec3(0.0, sign(normal.y), 0.0) : vec3(0.0, 0.0, sign(normal.z)));
    normal = normalize(mix(normal, dominant, smoothstep(0.94, 0.995, max(axis.x, max(axis.y, axis.z)))));
    float facing = Coverage.w > 0.5 ? smoothstep(0.0, 0.3, dot(normal, LightDirectionAndMask.xyz)) : 1.0;
    float occlusion = coverage > 0.0 && Coverage.z > 0.0 ? cascadeOcclusion(distanceToCamera, position, normal) : 0.0;
    float blocked = occlusion * coverage * facing;
    if (LightDirectionAndMask.w > 0.5) {
        fragColor = vec4(vec3(1.0 - blocked), 1.0);
        return;
    }
    vec3 illumination = vec3(0.0);
    // Actual cool fill, rather than only darkening the already dim vanilla nighttime image.
    illumination += vec3(0.14, 0.20, 0.34) * (MoonLight.x / 0.22) * facing * (1.0 - occlusion) * coverage;
    if (GridBoundsAndEnabled.w > 0.5 && localCoverage > 0.0) {
        for (int i = 0; i < 16; i++) {
            if (i >= int(GridOriginAndCount.w)) break;
            vec3 toLight = LightPositionRadius[i].xyz - position;
            float distanceToLight = length(toLight);
            float radius = LightPositionRadius[i].w;
            if (distanceToLight >= radius || distanceToLight < 0.001) continue;
            float lambert = max(dot(normal, toLight / distanceToLight), 0.0);
            if (lambert <= 0.0 || LightColorStrength[i].w <= 0.0) continue;
            if (!visibleToEmitter(position + normal * 0.04, LightPositionRadius[i].xyz)) continue;
            float falloff = 1.0 - distanceToLight / radius;
            illumination += LightColorStrength[i].rgb * LightColorStrength[i].w * falloff * falloff * lambert * 0.7 * localCoverage;
        }
    }
    // Color is already lit LDR; this is an artistic fill, not material-correct deferred lighting/GI.
    vec3 receiverColor = max(color.rgb, vec3(0.12));
    vec3 result = color.rgb * (1.0 - Coverage.z * blocked) + receiverColor * min(illumination, vec3(0.8));
    fragColor = vec4(clamp(result, 0.0, 1.0), color.a);
}
