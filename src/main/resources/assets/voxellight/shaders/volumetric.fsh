#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneDepth;
uniform sampler2D MaterialEmission;
uniform sampler2D ShadowMap;
uniform sampler2D MiddleShadowMap;
uniform sampler2D FarShadowMap;
uniform sampler2D EntityShadowMap;
uniform sampler2D MiddleEntityShadowMap;
uniform sampler2D FarEntityShadowMap;
layout(std140) uniform AtmosphereSettings { vec4 AtmosphereParameters; };
layout(std140) uniform LightingEnvironment { vec4 DirectColorStrength; vec4 SkyColorStrength; vec4 HorizonColorLower; };
layout(std140) uniform VolumetricSettings { vec4 VolumeParameters; }; // active, distance, anisotropy, phase scale
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;
    mat4 InvProjection;mat4 LightNormalMatrix[3];
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float visible(sampler2D terrain,sampler2D dynamicMap,mat4 matrix,vec3 position) {
    vec3 p=(matrix*vec4(position,1)).xyz;
    vec2 uv=p.xy*.5+.5, pixel=1.0/vec2(textureSize(terrain,0));
    // Unknown shadow coverage does not manufacture shafts through missing map coverage.
    if(p.z<=0.0 || p.z>=1.0 || any(lessThan(uv,pixel)) || any(greaterThan(uv,vec2(1)-pixel)))return 0.0;
    float bias=.0357*length(vec3(matrix[0].z,matrix[1].z,matrix[2].z));
    float visibility=0.0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        vec2 tap=uv+(vec2(x,y)-.5)*pixel;
        float blocker=min(texture(terrain,tap).r,texture(dynamicMap,tap).r);
        visibility+=p.z-bias<=blocker?.25:0.0;
    }
    return visibility;
}
float visibility(vec3 p) {
    float d=length(p);
    if(d<CascadeRanges.y) {
        float near=visible(ShadowMap,EntityShadowMap,LightMatrix[0],p);
        if(d<=CascadeRanges.x)return near;
        return mix(near,visible(MiddleShadowMap,MiddleEntityShadowMap,LightMatrix[1],p),smoothstep(CascadeRanges.x,CascadeRanges.y,d));
    }
    if(d<CascadeRanges.w) {
        float middle=visible(MiddleShadowMap,MiddleEntityShadowMap,LightMatrix[1],p);
        if(d<=CascadeRanges.z)return middle;
        return mix(middle,visible(FarShadowMap,FarEntityShadowMap,LightMatrix[2],p),smoothstep(CascadeRanges.z,CascadeRanges.w,d));
    }
    return visible(FarShadowMap,FarEntityShadowMap,LightMatrix[2],p);
}
void main() {
    fragColor=vec4(0,0,0,1);
    float depth=texture(SceneDepth,texCoord).r;
    if(depth<=0.0 || VolumeParameters.x<.5 || AtmosphereParameters.x<=0.0)return;
    vec4 v=InvProjection*vec4(texCoord*2.0-1.0,depth,1.0);
    if(abs(v.w)<1e-7)return;
    vec3 endpoint=(ViewToWorld*vec4(v.xyz/v.w,1)).xyz;
    float distance=length(endpoint);
    if(distance<.001)return;
    vec3 ray=endpoint/distance;
    float reach=min(distance,min(VolumeParameters.y,max(0.0,Coverage.y-8.0)));
    float ds=reach/16.0, transmission=1.0;
    // Fixed interleaved spatial gradient; no frame-varying noise or unvalidated history.
    float jitter=fract(52.9829189*fract(dot(floor(gl_FragCoord.xy),vec2(.06711056,.00583715))));
    float g=VolumeParameters.z, cosine=clamp(dot(ray,LightDirectionAndMask.xyz),-1.0,1.0);
    float phase=VolumeParameters.w*(1.0-g*g)/pow(1.0+g*g-2.0*g*cosine,1.5);
    float skyAccess=clamp(texture(MaterialEmission,texCoord).b,0.0,1.0);
    vec3 ambient=HorizonColorLower.rgb*(.01+SkyColorStrength.a*1.5)*skyAccess;
    vec3 scattered=vec3(0);
    for(int i=0;i<16;i++) {
        vec3 p=ray*((float(i)+jitter)*ds);
        float height=AtmosphereParameters.y+p.y;
        float tau=min(AtmosphereParameters.x*exp(-clamp(height/48.0,-1.0,4.0))*ds,2.0/16.0);
        float stepTransmission=exp(-tau);
        // Shadow only direct in-scattering; ambient keeps the stated receiver-skylight approximation.
        float direct=Coverage.z>0.0?visibility(p):0.0;
        vec3 source=ambient+DirectColorStrength.rgb*DirectColorStrength.a*phase*direct;
        scattered+=transmission*(1.0-stepTransmission)*source;
        transmission*=stepTransmission;
    }
    fragColor=vec4(scattered,transmission);
}
