#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneDepth;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialAlbedo;
// VOXELLIGHT_MOTION_FUNCTIONS
layout(location=0) in vec2 texCoord;
layout(location=0) out vec2 velocity;
layout(location=1) out float depthGuide;
layout(location=2) out vec4 normalGuide;
void main(){
    float d=texture(SceneDepth,texCoord).r;vec4 n=texture(MaterialNormal,texCoord);int flags=int(round(texture(MaterialAlbedo,texCoord).a*255.0));
    velocity=vec2(0);depthGuide=d;normalGuide=vec4(n.xyz,n.a);
    if(d<=0.0||n.a<.16||(flags&24)!=0){normalGuide.a=0.0;return;}
    normalGuide=vec4(normalize(n.xyz*2.0-1.0)*.5+.5,1);
    vec4 clip=PreviousWorldToClip*vec4(motionPosition(texCoord,d,CurrentClipToWorld)+MotionCameraDelta.xyz,1);
    if(MotionCameraDelta.w>.5&&clip.w>.001)velocity=texCoord-(clip.xy/clip.w*.5+.5);
}
