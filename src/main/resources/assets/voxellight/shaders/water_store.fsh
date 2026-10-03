#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InputHdr;
uniform sampler2D SceneDepth;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;
    mat4 InvProjection;mat4 LightNormalMatrix[3];
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 hdr=texture(InputHdr,texCoord);
    float depth=texture(SceneDepth,texCoord).r;
    if(hdr.a<.5 || depth<=0.0)discard;
    vec4 view=InvProjection*vec4(texCoord*2.0-1.0,depth,1.0);
    fragColor=vec4(hdr.rgb,length(view.xyz/view.w));
}
