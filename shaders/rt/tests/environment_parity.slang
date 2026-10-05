[[vk::binding(6,0)]] ByteAddressBuffer assets;
#include "../common/environment.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void environment_parity(uint3 tid:SV_DispatchThreadID) {
    uint i=tid.x,seed=(i+1)*747796405u;
    float3 direction=sampleEnvironment(seed);result[i*9]=float4(direction,environmentDensity(direction));
    uint2 size=environmentSize();uint row=(i/size.x)%size.y,column=i%size.x;
    float theta=Pi*(row+.5)/size.y,phi=2*Pi*(column+.5)/size.x;
    result[i*9+1]=float4(environmentDensity(float3(sin(theta)*cos(phi),cos(theta),sin(theta)*sin(phi))),environmentSolidAngle(row),environmentPower(),0);
    LightSample light=sampleOneLight(float3(0),seed);result[i*9+2]=float4(light.wi,light.pdf);result[i*9+3]=float4(light.radiance,light.distance);
    result[i*9+4]=float4(lightProbabilities(),(float)light.kind);
    result[i*9+5]=float4(missRadiance(direction,.2,false),0);
    Material m=(Material)0;m.type=DIFFUSE;m.baseColor=float3(.6,.4,.2);Frame frame=makeFrame(float3(0,1,0));float3 wo=frame.n;
    BsdfEval e=evalBsdf(m,frame,wo,light.wi);float3 value=float3(0);
    if(light.pdf>0)value+=e.f*abs(dot(frame.n,light.wi))*light.radiance*(light.kind==3?1:powerHeuristic(light.pdf,e.pdf))/light.pdf;
    BsdfSample b=sampleBsdf(m,frame,wo,seed);if(b.pdf>0)value+=b.weight*missRadiance(b.wi,b.pdf,(b.flags&DELTA)!=0);
    result[i*9+6]=float4(value,0);
    result[i*9+7]=float4(light.pdf>0?e.f*abs(dot(frame.n,light.wi))*light.radiance*lightMisWeight(light,e.pdf,false)/light.pdf:float3(0),0);
    MediumStack media=cameraMedia();result[i*9+8]=float4(media.ior(),(float)media.count,media.count>0?media.entries[0].sigmaA.x:0,media.count>0?media.entries[0].sigmaS.z:0);
}
