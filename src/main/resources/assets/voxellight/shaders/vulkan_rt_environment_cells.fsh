#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D EnvironmentMap;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 ivec2 size=textureSize(EnvironmentMap,0),pixel=min(ivec2(texCoord*vec2(size)),size-1);
 float omega=6.283185307/float(size.x)*(cos(3.141592654*float(pixel.y)/float(size.y))-cos(3.141592654*float(pixel.y+1)/float(size.y)));
 float prefix=0,total=0,mass=0;
 for(int x=0;x<size.x;x++){
  float weight=max(0,dot(texelFetch(EnvironmentMap,ivec2(x,pixel.y),0).rgb,vec3(.2126,.7152,.0722)))*omega;
  total+=weight;if(x<=pixel.x)prefix+=weight;if(x==pixel.x)mass=weight;
 }
 fragColor=vec4(prefix,mass,total,omega);
}
