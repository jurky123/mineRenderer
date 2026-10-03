#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
uniform sampler2D PbrIdsAtlas;
uniform sampler2D PbrNormalAtlas;
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

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 unlitTint;
layout(location = 2) flat in vec3 surfaceNormal;
layout(location = 3) flat in ivec2 emissionFlags;
layout(location = 4) in vec2 compatibilityLight;
layout(location=5) in vec3 materialPosition;
layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outEmission;
layout(location=3) out vec4 outMaterialPbr;
vec3 srgbToLinear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045)));
}
vec3 linearToSrgb(vec3 c) {
    return mix(1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, c * 12.92, lessThanEqual(c, vec3(0.0031308)));
}
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}


vec2 octEncode(vec3 n) {
    n/=abs(n.x)+abs(n.y)+abs(n.z);
    vec2 xy=n.xy;
    if(n.z<0.0)xy=(1.0-abs(xy.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(xy,vec2(0)));
    return xy*.5+.5;
}
void main() {
    // Derivatives are evaluated before alpha/marker discard, including neighboring helper lanes.
    vec3 dx=dFdx(materialPosition),dy=dFdy(materialPosition);
    vec2 ux=dFdx(texCoord),uy=dFdy(texCoord);
    float determinant=ux.x*uy.y-ux.y*uy.x;
    vec3 n=normalize(surfaceNormal),shadingNormal=n;
    vec2 mapXY=texture(PbrNormalAtlas,texCoord).rg*2.0-1.0;
    if(abs(determinant)>1e-12) {
        vec3 t=(dx*uy.y-dy*ux.y)/determinant;
        vec3 b=(dy*ux.x-dx*uy.x)/determinant;
        t-=n*dot(t,n);b-=n*dot(b,n);
        if(dot(t,t)>1e-10 && dot(b,b)>1e-10) {
            // Atlas V points down; this directly supplies LabPBR's DirectX Y-minus basis.
            vec3 tangentNormal=vec3(mapXY,sqrt(max(0.0,1.0-dot(mapXY,mapXY))));
            shadingNormal=normalize(normalize(t)*tangentNormal.x+normalize(b)*tangentNormal.y+n*tangentNormal.z);
        }
    }
    vec4 pbrId=texture(PbrIdsAtlas,texCoord);
    vec4 texel = UseRgss == 1 ? sampleRGSS(Sampler0, texCoord, 1.0 / vec2(TextureSize))
            : sampleNearest(Sampler0, texCoord, 1.0 / vec2(TextureSize));
    if(emissionFlags.x<0)discard; // native raw/unsupported emitters have no material marker
    int flags = emissionFlags.y >> 4;
    if ((flags & 1) != 0 && texel.a * unlitTint.a < 0.5) discard;
    // Encoded unlit albedo preserves dark RGBA8 colors. Decode once in the future lighting resolve.
    // Excludes native lightmap, face shading, vertex AO and fog.
    outAlbedo = vec4(linearToSrgb(srgbToLinear(texel.rgb) * srgbToLinear(unlitTint.rgb)), float(flags) / 255.0);
    // XYZ signed normal -> UNORM; alpha retains validity and native fade in [0,3].
    outNormal = vec4(normalize(surfaceNormal) * 0.5 + 0.5, 1.0 / 3.0);
#ifdef NATIVE_TERRAIN
    outNormal.a=(2.0+clamp(ChunkVisibility,0.0,1.0))/3.0;
#endif
    // Material strengths only. This is deliberately not a claim of emissive RGB radiance.
    outEmission = vec4(max(float(emissionFlags.x) / 15.0,pbrId.b*255.0/254.0), float(emissionFlags.y & 15) / 15.0, clamp(compatibilityLight.y, 0.0, 1.0), clamp(compatibilityLight.x, 0.0, 1.0));
    outMaterialPbr=vec4(pbrId.rg,octEncode(shadingNormal));
}
