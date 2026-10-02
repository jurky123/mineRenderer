#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CurrentHdr;
uniform sampler2D CurrentShadow;
uniform sampler2D MaterialNormal;
uniform sampler2D MaterialDepth;
uniform sampler2D History;
layout(std140) uniform Projection { mat4 ProjMat; };
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];
    mat4 ViewToWorld;
    vec4 LightDirectionAndMask;
    vec4 Coverage;
    vec4 CascadeRanges;
};
layout(std140) uniform TemporalSettings {
    mat4 PreviousWorldToClip;
    vec4 CameraDeltaAndReuse;
    vec4 HistoryParameters;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 resolvedHdr;
layout(location=1) out vec4 nextHistory;
vec2 signNotZero(vec2 v) { return mix(vec2(-1),vec2(1),greaterThanEqual(v,vec2(0))); }
vec2 encodeNormal(vec3 n) {
    n/=abs(n.x)+abs(n.y)+abs(n.z);
    vec2 p=n.z>=0.0?n.xy:(1.0-abs(n.yx))*signNotZero(n.xy);
    return p*.5+.5;
}
vec3 decodeNormal(vec2 p) {
    p=p*2.0-1.0;
    vec3 n=vec3(p,1.0-abs(p.x)-abs(p.y));
    if(n.z<0.0)n.xy=(1.0-abs(n.yx))*signNotZero(n.xy);
    return normalize(n);
}
void main() {
    vec4 current=texture(CurrentHdr,texCoord);
    resolvedHdr=current;
    nextHistory=vec4(-1,0,0,0);
    if(current.a<0.5)return;
    float depth=texture(MaterialDepth,texCoord).r;
    vec3 normal=normalize(texture(MaterialNormal,texCoord).xyz);
    vec4 view=inverse(ProjMat)*vec4(texCoord*2.0-1.0,depth,1.0);
    vec3 position=(ViewToWorld*vec4(view.xyz/view.w,1.0)).xyz;
    vec4 shadow=texture(CurrentShadow,texCoord);
    float visibility=shadow.r,filtered=visibility;
    bool eligible=current.a>.99 && depth>0.0;
    if(eligible && CameraDeltaAndReuse.w>.5) {
        vec3 previousPosition=position+CameraDeltaAndReuse.xyz;
        vec4 previousClip=PreviousWorldToClip*vec4(previousPosition,1.0);
        vec3 projected=previousClip.xyz/previousClip.w;
        vec2 uv=projected.xy*.5+.5;
        vec2 halfPixel=.5/vec2(textureSize(History,0));
        if(previousClip.w>0.0 && projected.z>=0.0 && projected.z<=1.0
                && all(greaterThanEqual(uv,halfPixel)) && all(lessThanEqual(uv,vec2(1)-halfPixel))) {
            vec4 history=texture(History,uv);
            float expectedDistance=length(previousPosition);
            bool matches=history.r>=0.0 && history.a>0.0
                    && abs(history.a-expectedDistance)<=HistoryParameters.y+HistoryParameters.z*expectedDistance
                    && dot(normal,decodeNormal(history.gb))>=HistoryParameters.w;
            if(matches) {
                float lower=visibility,upper=visibility;
                ivec2 size=textureSize(CurrentShadow,0),center=ivec2(gl_FragCoord.xy);
                for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
                    ivec2 pixel=clamp(center+ivec2(x,y),ivec2(0),size-1);
                    if(texelFetch(CurrentHdr,pixel,0).a<.99)continue;
                    float otherDepth=texelFetch(MaterialDepth,pixel,0).r;
                    vec3 otherNormal=normalize(texelFetch(MaterialNormal,pixel,0).xyz);
                    if(dot(normal,otherNormal)<HistoryParameters.w || abs(otherDepth-depth)>max(.0001,depth*.02))continue;
                    float value=texelFetch(CurrentShadow,pixel,0).r;
                    lower=min(lower,value);upper=max(upper,value);
                }
                float old=clamp(history.r,lower,upper);
                // Strong changes respond immediately; filter small PCF/cascade variations only.
                if(abs(old-visibility)<=.2)filtered=mix(visibility,old,HistoryParameters.x);
            }
        }
    }
    // History contains visibility, not color. Preserve current textures, local lights and emission.
    resolvedHdr=vec4(max(current.rgb+shadow.gba*(filtered-visibility),vec3(0)),current.a);
    nextHistory=vec4(eligible?filtered:-1.0,encodeNormal(normal),length(position));
}
