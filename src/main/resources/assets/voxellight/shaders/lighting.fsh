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
uniform sampler2D NextShadowMap;
uniform sampler2D MiddleNextShadowMap;
uniform sampler2D FarNextShadowMap;
uniform sampler2D EntityShadowMap;
uniform sampler2D MiddleEntityShadowMap;
uniform sampler2D FarEntityShadowMap;
uniform usampler2D VoxelOpacity;
uniform sampler2D ShapeBounds;
uniform sampler2D AmbientVisibility;
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
    vec4 EpochBlend; // x visibility blend; y surface PCF radius; w enables fixed-angle terrain epochs.

};
layout(std140) uniform LocalLightSettings {
    vec4 GridOriginAndCount;
    vec4 GridBoundsAndEnabled;
    vec4 MoonLight; // moon intensity, fine shapes, entity casters, one-based held-light slot (0 absent)
    vec4 LightPositionRadius[16];
    vec4 LightColorStrength[16];
};
layout(std140) uniform LightingEnvironment {
    vec4 DirectColorStrength;
    vec4 SkyColorStrength;
    vec4 HorizonColorLower;
};
layout(std140) uniform AoSettings {
    vec4 AoParameters; // Radius, strength, plane bias, maximum full-resolution screen radius.
    vec4 AoFilter; // Plane tolerance, normal threshold, enabled, debug view.
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#ifdef TEMPORAL_SHADOW
layout(location = 1) out vec4 shadowTemporalInput;
bool dynamicAffected = false;
#endif

vec2 aoSign(vec2 v) { return mix(vec2(-1),vec2(1),greaterThanEqual(v,vec2(0))); }
vec3 aoDecode(vec2 encoded) {
    vec2 p=encoded*2.0-1.0;
    vec3 n=vec3(p,1.0-abs(p.x)-abs(p.y));
    if(n.z<0.0)n.xy=(1.0-abs(n.yx))*aoSign(n.xy);
    return normalize(n);
}
vec2 aoEncode(vec3 n) {
    n/=abs(n.x)+abs(n.y)+abs(n.z);
    return (n.z>=0.0?n.xy:(1.0-abs(n.yx))*aoSign(n.xy))*.5+.5;
}
vec3 aoUnproject(vec2 uv,float depth) {
    vec4 p=InvProjection*vec4(uv*2.0-1.0,depth,1.0);
    return p.xyz/p.w;
}
// Guides correspond to the fixed (1,1) texel of each 2x2 group, clamped at odd dimensions.
vec3 aoGuidePosition(ivec2 halfPixel,float linearDepth,ivec2 fullSize) {
    vec2 uv=(vec2(min(halfPixel*2+1,fullSize-1))+.5)/vec2(fullSize);
    vec3 a=aoUnproject(uv,1.0),b=aoUnproject(uv,.5);
    vec3 ray=b-a;
    return a+ray*((-linearDepth-a.z)/ray.z);
}
float aoGuideWeight(vec3 center,vec3 normal,vec3 samplePosition,vec3 sampleNormal) {
    float facing=dot(normal,sampleNormal);
    float plane=abs(dot(normal,samplePosition-center));
    float tolerance=AoFilter.x+.002*abs(center.z);
    if(facing<AoFilter.y || plane>tolerance || length(samplePosition-center)>AoParameters.x)return 0.0;
    return pow(max(facing,0.0),8.0)*exp(-plane*plane/(tolerance*tolerance*.25));
}
float ambientVisibility(vec3 worldNormal,vec3 position,int flags) {
    // First AO phase is opaque terrain only; cutout, unshaded and entity receivers stay neutral.
    if(AoFilter.z<.5 || (flags&21)!=0)return 1.0;
    ivec2 size=textureSize(AmbientVisibility,0),fullSize=textureSize(SceneDepth,0);
    ivec2 fullPixel=ivec2(gl_FragCoord.xy);
    vec2 grid=(vec2(fullPixel)-1.0)*.5;
    if((fullSize.x&1)!=0 && fullPixel.x==fullSize.x-1)grid.x=float(size.x-1);
    if((fullSize.y&1)!=0 && fullPixel.y==fullSize.y-1)grid.y=float(size.y-1);
    ivec2 base=ivec2(floor(grid));vec2 phase=fract(grid);
    vec3 normal=normalize(transpose(mat3(ViewToWorld))*worldNormal);
    float sum=0.0,weight=0.0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        ivec2 pixel=base+ivec2(x,y);
        if(any(lessThan(pixel,ivec2(0))) || any(greaterThanEqual(pixel,size)))continue;
        vec4 guide=texelFetch(AmbientVisibility,pixel,0);
        if(guide.a<=0.0)continue;
        float w=(x==0?1.0-phase.x:phase.x)*(y==0?1.0-phase.y:phase.y)
            *aoGuideWeight(position,normal,aoGuidePosition(pixel,guide.a,fullSize),aoDecode(guide.gb));
        sum+=guide.r*w;weight+=w;
    }
    return weight>.00001?mix(1.0,sum/weight,AoParameters.y):1.0;
}

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
    // Integer-radius phase weights keep filtering continuous as the map scrolls.
    int radius=clamp(int(round(EpochBlend.y)),0,2);
    float width=float(2*radius+1);
    for (int y = -radius; y <= radius+1; y++) {
        float wy = y == -radius ? 1.0 - phase.y : (y == radius+1 ? phase.y : 1.0);
        for (int x = -radius; x <= radius+1; x++) {
            float wx = x == -radius ? 1.0 - phase.x : (x == radius+1 ? phase.x : 1.0);
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
    return blocked / (width*width) * smoothstep(3.0, 8.0, min(edge.x, edge.y));
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
    // Integer-radius phase weights keep filtering continuous as the map scrolls.
    int radius=clamp(int(round(EpochBlend.y)),0,2);
    float width=float(2*radius+1);
    for (int y = -radius; y <= radius+1; y++) {
        float wy = y == -radius ? 1.0 - phase.y : (y == radius+1 ? phase.y : 1.0);
        for (int x = -radius; x <= radius+1; x++) {
            float wx = x == -radius ? 1.0 - phase.x : (x == radius+1 ? phase.x : 1.0);
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
    return blocked / (width*width) * smoothstep(3.0, 8.0, min(edge.x, edge.y));
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


vec3 srgbToLinear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045)));
}
float energy(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
uniform sampler2D MaterialPbr;
uniform sampler2D MaterialTable;
layout(std140) uniform PbrSettings { vec4 PbrControls; };
vec3 octDecode(vec2 e) {
    vec2 f=e*2.0-1.0;vec3 n=vec3(f,1.0-abs(f.x)-abs(f.y));
    if(n.z<0.0)n.xy=(1.0-abs(n.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(n.xy,vec2(0)));
    return normalize(n);
}
vec3 fresnel(vec3 f0,float cosine){return f0+(1.0-f0)*pow(1.0-clamp(cosine,0.0,1.0),5.0);}
vec3 conductorF0(int metal,vec3 albedo) {
    // LabPBR predefined conductors; RGB optical constants, normal-incidence Fresnel.
    const vec3 ns[8]=vec3[](vec3(2.9114,2.9497,2.5845),vec3(.18299,.42108,1.3734),vec3(1.3456,.96521,.61722),vec3(3.1071,3.1812,2.3230),vec3(.27105,.67693,1.3164),vec3(1.91,1.83,1.44),vec3(2.3757,2.0847,1.8453),vec3(.15943,.14512,.13547));
    const vec3 ks[8]=vec3[](vec3(3.0893,2.9318,2.7670),vec3(3.4242,2.3459,1.7704),vec3(7.4746,6.3995,5.3031),vec3(3.3314,3.3291,3.1350),vec3(3.6092,2.6248,2.2921),vec3(3.51,3.4,3.18),vec3(4.2655,3.7153,3.1365),vec3(3.9291,3.19,2.3808));
    if(metal<230||metal>237)return albedo;
    vec3 n=ns[metal-230],k=ks[metal-230];return ((n-1.0)*(n-1.0)+k*k)/((n+1.0)*(n+1.0)+k*k);
}
vec3 ggx(vec3 n,vec3 v,vec3 l,vec3 f0,float alpha) {
    float nl=max(dot(n,l),0.0),nv=max(dot(n,v),0.001);
    if(nl<=0.0||dot(v+l,v+l)<1e-8)return vec3(0);
    vec3 h=normalize(v+l);float nh=max(dot(n,h),0.0),a2=alpha*alpha;
    float d=a2/(3.14159265*pow(nh*nh*(a2-1.0)+1.0,2.0));
    float gv=2.0*nv/(nv+sqrt(a2+(1.0-a2)*nv*nv));
    float gl=2.0*nl/(nl+sqrt(a2+(1.0-a2)*nl*nl));
    return fresnel(f0,max(dot(v,h),0.0))*d*gv*gl/(4.0*nv*max(nl,.001))*nl*3.14159265;
}
void main() {
    fragColor = vec4(0.0);
#ifdef TEMPORAL_SHADOW
    shadowTemporalInput = vec4(1.0,0.0,0.0,0.0);
#endif
    float depth = texture(SceneDepth, texCoord).r;
    float capturedDepth = texture(MaterialDepth, texCoord).r;
    vec4 geometryNormal = texture(MaterialNormal, texCoord) * vec4(2,2,2,3) - vec4(1,1,1,0);
    int difference = abs(int(floatBitsToUint(depth)) - int(floatBitsToUint(capturedDepth)));
    if (depth <= 0.0 || capturedDepth <= 0.0 || difference > 8 || geometryNormal.a < 0.5) {
        return;
    }
    vec4 material = texture(MaterialAlbedo, texCoord);
    vec4 properties = texture(MaterialEmission, texCoord);
    vec3 albedo = srgbToLinear(material.rgb);
    vec3 position = (ViewToWorld * vec4(reconstruct(texCoord, depth, InvProjection), 1.0)).xyz;
    float distanceToCamera = length(position);
    vec3 normal = normalize(geometryNormal.xyz);
    int flags = int(round(material.a * 255.0));
    vec3 shadingNormal=normal;
    vec4 packedPbr=texture(MaterialPbr,texCoord);
    ivec2 idBytes=ivec2(round(packedPbr.rg*255.0));
    vec4 pbr=texelFetch(MaterialTable,ivec2(idBytes.x,idBytes.y),0);
    bool usePbr=PbrControls.x>.5 && (flags&21)==0;
    float alpha=max(.045,(1.0-pbr.r)*(1.0-pbr.r));
    int reflectance=int(round(pbr.g*255.0));bool metal=reflectance>=230;
    float porosity=pbr.b*255.0<=64.0?pbr.b*255.0/64.0:0.0;
    vec3 f0=metal?conductorF0(reflectance,albedo):vec3(pbr.g);
    vec3 authoredAlbedo=albedo;
    if(usePbr) {
        shadingNormal=octDecode(packedPbr.ba);
        float wet=clamp(PbrControls.y,0.0,1.0)*properties.b*smoothstep(.2,.9,normal.y);
        albedo*=1.0-wet*porosity*.28;
        alpha=mix(alpha,max(.045,alpha*.2),wet*(1.0-porosity));
    }
    vec3 viewDirection=normalize(-position);
    if(PbrControls.z>.5) {
        vec3 debugColor=PbrControls.z<1.5?vec3(alpha):PbrControls.z<2.5?(metal?vec3(1,.65,.1):vec3(.15)):shadingNormal*.5+.5;
        fragColor=vec4(debugColor,1);return;
    }
    vec3 diffuseWeight=usePbr?(1.0-fresnel(f0,max(dot(shadingNormal,viewDirection),0.0)))*(metal?0.0:1.0):vec3(1);
    vec3 viewPosition=reconstruct(texCoord,depth,InvProjection);
    float ao=ambientVisibility(normal,viewPosition,flags);
    if(AoFilter.w>.5) { fragColor=vec4(vec3(ao),1.0);return; }
    // Cutout foliage receives two-sided diffuse light. Never orient its normal to the camera:
    // crossed models contain opposing quads, and camera-facing flips change lighting at grazing angles.
    bool foliage = (flags & 1) != 0 && (flags & 16) == 0;
    float coverage = 1.0 - smoothstep(Coverage.x, Coverage.y, distanceToCamera);
    float occlusion = coverage > 0.0 && Coverage.z > 0.0 ? cascadeOcclusion(distanceToCamera, position, normal) : 0.0;
    float visibility = 1.0 - occlusion * coverage;
    float skyAccess = clamp(properties.b, 0.0, 1.0);
    float skyFacing = (foliage ? abs(normal.y) : normal.y) * 0.5 + 0.5;
    vec3 sky = mix(HorizonColorLower.rgb, SkyColorStrength.rgb, max(normal.y, 0.0))
        * SkyColorStrength.a * skyAccess * mix(HorizonColorLower.a, 1.0, skyFacing);
    float directFacing = dot(shadingNormal, LightDirectionAndMask.xyz);
    vec3 direct = DirectColorStrength.rgb * DirectColorStrength.a
        * (foliage ? abs(directFacing) : max(directFacing, 0.0)) * skyAccess;
    // Keep all native block-light sources, including ones beyond the 16-light selection.
    // Replace this baseline only if the selected colored, visible reference has greater energy.
    vec3 blockBaseline = vec3(1.0, 0.78, 0.55) * 0.8 * properties.a * properties.a;
    vec3 selectedLocal = vec3(0.0), heldLocal = vec3(0.0),selectedSpecular=vec3(0),heldSpecular=vec3(0);
    float localCoverage = 1.0 - smoothstep(16.0, 24.0, distanceToCamera);
    if (GridBoundsAndEnabled.w > 0.5 && localCoverage > 0.0) {
        for (int i = 0; i < 16; i++) {
            if (i >= int(GridOriginAndCount.w)) break;
            vec3 toLight = LightPositionRadius[i].xyz - position;
            float distanceToLight = length(toLight), radius = LightPositionRadius[i].w;
            if (distanceToLight >= radius || distanceToLight < 0.001) continue;
            float lightFacing = dot(shadingNormal, toLight / distanceToLight);
            float lambert = foliage ? abs(lightFacing) : max(lightFacing, 0.0);
            if (lambert <= 0.0 || LightColorStrength[i].w <= 0.0) continue;
            // Offset toward the emitter side for thin foliage, independent of the viewing side.
            vec3 emitterNormal = foliage && lightFacing < 0.0 ? -normal : normal;
            if (!visibleToEmitter(position + emitterNormal * 0.04, LightPositionRadius[i].xyz)) continue;
            float falloff = 1.0 - distanceToLight / radius;
            vec3 contribution = LightColorStrength[i].rgb * LightColorStrength[i].w
                * falloff * falloff * lambert * 0.7 * localCoverage;
            vec3 specular=usePbr?LightColorStrength[i].rgb*LightColorStrength[i].w*falloff*falloff*.7*localCoverage*ggx(shadingNormal,viewDirection,toLight/distanceToLight,f0,alpha):vec3(0);
            if(i+1==int(MoonLight.w)){heldLocal+=contribution;heldSpecular+=specular;}
            else {selectedLocal+=contribution;selectedSpecular+=specular;}
        }
    }
    float baselineEnergy = energy(blockBaseline);
    float replacement = smoothstep(baselineEnergy, baselineEnergy * 1.25 + 0.001, energy(selectedLocal));
    // Reference emission color uses authored albedo, not the already-lit scene or block light.
    vec3 emission = authoredAlbedo * max(properties.r, properties.g) * 2.4;
    // AO modulates diffuse ambient/unshadowed block fill, never direct lamps, sun/moon or emission.
    vec3 ambient=vec3(0.012)+sky+blockBaseline*(1.0-replacement);
    vec3 sunSpecular=usePbr?DirectColorStrength.rgb*DirectColorStrength.a*skyAccess*ggx(shadingNormal,viewDirection,LightDirectionAndMask.xyz,f0,alpha):vec3(0);
    vec3 reflected=reflect(-viewDirection,shadingNormal);
    vec3 environmentSpecular=usePbr?mix(HorizonColorLower.rgb,SkyColorStrength.rgb,max(reflected.y,0.0))*SkyColorStrength.a*skyAccess*fresnel(f0,max(dot(shadingNormal,viewDirection),0.0))*mix(1.0,.45,alpha)*ao:vec3(0);
    vec3 directContribution=albedo*diffuseWeight*direct+sunSpecular;
    vec3 radiance=albedo*diffuseWeight*(ambient*ao*(usePbr?pbr.a:1.0)+selectedLocal*replacement+heldLocal)+directContribution*visibility+selectedSpecular*replacement+heldSpecular+environmentSpecular+emission;
    fragColor = vec4(radiance, 1.0);
#ifdef TEMPORAL_SHADOW
    shadowTemporalInput = vec4(visibility, directContribution);
    // Display coverage stays valid; .75 marks pixels that must never enter shadow history.
    if (dynamicAffected || (flags & 24) != 0) fragColor.a = 0.75;
#endif
}
