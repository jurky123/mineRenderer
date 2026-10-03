#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneDepth;
layout(std140) uniform WaterMaskSettings {vec4 WaterStill;vec4 WaterFlow;vec4 MaskViewport;};
layout(location=0) in vec2 texCoord;
layout(location=0) out float waterDepth;
bool sprite(vec4 b){return all(greaterThanEqual(texCoord,b.xy+vec2(1e-7)))&&all(lessThanEqual(texCoord,b.zw-vec2(1e-7)));}
void main(){if(!sprite(WaterStill)&&!sprite(WaterFlow))discard;vec2 uv=gl_FragCoord.xy/MaskViewport.xy;if(gl_FragCoord.z<=texture(SceneDepth,uv).r)discard;waterDepth=gl_FragCoord.z;}
