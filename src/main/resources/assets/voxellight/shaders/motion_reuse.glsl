uniform sampler2D PreviousDepth;
uniform sampler2D PreviousNormal;
uniform sampler2D MotionVectors;
bool motionPrevious(vec2 uv,float depth,vec3 normal,out vec2 previousUv){
    if(MotionCameraDelta.w<.5||depth<=0.0)return false;
    vec3 p=motionPosition(uv,depth,CurrentClipToWorld)+MotionCameraDelta.xyz;
    vec4 clip=PreviousWorldToClip*vec4(p,1);
    if(clip.w<=.001)return false;
    previousUv=clip.xy/clip.w*.5+.5;
    if(any(lessThan(previousUv,vec2(.001)))||any(greaterThan(previousUv,vec2(.999))))return false;
    vec4 guide=texture(PreviousNormal,previousUv);float oldDepth=texture(PreviousDepth,previousUv).r;
    if(guide.a<.5||oldDepth<=0.0)return false;
    vec3 oldNormal=normalize(guide.xyz*2.0-1.0);
    vec3 oldP=motionPosition(previousUv,oldDepth,PreviousClipToWorld);
    float tolerance=MotionControls.y+length(p)*MotionControls.z;
    return dot(normal,oldNormal)>.94&&abs(dot(p-oldP,normal))<tolerance&&length(p-oldP)<max(.12,tolerance*4.0);
}
