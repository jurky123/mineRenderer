#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentHdr;
uniform sampler2D RtDiffuse;
uniform sampler2D RtSpecular;
uniform sampler2D RtTransmission;
uniform sampler2D RtPosition;
uniform sampler2D RtNormal;
uniform sampler2D MaterialNormal;
uniform sampler2D SceneDepth;
layout(std140) uniform RtFrame {mat4 RtInvProjection;mat4 RtViewToWorld;vec4 RtControls;};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 fragColor=texture(CurrentHdr,texCoord);float d=texture(SceneDepth,texCoord).r;vec4 transmitted=texture(RtTransmission,texCoord);if((int(RtControls.x)&4)!=0&&transmitted.a>0){fragColor=vec4(transmitted.rgb,1);if(RtControls.y>0.5&&RtControls.y<3.5)fragColor.rgb=RtControls.y<1.5?texture(RtDiffuse,texCoord).rgb:RtControls.y<2.5?texture(RtSpecular,texCoord).rgb:transmitted.rgb;return;}if(d<=0){vec4 diagnostic=texture(RtDiffuse,texCoord);if(RtControls.y>=3.5&&diagnostic.a>.5)fragColor=diagnostic;return;}
 vec4 p=RtInvProjection*vec4(texCoord*2-1,d,1);p/=p.w;vec3 world=(RtViewToWorld*vec4(p.xyz,0)).xyz;
 vec3 n=normalize(texture(MaterialNormal,texCoord).xyz*2-1);ivec2 size=textureSize(RtPosition,0),base=ivec2(texCoord*vec2(size));vec3 gi=vec3(0),spec=vec3(0);float weight=0;
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){ivec2 q=clamp(base+ivec2(x,y),ivec2(0),size-1);vec4 h=texelFetch(RtPosition,q,0);vec3 hn=texelFetch(RtNormal,q,0).xyz;float plane=abs(dot(n,h.xyz-world)),tolerance=.12+.003*length(world);if(h.a<.5||texelFetch(RtDiffuse,q,0).a<.5&&texelFetch(RtSpecular,q,0).a<.5||dot(n,hn)<.8||plane>tolerance)continue;
  float w=exp(-float(x*x+y*y)-plane*plane/(tolerance*tolerance));gi+=texelFetch(RtDiffuse,q,0).rgb*w;spec+=texelFetch(RtSpecular,q,0).rgb*w;weight+=w;
 }
 if(weight<=0)return;gi/=weight;spec/=weight;
 if(RtControls.y>0.5){fragColor.rgb=RtControls.y>=3.5?gi:RtControls.y<1.5?gi:RtControls.y<2.5?spec:texture(RtTransmission,texCoord).rgb;fragColor.a=1;return;}
 fragColor.rgb+=gi+spec;
}
