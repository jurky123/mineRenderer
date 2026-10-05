#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentSample;
uniform sampler2D History;
layout(std140) uniform AccumulationSettings { vec4 accumulation; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    vec4 current=texture(CurrentSample,texCoord);
    // First sample must not depend on uninitialized or stale history.
    if(accumulation.y<.5){fragColor=current;return;}
    vec4 prior=texture(History,texCoord);
    fragColor=prior+(current-prior)*accumulation.x;
}
