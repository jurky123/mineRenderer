#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Noisy, Previous, Albedo, Normal, Position, PreviousAlbedo, PreviousNormal, PreviousPosition, MotionPosition, PreviousMotionPosition;
layout(std140) uniform ReconstructionSettings { mat4 previousClip; vec4 previousCamera; vec4 controls; mat4 viewRotation; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
bool finiteRgb(vec3 c){return !any(isnan(c))&&!any(isinf(c));}
vec2 rtFlow(vec2 pixel,vec2 previousUv,vec2 size){return pixel-previousUv*size;}
float rtIdentityMatch(float current,float previous){return current>0&&abs(current-previous)<.5?1:0;}
bool rtHistoryMatch(vec4 p,vec4 n,vec4 a,vec4 pp,vec4 pn,vec4 pa,float height){
    if(p.w<=0)return pp.w==0&&dot(pp.xyz,p.xyz)>.999;
    return pp.w>0&&a.a>0&&length(pp.xyz-p.xyz)<max(.04,p.w*2/height)&&abs(dot(pp.xyz-p.xyz,n.xyz))<max(.02,p.w*.001)&&dot(pn.xyz,n.xyz)>.95&&abs(pa.a-a.a)<.1&&length(pa.rgb-a.rgb)<.2;
}
void main(){
    ivec2 pixel=ivec2(gl_FragCoord.xy),size=textureSize(Noisy,0);
    vec4 a=texelFetch(Albedo,pixel,0),n=texelFetch(Normal,pixel,0),p=texelFetch(Position,pixel,0),raw=texelFetch(Noisy,pixel,0);
    vec3 sum=vec3(0),lo=vec3(1e30),hi=vec3(0);float total=0;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){
        ivec2 q=clamp(pixel+ivec2(x,y),ivec2(0),size-1);vec4 qn=texelFetch(Normal,q,0),qp=texelFetch(Position,q,0),qa=texelFetch(Albedo,q,0);vec3 c=texelFetch(Noisy,q,0).rgb;
        if(!finiteRgb(c))continue;
        float weight=exp(-float(x*x+y*y)*.35);
        if(p.w>0){weight*=pow(max(dot(n.xyz,qn.xyz),0),32)*exp(-length(qp.xyz-p.xyz)/max(.025,p.w*.005))*exp(-length(qa.rgb-a.rgb)*8);if(abs(qa.a-a.a)>.1||qp.w<=0)weight=0;}
        else if(qp.w>0)weight=0;
        sum+=max(c,vec3(0))*weight;total+=weight;if(weight>.05){lo=min(lo,c);hi=max(hi,c);}
    }
    vec3 current=total>1e-6?sum/total:vec3(0);float count=max(raw.a,1);vec4 old=vec4(0);
    if(previousCamera.w>.5&&a.a>=0){
        vec4 motion=texelFetch(MotionPosition,pixel,0);vec4 clip=(p.w>0?previousClip*vec4(motion.xyz-previousCamera.xyz,1):previousClip*vec4(p.xyz,0));vec2 uv=clip.xy/clip.w*.5+.5;
        if(clip.w>0&&all(greaterThanEqual(uv,vec2(0)))&&all(lessThan(uv,vec2(1)))){
            vec4 pp=texture(PreviousPosition,uv),pn=texture(PreviousNormal,uv),pa=texture(PreviousAlbedo,uv);
            vec4 identity=texture(PreviousMotionPosition,uv);
            bool sameSurface=p.w<=0||(rtIdentityMatch(motion.w,identity.w)>.5);
            bool match=sameSurface&&rtHistoryMatch(p.w>0?vec4(motion.xyz,p.w):p,n,a,pp,pn,pa,controls.y);
            // Rough opaque terrain may reuse history; sharp reflection/transmission needs separate motion.
            if(match&&(p.w<=0||n.a>.15)){old=texture(Previous,uv);if(!finiteRgb(old.rgb)||isnan(old.a)||isinf(old.a))old=vec4(0);}
        }
    }
    float retained=min(max(old.a,0),controls.z-count);float weight=count/max(retained+count,1);
    fragColor=vec4(mix(clamp(old.rgb,lo,hi),current,weight),min(retained+count,controls.z));
}
