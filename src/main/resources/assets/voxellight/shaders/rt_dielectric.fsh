#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D RtDiffuse;
uniform sampler2D RtSpecular;
layout(std140) uniform RtFrame {mat4 RtInvProjection;mat4 RtViewToWorld;vec4 RtControls;};
uniform sampler2D RtTransmission;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){vec4 t=texture(RtTransmission,texCoord);vec3 color=t.rgb+(t.a>0?texture(RtSpecular,texCoord).rgb:vec3(0));
 if(RtControls.y>0.5&&RtControls.y<3.5)color=RtControls.y<1.5?texture(RtDiffuse,texCoord).rgb:RtControls.y<2.5?texture(RtSpecular,texCoord).rgb:t.rgb;
 fragColor=vec4(color,t.a);}
