#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentSample;
uniform sampler2D History;
layout(std140) uniform AccumulationSettings { vec4 accumulation; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
bool finiteRgb(vec3 value){return !any(isnan(value))&&!any(isinf(value));}
vec4 accumulateSample(vec4 current,vec4 prior,float target){
    if(!finiteRgb(prior.rgb)||isnan(prior.a)||isinf(prior.a))prior=vec4(0);
    if(current.a<=.5||isnan(current.a)||isinf(current.a)||!finiteRgb(current.rgb))return prior;
    float count=min(max(prior.a,0)+1,target);
    return vec4(prior.rgb*(1-1/count)+max(current.rgb,vec3(0))/count,count);
}
void main(){
    vec4 prior=vec4(0);
    if(accumulation.y>.5)prior=texture(History,texCoord);
    fragColor=accumulateSample(texture(CurrentSample,texCoord),prior,accumulation.x);
}
