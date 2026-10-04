#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneDepth;
uniform sampler2D MaterialDepth;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialAlbedo;
uniform sampler2D MaterialPbr;
uniform sampler2D MaterialTable;
layout(std140) uniform RtFrame {mat4 RtInvProjection;mat4 RtViewToWorld;vec4 RtControls;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 position;
layout(location=1) out vec4 normal;
layout(location=2) out vec4 albedo;
layout(location=3) out vec4 material;
vec3 decode(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),greaterThan(c,vec3(.04045)));}
vec3 oct(vec2 e){vec2 f=e*2-1;vec3 n=vec3(f,1-abs(f.x)-abs(f.y));if(n.z<0)n.xy=(1-abs(n.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(n.xy,vec2(0)));return normalize(n);}
void main(){
 position=vec4(0);normal=vec4(0);albedo=vec4(0);material=vec4(0);
 float d=texture(SceneDepth,texCoord).r,md=texture(MaterialDepth,texCoord).r;vec4 gn=texture(MaterialNormal,texCoord);vec4 a=texture(MaterialAlbedo,texCoord);
 if(d<=0){vec4 far=RtInvProjection*vec4(texCoord*2-1,.00001,1);vec3 ray=normalize((RtViewToWorld*vec4(far.xyz/far.w,0)).xyz);position=vec4(ray*512,2);normal=vec4(-ray,1);return;}
 if(md<=0||gn.a<.16||(int(round(a.a*255))&16)!=0)return;
 vec4 p=RtInvProjection*vec4(texCoord*2-1,d,1),m=RtInvProjection*vec4(texCoord*2-1,md,1);p/=p.w;m/=m.w;
 if(abs(m.z-p.z)>max(.01,.001*abs(p.z)))return;
 vec3 world=(RtViewToWorld*vec4(p.xyz,0)).xyz;if(length(world)>128)return;
 vec4 packed=texture(MaterialPbr,texCoord),profile=texelFetch(MaterialTable,ivec2(round(packed.rg*255)),0);
 position=vec4(world,1);normal=vec4(oct(packed.ba),1);albedo=vec4(decode(a.rgb),1);material=vec4(1-profile.r,profile.g,round(packed.r*255)+256*round(packed.g*255),0);
}
