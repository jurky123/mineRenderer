#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform RtEnvironmentSettings {vec4 DirectColorStrength;vec4 SkyColorStrength;vec4 HorizonColorLower;vec4 RtSun;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 float phi=texCoord.x*6.283185307,theta=texCoord.y*3.141592654;
 vec3 ray=vec3(sin(theta)*cos(phi),cos(theta),sin(theta)*sin(phi));
 // Native sky color is already time/weather dependent; no procedural stars or clouds in PT.
 float hemisphere=mix(HorizonColorLower.a,1.0,smoothstep(-.10,.10,ray.y));
 vec3 radiance=SkyColorStrength.rgb*SkyColorStrength.a*hemisphere;
 fragColor=vec4(max(radiance,vec3(0)),1);
}
