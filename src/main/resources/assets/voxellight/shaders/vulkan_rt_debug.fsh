#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D RtNormal;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 hit=texture(RtNormal,texCoord);
    bool finiteSignal=!any(isnan(hit))&&!any(isinf(hit));
    vec3 color=finiteSignal?hit.rgb:vec3(1.0,0.0,0.0);
    // Cyan border proves this fullscreen display pass ran, independently of its sampler.
    if(texCoord.x<0.003||texCoord.y<0.003||texCoord.x>0.997||texCoord.y>0.997)color=vec3(0.0,1.0,1.0);
    fragColor=vec4(color,1.0);
}
