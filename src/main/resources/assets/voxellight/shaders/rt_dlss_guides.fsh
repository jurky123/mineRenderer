#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Albedo,Normal,Position,MotionPosition,Specular;
layout(std140) uniform DlssSettings {mat4 previousClip;mat4 currentClip;vec4 previousCamera;vec4 camera;vec4 size;vec4 reserved;};
layout(location=0) in vec2 texCoord;
layout(location=0) out float depth;
layout(location=1) out vec2 motion;
layout(location=2) out vec4 normalsRoughness;
layout(location=3) out vec4 diffuseAlbedo;
layout(location=4) out vec4 specularAlbedo;
vec2 rrMotion(vec4 current,vec4 previous,vec2 dimensions,bool valid){
    return valid&&current.w>1e-6&&previous.w>1e-6?(previous.xy/previous.w-current.xy/current.w)*.5*dimensions:vec2(0);
}
float rrDepth(vec4 current,bool surface){return surface&&current.w>1e-6?clamp(current.z/current.w,0,1):0;}
void main(){
    ivec2 pixel=ivec2(gl_FragCoord.xy);vec4 p=texelFetch(Position,pixel,0),n=texelFetch(Normal,pixel,0),a=texelFetch(Albedo,pixel,0),s=texelFetch(Specular,pixel,0),previous=texelFetch(MotionPosition,pixel,0);
    bool surface=p.w>0;
    vec4 current=currentClip*(surface?vec4(p.xyz-camera.xyz,1):vec4(p.xyz,0));
    vec4 before=previousClip*(surface?vec4(previous.xyz-previousCamera.xyz,1):vec4(p.xyz,0));
    depth=rrDepth(current,surface);
    motion=rrMotion(current,before,size.xy,previousCamera.w>.5);
    normalsRoughness=surface?vec4(normalize(n.xyz),clamp(s.a,0,1)):vec4(0,0,0,1);
    int type=int(a.a+.5)-1;bool specular=type==2||type==3||type==4||type==6||type==9;
    diffuseAlbedo=vec4(surface&&!specular?clamp(a.rgb,0,1):vec3(0),1);
    specularAlbedo=vec4(surface?clamp(s.rgb,0,1):vec3(0),1);
}
