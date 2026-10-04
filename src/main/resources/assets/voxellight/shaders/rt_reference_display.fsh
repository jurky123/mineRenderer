#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D ReferenceRadiance;
layout(std140) uniform RtEnvironmentSettings {vec4 Exposure;vec4 Reserve0;vec4 Reserve1;vec4 Reserve2;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec3 filmic(vec3 x){return (x*(.15*x+.05)+.004)/(x*(.15*x+.5)+.06)-.02/.3;}
void main(){vec4 observation=texture(ReferenceRadiance,texCoord);if(observation.a<.5)discard;vec3 linear=clamp(filmic(max(observation.rgb,vec3(0))*Exposure.x)/filmic(vec3(6)),0,1);vec3 encoded=mix(1.055*pow(linear,vec3(1/2.4))-.055,linear*12.92,lessThanEqual(linear,vec3(.0031308)));fragColor=vec4(encoded,1);}
