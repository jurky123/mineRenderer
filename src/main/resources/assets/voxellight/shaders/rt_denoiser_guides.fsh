#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Noisy, Previous, Albedo, Normal, Position, PreviousAlbedo, PreviousNormal, PreviousPosition, MotionPosition, PreviousMotionPosition;
layout(std140) uniform ReconstructionSettings { mat4 previousClip; vec4 previousCamera; vec4 controls; mat4 viewRotation; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 normalGuide;
layout(location=1) out vec4 flowGuide;
layout(location=2) out vec4 trustGuide;
vec2 rtFlow(vec2 pixel,vec2 previousUv,vec2 size){return pixel-previousUv*size;}
float rtIdentityMatch(float current,float previous){return current>0&&abs(current-previous)<.5?1:0;}
bool rtHistoryMatch(vec4 p,vec4 n,vec4 a,vec4 pp,vec4 pn,vec4 pa,float height){
    if(p.w<=0)return pp.w==0&&dot(pp.xyz,p.xyz)>.999;
    return pp.w>0&&a.a>0&&length(pp.xyz-p.xyz)<max(.04,p.w*2/height)&&abs(dot(pp.xyz-p.xyz,n.xyz))<max(.02,p.w*.001)&&dot(pn.xyz,n.xyz)>.95&&abs(pa.a-a.a)<.1&&length(pa.rgb-a.rgb)<.2;
}
void main(){
    ivec2 pixel=ivec2(gl_FragCoord.xy);vec4 p=texelFetch(Position,pixel,0),n=texelFetch(Normal,pixel,0),a=texelFetch(Albedo,pixel,0);
    vec2 flow=vec2(0);float trust=0;
    if(previousCamera.w>.5){
        vec4 motion=texelFetch(MotionPosition,pixel,0);vec4 clip=p.w>0?previousClip*vec4(motion.xyz-previousCamera.xyz,1):previousClip*vec4(p.xyz,0);
        vec2 uv=clip.xy/clip.w*.5+.5;
        if(clip.w>0&&all(greaterThanEqual(uv,vec2(0)))&&all(lessThan(uv,vec2(1)))){
            vec4 pp=texture(PreviousPosition,uv),pn=texture(PreviousNormal,uv),pa=texture(PreviousAlbedo,uv);
            flow=rtFlow(gl_FragCoord.xy,uv,controls.xy);
            vec4 identity=texture(PreviousMotionPosition,uv);
            bool sameSurface=p.w<=0||(rtIdentityMatch(motion.w,identity.w)>.5);
            bool match=sameSurface&&rtHistoryMatch(p.w>0?vec4(motion.xyz,p.w):p,n,a,pp,pn,pa,controls.y);
            trust=match?(p.w<=0||n.a>.15?1:.1):0;
        }
    }
    normalGuide=vec4(p.w>0?normalize(mat3(viewRotation)*n.xyz):vec3(0),1);
    flowGuide=vec4(flow,0,1);
    trustGuide=vec4(trust*(controls.z<8?.25:1),0,0,1);
}
