#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D EnvironmentCells;
uniform sampler2D EnvironmentRows;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float prefixAt(int line,int i){return line==0?texelFetch(EnvironmentRows,ivec2(i,0),0).x:texelFetch(EnvironmentCells,ivec2(i,line-1),0).x;}
int upperBound(int line,int count,float value){int lo=0,hi=count-1;while(lo<hi){int mid=(lo+hi)/2;if(prefixAt(line,mid)<=value)lo=mid+1;else hi=mid;}return lo;}
void main(){
 ivec2 size=textureSize(EnvironmentCells,0);int bucket=min(int(texCoord.x*256.0),255),line=min(int(texCoord.y*float(size.y+1)),size.y);
 int count=line==0?size.y:size.x;
 float total=line==0?texelFetch(EnvironmentRows,ivec2(size.y-1,0),0).z:texelFetch(EnvironmentRows,ivec2(line-1,0),0).y;
 float lower=float(bucket)/256.0*total,upper=float(bucket+1)/256.0*total;
 fragColor=vec4(float(upperBound(line,count,lower)),float(upperBound(line,count,upper)),0.0,0.0);
}
