#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D VolumeInput;
uniform sampler2D VolumeHistory;
uniform sampler2D SceneDepth;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialAlbedo;
layout(std140) uniform VolumetricSettings {vec4 VolumeParameters;vec4 VolumeQuality;};
// VOXELLIGHT_MOTION_FUNCTIONS
// VOXELLIGHT_MOTION_REUSE
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    vec4 current=texture(VolumeInput,texCoord);fragColor=current;
    vec4 guide=texture(MaterialNormal,texCoord);int flags=int(round(texture(MaterialAlbedo,texCoord).a*255.0));vec2 previousUv;
    if(VolumeQuality.w<.5||guide.a<.16||(flags&24)!=0||!motionPrevious(texCoord,texture(SceneDepth,texCoord).r,normalize(guide.xyz*2.0-1.0),previousUv))return;
    vec4 lo=current,hi=current;vec2 pixel=1.0/vec2(textureSize(VolumeInput,0));
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){vec4 v=texture(VolumeInput,texCoord+vec2(x,y)*pixel);lo=min(lo,v);hi=max(hi,v);}
    vec4 old=clamp(texture(VolumeHistory,previousUv),lo,hi);
    float speed=length(texture(MotionVectors,texCoord).rg*vec2(textureSize(VolumeInput,0)));
    float weight=MotionControls.x*exp(-length(MotionCameraDelta.xyz)*.45-speed*.02);
    fragColor=mix(current,old,weight);
}
