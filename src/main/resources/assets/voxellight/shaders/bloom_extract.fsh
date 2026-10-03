#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MaterialAlbedo;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialEmission;
uniform sampler2D MaterialDepth;
uniform sampler2D SceneDepth;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec3 srgbToLinear(vec3 c) {
    return mix(pow((c+.055)/1.055,vec3(2.4)),c/12.92,lessThanEqual(c,vec3(.04045)));
}
void main() {
    ivec2 size=textureSize(SceneDepth,0);
    ivec2 base=ivec2(gl_FragCoord.xy)*4;
    vec3 emission=vec3(0.0);
    // Area average, rather than a center sample: thin torches still contribute at quarter resolution.
    for(int y=0;y<4;y++)for(int x=0;x<4;x++) {
        ivec2 p=base+ivec2(x,y);
        if(any(greaterThanEqual(p,size)))continue;
        float scene=texelFetch(SceneDepth,p,0).r, surface=texelFetch(MaterialDepth,p,0).r;
        if(texelFetch(MaterialNormal,p,0).a*3.0<.5 || scene<=0.0 || surface<=0.0 || abs(int(floatBitsToUint(scene))-int(floatBitsToUint(surface)))>8)continue;
        vec4 properties=texelFetch(MaterialEmission,p,0);
        emission+=srgbToLinear(texelFetch(MaterialAlbedo,p,0).rgb)*max(properties.r,properties.g)*2.4;
    }
    fragColor=vec4(emission/16.0,1.0);
}
