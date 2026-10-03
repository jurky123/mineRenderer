#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(std140) uniform EntityMaterialSettings { vec4 Settings; };
layout(location=0) in vec2 texCoord;
layout(location=1) in vec4 unlitTint;
layout(location=2) in vec3 surfaceNormal;
layout(location=3) flat in ivec2 overlayCoords;
layout(location=4) in vec2 compatibilityLight;
layout(location=0) out vec4 outAlbedo;
layout(location=1) out vec4 outNormal;
layout(location=2) out vec4 outEmission;
layout(location=3) out vec4 outMaterialPbr;
vec3 srgbToLinear(vec3 c) {
    return mix(pow((c+0.055)/1.055,vec3(2.4)),c/12.92,lessThanEqual(c,vec3(0.04045)));
}
vec3 linearToSrgb(vec3 c) {
    return mix(1.055*pow(max(c,vec3(0)),vec3(1.0/2.4))-0.055,c*12.92,lessThanEqual(c,vec3(0.0031308)));
}
void main() {
    vec4 texel=texture(Sampler0,texCoord);
    // Native entity cutout tests texture alpha at .1, before vertex tint.
    if(texel.a<Settings.x)discard;
    vec3 base=srgbToLinear(texel.rgb)*srgbToLinear(unlitTint.rgb);
    if(Settings.z>0.5) {
        vec4 overlay=texelFetch(Sampler1,overlayCoords,0);
        base=mix(srgbToLinear(overlay.rgb),base,overlay.a);
    }
    outAlbedo=vec4(linearToSrgb(base),Settings.y/255.0);
    outNormal=vec4(normalize(gl_FrontFacing?surfaceNormal:-surfaceNormal)*0.5+0.5,(1.0-Settings.w)/3.0);
    // Packed brightness is compatibility illumination, never inferred emission.
    outEmission=vec4(0,0,clamp(compatibilityLight.y,0,1),clamp(compatibilityLight.x,0,1));
    outMaterialPbr=vec4(0); // Neutral ID; entity BRDF remains compatibility diffuse.
}
