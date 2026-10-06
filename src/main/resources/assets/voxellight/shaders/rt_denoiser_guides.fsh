#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Noisy, Previous, Albedo, Normal, Position, PreviousAlbedo, PreviousNormal, PreviousPosition;
layout(std140) uniform ReconstructionSettings { mat4 previousClip; vec4 previousCamera; vec4 controls; mat4 viewRotation; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    ivec2 pixel=ivec2(gl_FragCoord.xy);vec4 p=texelFetch(Position,pixel,0),n=texelFetch(Normal,pixel,0),a=texelFetch(Albedo,pixel,0);
    vec2 flow=vec2(0);float trust=0;
    if(p.w>0&&previousCamera.w>.5){
        vec4 clip=previousClip*vec4(p.xyz-previousCamera.xyz,1);vec2 uv=clip.xy/clip.w*.5+.5;
        if(clip.w>0&&all(greaterThanEqual(uv,vec2(0)))&&all(lessThan(uv,vec2(1)))){
            vec4 pp=texture(PreviousPosition,uv),pn=texture(PreviousNormal,uv),pa=texture(PreviousAlbedo,uv);
            flow=gl_FragCoord.xy-uv*controls.xy;
            trust=pp.w>0&&length(pp.xyz-p.xyz)<max(.04,p.w*.003)&&dot(pn.xyz,n.xyz)>.9&&abs(pa.a-a.a)<.1&&length(pa.rgb-a.rgb)<.15&&n.a>.15?1:0;
        }
    }
    if(controls.w<.5)fragColor=vec4(p.w>0?normalize(mat3(viewRotation)*n.xyz):vec3(0),1);
    else if(controls.w<1.5)fragColor=vec4(flow,0,1);
    else fragColor=vec4(trust*(controls.z<8?.25:1),0,0,1);
}
