#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(std140) uniform AtlasRegion { vec4 crop; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){fragColor=textureLod(Sampler0,crop.xy+texCoord*crop.zw,0);}
