// Appended to the active native terrain fragment shader after renaming its main.
uniform sampler2D WaterHdr;
uniform sampler2D WaterDepth;
uniform sampler2D EmissiveBloom;
in float waterSkyAccess;
layout(std140) uniform WaterSettings {
    vec4 WaterStill;vec4 WaterFlow;vec4 WaterCamera;vec4 WaterParameters;
};
layout(std140) uniform VisualSettings {vec4 ToneBloom;vec4 MaterialFade;};
layout(std140) uniform AtmosphereSettings {vec4 AtmosphereParameters;};
layout(std140) uniform LightingEnvironment {vec4 DirectColorStrength;vec4 SkyColorStrength;vec4 HorizonColorLower;};
layout(std140) uniform ShadowResolveSettings {
    mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;
    mat4 InvProjection;mat4 LightNormalMatrix[3];
};
// VOXELLIGHT_VISUAL_FUNCTIONS
bool waterSprite(vec2 uv,vec4 bounds){return all(greaterThanEqual(uv,bounds.xy)) && all(lessThanEqual(uv,bounds.zw));}
vec3 waterPosition(vec2 uv,float depth) {
    vec4 view=InvProjection*vec4(uv*2.0-1.0,depth,1.0);
    return (ViewToWorld*vec4(view.xyz/view.w,1.0)).xyz;
}
bool background(vec2 uv,vec3 surface,out vec3 radiance,out float thickness) {
    vec4 hdr=texture(WaterHdr,uv);float depth=texture(WaterDepth,uv).r;
    vec3 back=waterPosition(uv,depth);
    float distanceBack=length(back);
    if(depth<=0.0 || hdr.a<=0.0 || abs(hdr.a-distanceBack)>max(.04,distanceBack*.002) || distanceBack<=length(surface))return false;
    radiance=hdr.rgb;thickness=clamp(distanceBack-length(surface),0.0,16.0);return true;
}
void main() {
    voxellightNativeMain();
    if(!waterSprite(texCoord0,WaterStill) && !waterSprite(texCoord0,WaterFlow))return;
    vec2 uv=gl_FragCoord.xy/vec2(textureSize(WaterHdr,0));
    vec3 surface=waterPosition(uv,gl_FragCoord.z),base;float thickness;
    if(!background(uv,surface,base,thickness))return;
    vec3 crossed=cross(dFdx(surface),dFdy(surface));
    if(dot(crossed,crossed)<1e-12 || length(surface)<.001)return;
    vec3 normal=normalize(crossed);
    vec3 viewDirection=normalize(-surface);
    if(dot(normal,viewDirection)<0.0)normal=-normal;
    vec3 absolute=surface+WaterCamera.xyz;
    float time=GameTime*1200.0*.7853981634;
    if(abs(normal.y)>.8)normal=normalize(normal+vec3(.055*sin(absolute.x*.1963495408+time),0.0,.045*cos(absolute.z*.3926990817-time)));
    vec3 viewNormal=transpose(mat3(ViewToWorld))*normal;
    vec2 refracted=uv+viewNormal.xy*min(6.0,thickness*2.0)/vec2(textureSize(WaterHdr,0));
    vec3 shifted;float shiftedThickness;
    if(all(greaterThanEqual(refracted,vec2(0))) && all(lessThanEqual(refracted,vec2(1))) && background(refracted,surface,shifted,shiftedThickness)) {base=shifted;thickness=shiftedThickness;}
    vec3 transmission=exp(-vec3(.18,.065,.028)*thickness);
    vec3 reflected=reflect(-viewDirection,normal);
    vec3 sky=mix(HorizonColorLower.rgb,SkyColorStrength.rgb,clamp(reflected.y,0.0,1.0))*SkyColorStrength.a*waterSkyAccess;
    float specular=pow(max(dot(reflected,LightDirectionAndMask.xyz),0.0),64.0);
    sky+=DirectColorStrength.rgb*DirectColorStrength.a*specular*waterSkyAccess;
    vec3 body=vec3(.015,.09,.12)*(.01+SkyColorStrength.a*waterSkyAccess);
    vec3 transmitted=base*transmission+body*(1.0-transmission);
    float fresnel=.02+.98*pow(1.0-clamp(dot(normal,viewDirection),0.0,1.0),5.0);
    vec3 radiance=mix(transmitted,sky,fresnel);
    float coverage=(1.0-smoothstep(WaterParameters.x,WaterParameters.y,length(surface)))*clamp(ChunkVisibility,0.0,1.0);
    // Fully admitted water replaces the native fragment with a transmitted HDR background, tone mapped once.
    fragColor=mix(fragColor,vec4(displayColor(radiance,surface,waterSkyAccess,uv),1.0),coverage);
}
