#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneDepth;
uniform sampler2D CloudHistory;
layout(std140) uniform CloudSettings {vec4 CloudControls;};
layout(std140) uniform MotionSettings {mat4 MotionInverseCurrent;mat4 MotionCurrent;mat4 MotionPrevious;mat4 MotionInversePrevious;vec4 MotionCameraDelta;vec4 MotionControls;};
layout(std140) uniform LightingEnvironment {vec4 DirectColorStrength;vec4 SkyColorStrength;vec4 HorizonColorLower;};
layout(std140) uniform Projection {mat4 ProjMat;};
layout(std140) uniform ShadowResolveSettings {
 mat4 LightMatrix[3];mat4 ViewToWorld;vec4 LightDirectionAndMask;vec4 Coverage;vec4 CascadeRanges;mat4 InvProjection;mat4 LightNormalMatrix[3];mat4 TerrainLightMatrix[3];mat4 TerrainNormalMatrix[3];mat4 NextLightMatrix[3];mat4 NextNormalMatrix[3];vec4 EpochBlend;
};
// VOXELLIGHT_ENVIRONMENT_FUNCTIONS
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 fragColor=vec4(0);if(WeatherControls.y<.5)return;
 vec4 q=InvProjection*vec4(texCoord*2-1,.00001,1);vec3 ray=normalize(mat3(ViewToWorld)*(q.xyz/q.w));
 float lower=CelestialData.z-CloudOriginTime.z,upper=lower+32;
 if(abs(ray.y)<.001)return;float a=lower/ray.y,b=upper/ray.y,start=max(0,min(a,b)),stop=min(8000,max(a,b));
 float depth=texture(SceneDepth,texCoord).r;if(depth>0){vec4 p=InvProjection*vec4(texCoord*2-1,depth,1);stop=min(stop,length(p.xyz/p.w));}if(stop<=start)return;
 float stepLength=(stop-start)/24.0;float jitter=envHash(gl_FragCoord.xy+MotionControls.w);vec3 total=vec3(0);float transmittance=1;
 for(int i=0;i<24;i++){float t=start+(float(i)+jitter)*stepLength;vec3 position=vec3(CloudOriginTime.x,CloudOriginTime.z,CloudOriginTime.y)+ray*t;float density=voxelCloudDensity(position);if(density<=0)continue;
  float optical=density*stepLength*.045,alpha=1-exp(-min(optical,6));float sunTau=0;for(int j=1;j<=3;j++)sunTau+=voxelCloudDensity(position+LightDirectionAndMask.xyz*float(j)*8)*.36;
  vec3 color=SkyColorStrength.rgb*(.12+.22*exp(-sunTau))+DirectColorStrength.rgb*DirectColorStrength.a*exp(-sunTau);
  if(CloudControls.y>.5)color=CloudControls.y<1.5?vec3(density):fract(floor(position/vec3(8,4,8))*.17);
  total+=transmittance*alpha*color;transmittance*=1-alpha;if(transmittance<.01)break;
 }
 vec4 current=vec4(total,1-transmittance);fragColor=current;
 if(CloudControls.x>.5&&CloudControls.y<.5&&MotionCameraDelta.w>.5){float middle=(start+stop)*.5;vec3 point=ray*middle+MotionCameraDelta.xyz;vec4 previous=MotionPrevious*vec4(point,1);vec2 uv=previous.xy/previous.w*.5+.5;
  if(previous.w>0&&all(greaterThan(uv,vec2(0)))&&all(lessThan(uv,vec2(1)))){vec4 old=texture(CloudHistory,uv);float agreement=exp(-abs(old.a-current.a)*8);fragColor=mix(current,old,.75*agreement*exp(-length(MotionCameraDelta.xyz)*.08));}
 }
}
