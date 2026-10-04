#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform RtEnvironmentSettings {vec4 DirectColorStrength;vec4 SkyColorStrength;vec4 HorizonColorLower;vec4 RtSun;};
// VOXELLIGHT_ENVIRONMENT_FUNCTIONS
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 float phi=texCoord.x*6.283185307,theta=texCoord.y*3.141592654;
 vec3 ray=vec3(sin(theta)*cos(phi),cos(theta),sin(theta)*sin(phi));
 vec3 radiance=environmentSkyModel(ray,SkyColorStrength.rgb,HorizonColorLower.rgb,SkyColorStrength.a,false);
 if(WeatherControls.y>.5&&abs(ray.y)>.001){
  float a=(CelestialData.z-CloudOriginTime.z)/ray.y,b=(CelestialData.z+32-CloudOriginTime.z)/ray.y;
  float start=max(0,min(a,b)),stop=min(8000,max(a,b)),transmittance=1;vec3 cloud=vec3(0);
  if(stop>start){float stepLength=(stop-start)/16;for(int i=0;i<16;i++){
   vec3 p=vec3(CloudOriginTime.x,CloudOriginTime.z,CloudOriginTime.y)+ray*(start+(float(i)+.5)*stepLength);
   float density=voxelCloudDensity(p),tau=0;for(int j=1;j<=3;j++)tau+=voxelCloudDensity(p+RtSun.xyz*float(j)*8)*.36;
   float alpha=1-exp(-min(6,density*stepLength*.045));
   vec3 color=SkyColorStrength.rgb*(.12+.22*exp(-tau))+DirectColorStrength.rgb*DirectColorStrength.a*exp(-tau);
   cloud+=transmittance*alpha*color;transmittance*=1-alpha;if(transmittance<.01)break;
  }radiance=radiance*transmittance+cloud;}
 }
 fragColor=vec4(max(radiance,vec3(0)),1);
}
