#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Noisy, Previous, Albedo, Normal, Position, PreviousAlbedo, PreviousNormal, PreviousPosition, MotionPosition, PreviousMotionPosition;
layout(std140) uniform ReconstructionSettings { mat4 previousClip; vec4 previousCamera; vec4 controls; mat4 viewRotation; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
bool finiteRgb(vec3 c){return !any(isnan(c))&&!any(isinf(c));}
void main(){
    ivec2 size=textureSize(Noisy,0);vec2 grid=texCoord*vec2(size)-.5;
    ivec2 center=clamp(ivec2(floor(grid+.5)),ivec2(0),size-1);
    vec4 p=texelFetch(Position,center,0),n=texelFetch(Normal,center,0),a=texelFetch(Albedo,center,0),m=texelFetch(MotionPosition,center,0);
    vec3 sum=vec3(0),lo=vec3(1e30),hi=vec3(0);float total=0;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        ivec2 q=clamp(center+ivec2(x,y),ivec2(0),size-1);vec4 qp=texelFetch(Position,q,0),qn=texelFetch(Normal,q,0),qa=texelFetch(Albedo,q,0),qm=texelFetch(MotionPosition,q,0);vec3 c=texelFetch(Noisy,q,0).rgb;
        if(!finiteRgb(c))continue;
        float w=exp(-dot(vec2(q)-grid,vec2(q)-grid)*1.4);
        if(p.w>0){w*=pow(max(dot(n.xyz,qn.xyz),0),32)*exp(-abs(dot(qp.xyz-p.xyz,n.xyz))/max(.02,p.w*.002));if(qp.w<=0||abs(qm.w-m.w)>.5||length(qa.rgb-a.rgb)>.25)w=0;}
        else if(qp.w>0)w=0;
        float nextTotal=total+w;if(nextTotal>0)sum=sum*(total/nextTotal)+max(c,vec3(0))*(w/nextTotal);total=nextTotal;if(w>.025){lo=min(lo,c);hi=max(hi,c);}
    }
    vec3 current=total>1e-6?sum:vec3(0);if(total<=1e-6||any(greaterThan(lo,hi))){lo=current;hi=current;}vec4 old=vec4(0);
    if(previousCamera.w>.5&&(p.w<=0||m.w>0)){
        vec4 clip=p.w>0?previousClip*vec4(m.xyz-previousCamera.xyz,1):previousClip*vec4(p.xyz,0);
        vec2 oldUv=clip.xy/clip.w*.5+.5;
        // Retain the output-pixel subpixel offset rather than snapping every output pixel to one low-res guide.
        oldUv+=texCoord-(vec2(center)+.5)/vec2(size);
        if(clip.w>0&&all(greaterThanEqual(oldUv,vec2(0)))&&all(lessThan(oldUv,vec2(1)))){
            vec4 pp=texture(PreviousPosition,oldUv),pn=texture(PreviousNormal,oldUv),pa=texture(PreviousAlbedo,oldUv),pm=texture(PreviousMotionPosition,oldUv);
            bool match=p.w<=0?pp.w==0&&dot(p.xyz,pp.xyz)>.999:
                pp.w>0&&abs(pm.w-m.w)<.5&&dot(n.xyz,pn.xyz)>.95&&length(pp.xyz-m.xyz)<max(.04,p.w*2/controls.y)&&abs(dot(pp.xyz-m.xyz,n.xyz))<max(.02,p.w*.001)&&length(pa.rgb-a.rgb)<.2;
            if(match&&(p.w<=0||n.a>.15))old=texture(Previous,oldUv);
        }
    }
    if(!finiteRgb(old.rgb)||isnan(old.a)||isinf(old.a))old=vec4(0);
    float count=min(max(old.a,0),min(controls.z,16)-1);float blend=1/(count+1);
    fragColor=vec4(mix(clamp(old.rgb,lo,hi),current,blend),count+1);
}
