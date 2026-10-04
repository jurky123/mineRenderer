layout(std140) uniform EnvironmentSettings {
    vec4 CloudOriginTime; // wrapped absolute camera x,z; camera height; world-space wind displacement
    vec4 WeatherControls; // custom sky, cloud layer, cloud shadows, rain amount
    vec4 CelestialData; // sun angle, moon phase, cloud altitude, clear-weather coverage threshold
    vec4 UnderwaterControls; // underwater medium, caustics, ripples, water surface world height
};
float envHash(vec2 p){p=mod(p,128.0);return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float envNoise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);return mix(mix(envHash(i),envHash(i+vec2(1,0)),f.x),mix(envHash(i+vec2(0,1)),envHash(i+1.0),f.x),f.y);}
float cloudField(vec2 world){
    vec2 p=(world+vec2(CloudOriginTime.w,CloudOriginTime.w*.5))/128.0;
    float noise=.55*envNoise(p)+.28*envNoise(p*2.0)+.12*envNoise(p*4.0)+.05*envNoise(p*8.0);
    float threshold=CelestialData.w-WeatherControls.w*.14;
    return smoothstep(threshold,threshold+.20,noise);
}
// 8x4x8 macro cells retain square silhouettes. Small-scale erosion softens their boundaries.
float voxelCloudDensity(vec3 world){
 vec3 moving=world+vec3(CloudOriginTime.w,0,CloudOriginTime.w*.5),cell=floor(moving/vec3(8,4,8));
 float altitude=world.y-CelestialData.z;if(altitude<0||altitude>32)return 0;
 float shape=0;vec2 edge=moving.xz/8.0;
 for(int y=0;y<2;y++)for(int x=0;x<2;x++){vec2 candidate=floor(edge+vec2(x==0?-.12:.12,y==0?-.12:.12));float noise=envNoise(candidate*.09),occupied=step(CelestialData.w-WeatherControls.w*.14,noise),height=4*(3+floor(noise*5));vec2 delta=abs(moving.xz-(candidate+.5)*8)-4;float face=max(max(delta.x,delta.y),altitude-height);shape=max(shape,occupied*(1-smoothstep(-.7,.8,face)));}
 shape*=smoothstep(0,3,altitude)*(1-smoothstep(25,32,altitude));
 float detail=envNoise(moving.xz*.47+vec2(world.y*.31,-world.y*.17));
 return max(0,shape*(.65+.35*envNoise(cell.xz*.31+cell.y))-(1-detail)*.27);
}
float cloudVisibility(vec3 position,vec3 light){
    if(WeatherControls.z<.5 || light.y<.08)return 1.0;
    float height=CelestialData.z-CloudOriginTime.z-position.y;
    if(height<=0.0)return 1.0;
    vec2 projected=CloudOriginTime.xy+position.xz+light.xz*(height/light.y);
    float density=0;for(int i=0;i<4;i++)density+=voxelCloudDensity(vec3(projected.x,CelestialData.z+4+float(i)*8,projected.y));return exp(-density*mix(.4,.65,WeatherControls.w));
}
vec3 environmentSky(vec3 ray,vec3 zenith,vec3 horizon,float brightness){
    vec3 sun=vec3(-sin(CelestialData.x),cos(CelestialData.x),0);
    float day=smoothstep(-.15,.25,sun.y),rise=exp(-abs(sun.y)*8.0);
    vec3 night=vec3(.007,.014,.035);
    vec3 sky=mix(night,mix(horizon,zenith,pow(max(ray.y,0.0),.45))*.8,day);
    float towardSun=pow(max(dot(ray,sun),0.0),12.0);
    sky+=vec3(1.0,.22,.045)*rise*towardSun*.65*(1.0-WeatherControls.w);
    float disc=smoothstep(cos(.014),cos(.010),dot(ray,sun));
    sky+=vec3(8.0,6.5,4.2)*disc*day*(1.0-WeatherControls.w*.8);
    vec3 moon=-sun;float moonDisc=smoothstep(cos(.016),cos(.012),dot(ray,moon));
    float phase=abs(CelestialData.y-4.0)/4.0;
    sky+=vec3(.35,.45,.65)*moonDisc*(1.0-day)*phase;
    // Sparse deterministic world-direction stars, hidden by rain/day/cloud opacity.
    vec2 starUv=vec2(atan(ray.z,ray.x),asin(clamp(ray.y,-1.0,1.0)))*180.0;
    vec2 starCell=floor(starUv);vec2 starOffset=fract(starUv)-.5;
    sky+=vec3(.4,.5,.7)*step(.996,envHash(starCell))*exp(-dot(starOffset,starOffset)*150.0)*(1.0-day)*(1.0-WeatherControls.w)*smoothstep(0.0,.15,ray.y);
    return mix(sky,vec3(.22,.24,.27)*max(.08,day),WeatherControls.w*.60);
}
vec4 environmentCloud(vec3 ray,vec3 position,vec3 directColor,float directStrength,vec3 skyColor){
    if(WeatherControls.y<.5 || abs(ray.y)<.002)return vec4(0);
    float t=(CelestialData.z-CloudOriginTime.z)/ray.y;
    if(t<=0.0 || t>8000.0)return vec4(0);
    if(length(position)>0.0 && t>=length(position))return vec4(0);
    vec2 world=CloudOriginTime.xy+ray.xz*t;
    float density=cloudField(world),upper=cloudField(world+vec2(8,-4));
    float light=mix(.25,.9,clamp(1.0-density+.5*(upper-density),0.0,1.0));
    vec3 color=skyColor*.25+directColor*directStrength*light;
    color=mix(color,color*.48,WeatherControls.w);
    float opacity=density*.94*(1.0-smoothstep(4000.0,8000.0,t));
    return vec4(color,opacity);
}
float causticPattern(vec3 p){
    vec2 uv=(CloudOriginTime.xy+p.xz)*.6;
    float time=CloudOriginTime.w*2.0;
    float a=sin(uv.x+time+sin(uv.y*.7-time*.8));
    float b=sin(uv.y-time*.6+sin(uv.x*.8+time*.5));
    return pow(max(0.0,1.0-abs(a*b)*2.5),8.0);
}
vec3 underwaterMedium(vec3 radiance,vec3 position,vec3 sky,float sun){
    if(UnderwaterControls.x<.5)return radiance;
    float distance=min(length(position),96.0);
    vec3 transmission=exp(-vec3(.16,.060,.035)*distance);
    vec3 scatter=vec3(.025,.11,.16)*(.35+max(sun,0.0));
    return radiance*transmission+scatter*(1.0-transmission);
}
