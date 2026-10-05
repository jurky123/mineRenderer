#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D EnvironmentCells;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 ivec2 size=textureSize(EnvironmentCells,0);int row=min(int(texCoord.x*float(size.y)),size.y-1);
 float prefix=0,total=0,mass=0;
 for(int y=0;y<size.y;y++){
  float weight=texelFetch(EnvironmentCells,ivec2(size.x-1,y),0).z;
  total+=weight;if(y<=row)prefix+=weight;if(y==row)mass=weight;
 }
 fragColor=vec4(prefix,mass,total,0);
}
