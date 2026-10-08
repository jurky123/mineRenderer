#define RT_CPU_FIXTURE 1
#define RT_POLICY_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void realtime_policy(uint3 tid:SV_DispatchThreadID){
    for(uint row=544;row<1568;row++)pageFeedback[row]=uint4(0xdeadc0deu);
    uint p=rtProbe(0),request=rtRequestBase();float3 point=float3(1,1,1),n=float3(0,1,0);
    pageFeedback[1568]=uint4(1,7,0,1024);
    pageFeedback[p]=uint4(1,1,0,1);pageFeedback[request+1]=asuint(float4(3,4,5,1));pageFeedback[request+2]=asuint(float4(.25,.5,.75,0));pageFeedback[request+4]=asuint(float4(point,0));pageFeedback[request+5]=asuint(float4(n,0));
    // Stratified uniform-hemisphere observations of constant incoming radiance.
    for(uint i=0;i<128;i++){float u=(i+.5)/128,phi=2*Pi*i*.61803398875;float3 wi=float3(sqrt(1-u*u)*cos(phi),u,sqrt(1-u*u)*sin(phi));pageFeedback[request+3]=asuint(float4(wi,1/(2*Pi)));output[0]=float4(3.5,5.5,8,1);rtTrain(0);}
    uint address=rtCacheAddress(point,n);float3 value=rtShIrradiance(asfloat(pageFeedback[address+4]),asfloat(pageFeedback[address+5]),asfloat(pageFeedback[address+6]),asfloat(pageFeedback[address+7]),n);
    output[1]=float4(value,rtCacheValid(address,point,n)?1:0);
    output[2]=float4(rtInterval(8,.01),rtInterval(4,.05),rtInterval(32,8),rtCacheValid(address,point+float3(0,.1,0),n)?1:0);
    pageFeedback[1568].y=8;output[3]=float4(rtCacheValid(address,point,n)?1:0,0,0,0);pageFeedback[1568].y=7;pageFeedback[address].z=camera.frame-129;output[3].y=rtCacheValid(address,point,n)?1:0;rtTrain(0);output[9]=float4(pageFeedback[address].w,0,0,0);
    Material m=(Material)0;m.type=DIFFUSE;m.microfacetAlpha=.5;output[4]=float4(rtDiffuse(m)?1:0,0,0,0);m.type=CONDUCTOR;output[4].y=rtDiffuse(m)?1:0;m.type=WATER;output[4].z=rtDiffuse(m)?1:0;
    pageFeedback[1568].x=0;output[4].w=rtPolicy()?1:0;
    pageFeedback[1568]=uint4(3,7,0,1024);pageFeedback[1570]=asuint(float4(1,0,0,0));pageFeedback[1571]=asuint(float4(0,1,0,0));pageFeedback[1572]=asuint(float4(0,0,1,0));pageFeedback[1573]=asuint(float4(0,0,0,1));pageFeedback[1574]=uint4(asuint(float3(1,1,0)),1);
    uint previous=rtHistory(camera.width*camera.height-1,(camera.frame+1)&1);pageFeedback[previous]=asuint(float4(.2,.3,.4,8));pageFeedback[previous+1]=asuint(float4(.2,.3,.4,.01));pageFeedback[previous+2]=asuint(float4(0,0,0,.3));pageFeedback[previous+3]=0;pageFeedback[previous+4]=asuint(float4(point,1));pageFeedback[previous+5]=asuint(float4(n,.5));pageFeedback[previous+6]=uint4(41,7,7,camera.frame-1);pageFeedback[previous+7]=asuint(float4(.6,.6,.6,DIFFUSE));pageFeedback[p]=0;
    PathState path=(PathState)0;path.origin.w=1;TerrainSurface surface=(TerrainSurface)0;surface.material=(Material)0;surface.material.type=DIFFUSE;surface.material.materialId=7;surface.material.microfacetAlpha=.5;surface.material.baseColor=.6;SurfacePayload hit=(SurfacePayload)0;hit.triangle=41;hit.distance=1;MediumStack media=(MediumStack)0;
    bool reused=rtSparse(path,surface,hit,point,n,media);output[5]=float4(output[0].xyz,reused?1:0);
    path.origin.w=1;surface.flags=128;output[6]=float4(rtSparse(path,surface,hit,point,n,media)?1:0,0,0,0);surface.flags=0;pageFeedback[1568].y=8;output[6].y=rtSparse(path,surface,hit,point,n,media)?1:0;pageFeedback[1568].y=7;hit.triangle=42;output[6].z=rtSparse(path,surface,hit,point,n,media)?1:0;

    hit.triangle=41;surface.material.baseColor=float3(.3,.9,.6);path.origin.w=1;bool textured=rtSparse(path,surface,hit,point,n,media);output[10]=float4(output[0].xyz,textured?1:0);
    float q=128/127.5-1;float3 neutral=safeNormalize(float3(q,sqrt(1-2*q*q),q));output[11]=float4(rtFlatNormal(neutral,n)?1:0,rtFlatNormal(safeNormalize(float3(.2,1,0)),n)?1:0,0,0);
    surface.material.baseColor=float3(0,.9,.6);pageFeedback[p]=0;output[12]=float4(rtSparse(path,surface,hit,point,n,media)?1:0,0,0,0);
    pageFeedback[previous+7]=asuint(float4(.01,.6,.6,DIFFUSE));surface.material.baseColor=float3(.015,.6,.6);output[13]=float4(rtSparse(path,surface,hit,point,n,media)?1:0,0,0,0);
    // A rough diffuse BRDF includes uncolored specular: do not remodulate it.
    surface.material.type=ROUGH_DIFFUSE;surface.material.baseColor=float3(.3,.9,.6);pageFeedback[previous+7]=asuint(float4(.6,.6,.6,ROUGH_DIFFUSE));output[15]=float4(rtSparse(path,surface,hit,point,n,media)?1:0,0,0,0);
    uint neighbor=rtHistory(1,(camera.frame+1)&1);for(uint slot=0;slot<8;slot++)pageFeedback[neighbor+slot]=pageFeedback[previous+slot];pageFeedback[previous+6].x=99;
    output[14]=float4(rtPrevious(point,n,float3(.6),41,7,1)==neighbor?1:0,0,0,0);
    pageFeedback[neighbor+6].x=99;output[14].y=rtPrevious(point,n,float3(.6),41,7,1)==0?1:0;
    // A noisy but sufficiently trained mean can be used; a cold or stale one cannot.
    pageFeedback[address]=uint4(0,7,camera.frame,64);pageFeedback[address+8]=asuint(float4(1,2,0,0));
    output[18]=float4(rtCacheStatus(address,point,n),0,0,0);pageFeedback[address].w=24;output[18].y=rtCacheStatus(address,point,n);pageFeedback[address].w=23;output[18].z=rtCacheStatus(address,point,n);pageFeedback[address].w=64;pageFeedback[address].z=camera.frame-129;output[18].w=rtCacheStatus(address,point,n);
    // History stores the average of actual paths and moments of their noise.
    uint guide=rtCapacity()+4;float4 savedNormal=output[guide+camera.width*camera.height];output[guide+camera.width*camera.height]=float4(n,.5);output[guide+camera.width*camera.height*2]=float4(point,1);
    pageFeedback[previous]=asuint(float4(.2,.3,.4,8));pageFeedback[previous+1]=asuint(float4(.2,.3,.4,.01));pageFeedback[previous+2]=asuint(float4(0,0,0,.5));pageFeedback[previous+3]=0;pageFeedback[previous+6]=uint4(41,7,7,camera.frame-1);pageFeedback[previous+7]=asuint(float4(.6,.6,.6,ROUGH_DIFFUSE));
    pageFeedback[p]=uint4(0,0,previous,0);pageFeedback[p+1]=uint4(41,7,7,0);pageFeedback[p+2]=asuint(float4(.6,.6,.6,ROUGH_DIFFUSE));
    rtSaveHistory(0,float3(.8,.9,1),float3(.8,.9,1),float3(0),float3(0));uint current=rtHistory(0,camera.frame&1);output[17]=asfloat(pageFeedback[current]);
    pageFeedback[p].x=2;rtSaveHistory(0,float3(.2,.3,.4),float3(.2,.3,.4),float3(0),float3(0));output[19]=float4(asfloat(pageFeedback[current]).w,pageFeedback[current+6].w,rtInterval(8,.1),rtInterval(3,0));
    output[guide+camera.width*camera.height]=savedNormal;
    surface.material.type=DIFFUSE;surface.material.baseColor=.6;surface.flags=0;pageFeedback[previous+7]=asuint(float4(.6,.6,.6,DIFFUSE));pageFeedback[previous+6].w=camera.frame-2;pageFeedback[p]=0;path.origin.w=1;
    output[20]=float4(rtSparse(path,surface,hit,point,n,media)?1:0,0,0,0);pageFeedback[1568].z=1;pageFeedback[p]=0;path.origin.w=1;output[20].y=rtSparse(path,surface,hit,point,n,media)?1:0;pageFeedback[1568].z=0;
    // Quad diagonal changes retain a face; separate parallel planes/owners do not.
    float3 a=float3(0,0,0),b=float3(1,0,0),c=float3(1,0,1),d=float3(0,0,1),facePoint=float3(.49,0,.51);
    uint face=rtFaceIdentity(19,1,a,b,c,facePoint);
    output[21]=float4(face==rtFaceIdentity(19,2,c,d,a,facePoint)?1:0,face!=rtFaceIdentity(20,1,a,b,c,facePoint)?1:0,face!=rtFaceIdentity(19,1,a+float3(0,.01,0),b+float3(0,.01,0),c+float3(0,.01,0),facePoint)?1:0,0);
    // Same cell, two heights with independent red/blue radiance.
    float3 low=float3(8.5,.5,8.5),high=float3(8.5,1,8.5);pageFeedback[p]=uint4(1,1,0,1);
    uint lowAddress=rtCacheAddress(low,n);pageFeedback[request+4]=asuint(float4(low,0));pageFeedback[request+3]=asuint(float4(n,1/Pi));pageFeedback[request+1]=asuint(float4(0,0,0,1));pageFeedback[request+2]=asuint(float4(1,1,1,0));
    for(uint observation=0;observation<32;observation++){output[0]=float4(3,0,0,1);rtTrain(0);}
    uint highAddress=rtCacheAddress(high,n);pageFeedback[request+4]=asuint(float4(high,0));
    for(uint observation=0;observation<32;observation++){output[0]=float4(0,0,5,1);rtTrain(0);}
    output[22]=float4(lowAddress!=highAddress?1:0,rtCacheStatus(lowAddress,low,n),rtCacheStatus(highAddress,high,n),rtCacheStatus(lowAddress,high,n));
    float3 lowValue=rtShIrradiance(asfloat(pageFeedback[lowAddress+4]),asfloat(pageFeedback[lowAddress+5]),asfloat(pageFeedback[lowAddress+6]),asfloat(pageFeedback[lowAddress+7]),n);
    float3 highValue=rtShIrradiance(asfloat(pageFeedback[highAddress+4]),asfloat(pageFeedback[highAddress+5]),asfloat(pageFeedback[highAddress+6]),asfloat(pageFeedback[highAddress+7]),n);
    output[23]=float4(lowValue.x,lowValue.z,highValue.x,highValue.z);
    // A matching training cell must not reserve a second same-frame suffix.
    output[24]=float4(rtClaim(lowAddress,low,n)?1:0,0,0,0);output[24].y=rtClaim(lowAddress,low,n)?1:0;output[24].z=rtClaim(highAddress,high,n)?1:0;
    pageFeedback[lowAddress].w=1;output[25]=float4(rtCacheStatus(lowAddress,high,n),rtCacheStatus(lowAddress,low,n),0,0);
    // A mature neighbor can preserve confidence when the nearest matching history is cold.
    for(uint slot=0;slot<8;slot++)pageFeedback[neighbor+slot]=pageFeedback[previous+slot];pageFeedback[neighbor+6].x=41;
    pageFeedback[previous]=asuint(float4(.2,.3,.4,2));pageFeedback[neighbor]=asuint(float4(.2,.3,.4,8));
    output[26]=float4(rtPrevious(point,n,float3(.6),41,7,1)==neighbor?1:0,0,0,0);
    pageFeedback[previous]=asuint(float4(.2,.3,.4,16));output[26].y=rtPrevious(point,n,float3(.6),41,7,1)==previous?1:0;
    // Exercise production tail scheduling: a duplicate must not consume another ticket.
    m=(Material)0;m.type=DIFFUSE;m.microfacetAlpha=.5;
    float3 fresh=float3(20.5,.5,20.5);pageFeedback[1568]=uint4(1,7,0,1024);pageFeedback[1604].x=0;pageFeedback[p]=0;
    path.throughput.w=1;path.reserved0=0;float3 response;bool training;
    rtTail(path,m,fresh,n,media,response,training);output[27]=float4(training?1:0,0,0,0);
    path.reserved0=1;pageFeedback[rtProbe(1)]=0;rtTail(path,m,fresh,n,media,response,training);
    output[27].y=training?1:0;output[27].z=pageFeedback[1604].x;output[27].w=pageFeedback[rtCacheAddress(fresh,n)].w;
    // Primary cache is opt-in and excludes every non-pure-diffuse lobe and medium.
    path.reserved0=0;path.throughput.w=0;pageFeedback[p]=0;pageFeedback[1604].x=0;pageFeedback[1568]=uint4(1,7,0,1024);
    rtTail(path,m,float3(30.5,.5,30.5),n,media,response,training);output[28]=float4(training?1:0,0,0,0);
    pageFeedback[1568].x=5;rtTail(path,m,float3(30.5,.5,30.5),n,media,response,training);output[28].y=training?1:0;output[28].z=pageFeedback[p].w;output[28].w=pageFeedback[rtRequest(0)].y;
    pageFeedback[p]=0;m.type=ROUGH_DIFFUSE;rtTail(path,m,float3(40.5,.5,40.5),n,media,response,training);output[29]=float4(training?1:0,0,0,0);
    m.type=WATER;rtTail(path,m,float3(40.5,.5,40.5),n,media,response,training);output[29].y=training?1:0;
    m.type=DIFFUSE;media.count=1;rtTail(path,m,float3(40.5,.5,40.5),n,media,response,training);output[29].z=training?1:0;media.count=0;
    pageFeedback[1604].x=1024;rtTail(path,m,float3(40.5,.5,40.5),n,media,response,training);output[29].w=training?1:0;
    output[30]=float4(pageFeedback[1604].x,pageFeedback[p].w,rtRequestBase()-rtCacheBase(),rtHistory(0,0)-rtProbe(0));
    uint corrupt=0;for(uint row=544;row<1568;row++)if(any(pageFeedback[row]!=uint4(0xdeadc0deu)))corrupt++;output[8]=float4(corrupt,0,0,0);

}
