#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D RtNormal;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// Match the existing full-reference linear HDR display curve; fixed 0.75 EV for this experiment.
vec3 filmic(vec3 x){
    vec3 small=(x*(.15*x+.05)+.004)/(x*(.15*x+.5)+.06)-.02/.3;
    vec3 safe=max(x,vec3(1));vec3 large=(.15*safe+.05+.004/safe)/(.15*safe+.5+.06/safe)-.02/.3;
    return mix(small,large,greaterThan(x,vec3(1)));
}
void main(){
    vec3 hdr=texture(RtNormal,texCoord).rgb;
    if(any(isnan(hdr))||any(isinf(hdr))){fragColor=vec4(0,0,0,1);return;}
    vec3 linear=clamp(filmic(clamp(hdr,vec3(0),vec3(1e18))*exp2(.75))/filmic(vec3(6)),0,1);
    vec3 encoded=mix(1.055*pow(linear,vec3(1/2.4))-.055,linear*12.92,lessThanEqual(linear,vec3(.0031308)));
    fragColor=vec4(encoded,1);
}
