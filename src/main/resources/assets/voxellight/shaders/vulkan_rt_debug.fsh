#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D RtNormal;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 hit=texture(RtNormal,texCoord);
    fragColor=vec4(hit.a>0.5?hit.rgb:vec3(0.025),1.0);
}
