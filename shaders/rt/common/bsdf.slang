#pragma once
// Ported from native/rt/bsdf.h; preserve Material 3 transport semantics.
#include "material.slang"
struct Frame {float3 n,t,b;
 float3 local(float3 a){return float3(dot(a,t),dot(a,b),dot(a,n));}
 float3 world(float3 a){return t*a.x+b*a.y+n*a.z;}
};
Frame makeFrame(float3 normal) { Frame f; f.n=safeNormalize(normal); f.t=safeNormalize(cross(abs(f.n.y)<.9?float3(0,1,0):float3(1,0,0),f.n)); f.b=cross(f.n,f.t); return f; }
Frame makeFrame(float3 normal,float3 tangent,float3 bitangent) { Frame f=makeFrame(normal); float3 projected=tangent-f.n*dot(tangent,f.n); if(dot(projected,projected)>1e-12) { f.t=safeNormalize(projected); f.b=cross(f.n,f.t); if(dot(f.b,bitangent)<0) f.b=-f.b; } return f; }
struct BsdfEval {float3 f;float pdf;};
struct BsdfSample {float3 wi,weight;float pdf,eta;uint flags;};
float fresnelDielectric(float c,float eta){c=clamp(abs(c),0,1);if(abs(eta-1)<1e-7)return 0;if(c==0||isinf(eta))return 1;float sin2=(1-c*c)/(eta*eta);if(sin2>=1)return 1;float ct=sqrt(1-sin2);float rp=(eta*c-ct)/(eta*c+ct);float rs=(c-eta*ct)/(c+eta*ct);return .5f*(rp*rp+rs*rs);}
float conductorChannel(float c,float eta,float k){c=clamp(abs(c),0,1);float c2=c*c;float s2=1-c2;float e2=eta*eta;float k2=k*k;float t0=e2-k2-s2;float a2b2=sqrt(t0*t0+4*e2*k2);float a=sqrt(.5f*(a2b2+t0));float t1=a2b2+c2;float t2=2*c*a;float rs=(t1-t2)/(t1+t2);float t3=c2*a2b2+s2*s2;float t4=t2*s2;return .5f*rs*(1+(t3-t4)/(t3+t4));}
float3 conductorFresnel(Material m,float c){if(m.conductorId==255){float f=pow(1-clamp(c,0,1),5);return m.baseColor+(float3(1,1,1)-m.baseColor)*f;}return m.baseColor*float3(conductorChannel(c,m.eta.x,m.k.x),conductorChannel(c,m.eta.y,m.k.y),conductorChannel(c,m.eta.z,m.k.z));}
float D(float3 h,float ax,float ay){if(h.z<=0)return 0;float q=sq(h.x/ax)+sq(h.y/ay)+h.z*h.z;return 1/(Pi*ax*ay*q*q);}
float lambda(float3 w,float ax,float ay){if(abs(w.z)<1e-7f)return 1e10f;return .5f*(sqrt(1+(sq(ax*w.x)+sq(ay*w.y))/(w.z*w.z))-1);}
float G1(float3 w,float ax,float ay){return 1/(1+lambda(w,ax,ay));}
float G(float3 a,float3 b,float ax,float ay){return 1/(1+lambda(a,ax,ay)+lambda(b,ax,ay));}
float normalPdf(float3 wo,float3 h,float ax,float ay){return D(h,ax,ay)*G1(wo,ax,ay)*abs(dot(wo,h))/max(1e-7f,abs(wo.z));}
float3 sampleNormal(float3 wo,float ax,float ay,inout uint s){float3 vh=safeNormalize(float3(ax*wo.x,ay*wo.y,wo.z));float lens=vh.x*vh.x+vh.y*vh.y;float3 t1=lens>1e-10f?float3(-vh.y,vh.x,0)/sqrt(lens):float3(1,0,0);float3 t2=cross(vh,t1);float r=sqrt(rng(s));float phi=2*Pi*rng(s);float x=r*cos(phi);float y=r*sin(phi);float blend=.5f*(1+vh.z);y=(1-blend)*sqrt(max(0,1-x*x))+blend*y;float3 nh=t1*x+t2*y+vh*sqrt(max(0,1-x*x-y*y));return safeNormalize(float3(ax*nh.x,ay*nh.y,max(0,nh.z)));}
float3 cosine(inout uint s){float r=sqrt(rng(s));float a=2*Pi*rng(s);return float3(r*cos(a),r*sin(a),sqrt(max(0,1-r*r)));}
bool dielectric(Material m){return m.type==DIELECTRIC||m.type==THIN_DIELECTRIC||m.type==WATER;}
bool metal(Material m){return m.type==CONDUCTOR||m.type==COATED_CONDUCTOR;}
float specProbability(Material m){return m.type==COATED_DIFFUSE?0:metal(m)?1:clamp(m.f0*2,.05f,.5f);}


#include "ggx_energy.slang"
float multiscatterMix(Material m,float cosine){return m.multipleScattering?clamp(1-ggxEnergy(sqrt(m.microfacetAlpha*m.alphaV),cosine),0,.9f):0;}
float3 multiscatterF(Material m,float3 wo,float3 wi){if(!m.multipleScattering||m.type==COATED_DIFFUSE||dielectric(m))return float3(0,0,0);float alpha=sqrt(m.microfacetAlpha*m.alphaV);float eo=ggxEnergy(alpha,wo.z);float ei=ggxEnergy(alpha,wi.z);float average=ggxAverageEnergy(alpha);if(average>.99999f)return float3(0,0,0);float3 F=float3(0,0,0);
for(int i=0;i<8;i++){float c=(i+.5f)/8;F=F+(metal(m)?conductorFresnel(m,c):float3(1,1,1)*fresnelDielectric(c,(1+sqrt(m.f0))/(1-sqrt(m.f0))))*(2*c/8);}float3 factor=float3(F.x*F.x*average/max(1e-6f,1-F.x*(1-average)),F.y*F.y*average/max(1e-6f,1-F.y*(1-average)),F.z*F.z*average/max(1e-6f,1-F.z*(1-average)));return factor*((1-eo)*(1-ei)/(Pi*max(1e-6f,1-average)));}
BsdfEval evalLocal(Material m,float3 wo,float3 wi,float eta=1.5f,int forced=0){
 BsdfEval result = {float3(0,0,0),0};if(wo.z<=0||abs(wi.z)<1e-7f)return result;
 float ax=max(.0005f,m.microfacetAlpha);float ay=max(.0005f,m.alphaV);
 bool refl=wi.z>0;
 if(dielectric(m)){
  if((forced==1&&!refl)||(forced==2&&refl))return result;
  if(m.type==THIN_DIELECTRIC)return result; // discrete two-interface sheet, evaluated through sample only
  float3 h=safeNormalize(wo+(refl?wi:wi*eta));if(h.z<0)h=h*-1;
  float oh=dot(wo,h);float ih=dot(wi,h);if(oh*wo.z<=0||ih*wi.z<=0)return result;
  float F=fresnelDielectric(oh,eta);float select=forced==1?1:forced==2?0:F;
  if(refl){result.f=float3(1,1,1)*(D(h,ax,ay)*G(wo,wi,ax,ay)*F/(4*wo.z*wi.z));result.pdf=normalPdf(wo,h,ax,ay)/(4*abs(oh))*select;}
  else{float den=sq(ih+oh/eta);if(den<1e-15f)return result;result.f=float3(1,1,1)*(D(h,ax,ay)*G(wo,wi,ax,ay)*(1-F)*abs(ih*oh/(den*wi.z*wo.z))/(eta*eta)*m.transmission);result.pdf=normalPdf(wo,h,ax,ay)*abs(ih)/den*(forced==2?1:1-select);}
  return result;
 }
 if(m.type==DIFFUSE_TRANSMISSION&&!refl){result.f=m.baseColor*(m.transmission/Pi);result.pdf=abs(wi.z)/Pi*m.transmission;return result;}
 if(!refl)return result;
 float3 h=safeNormalize(wo+wi);float oh=max(0,dot(wo,h));float pdfSpec=normalPdf(wo,h,ax,ay)/max(1e-7f,4*oh);
 float3 F=metal(m)?conductorFresnel(m,oh):float3(1,1,1)*fresnelDielectric(oh,sqrt(max(0,m.f0))>=.999f?1000:(1+sqrt(m.f0))/(1-sqrt(m.f0)));
 float3 spec=F*(D(h,ax,ay)*G(wo,wi,ax,ay)/max(1e-7f,4*wo.z*wi.z));
 if(m.type==COATED_DIFFUSE)spec=float3(0,0,0);
 float q=multiscatterMix(m,wo.z);spec=spec+multiscatterF(m,wo,wi);pdfSpec=pdfSpec*(1-q)+wi.z/Pi*q;
 float p=specProbability(m);float root=sqrt(m.f0);float baseIOR=root>=.999f?1000:(1+root)/(1-root);float3 diffuse=m.baseColor*((1-fresnelDielectric(wo.z,baseIOR))*(1-fresnelDielectric(wi.z,baseIOR))/Pi);
 if(m.type==COATED_DIFFUSE)diffuse=m.baseColor/Pi;
 if(m.type==ROUGH_DIFFUSE){float sigma=sqrt(m.microfacetAlpha)*.5f;float s2=sigma*sigma;float A=1-.5f*s2/(s2+.33f);float B=.45f*s2/(s2+.09f);float so=sqrt(max(0,1-wo.z*wo.z));float si=sqrt(max(0,1-wi.z*wi.z));float az=(wo.x*wi.x+wo.y*wi.y)/max(1e-7f,so*si);diffuse=diffuse*(A+B*max(0,az)*max(so,si)*min(so/max(wo.z,1e-5f),si/max(wi.z,1e-5f)));}
 if(m.type==DIFFUSE||m.type==EMISSIVE){result={m.baseColor/Pi,wi.z/Pi};}
 else if(m.type==DIFFUSE_TRANSMISSION){result={m.baseColor*((1-m.transmission)/Pi),wi.z/Pi*(1-m.transmission)};}
 else result={metal(m)?spec:diffuse+spec,p*pdfSpec+(1-p)*wi.z/Pi};
 if(m.coatWeight>0){float fcO=fresnelDielectric(wo.z,m.coatIOR);float fcI=fresnelDielectric(wi.z,m.coatIOR);float fc=fresnelDielectric(oh,m.coatIOR);float ca=max(.0005f,m.coatAlpha);float cp=m.coatWeight*.5f;float3 coat=float3(1,1,1)*(fc*D(h,ca,ca)*G(wo,wi,ca,ca)/(4*wo.z*wi.z));float pdf=normalPdf(wo,h,ca,ca)/max(1e-7f,4*oh);result.f=result.f*(1-m.coatWeight+m.coatWeight*(1-fcO)*(1-fcI))+coat*m.coatWeight;result.pdf=result.pdf*(1-cp)+pdf*cp;}
 return result;
}
BsdfEval evalBsdf(Material m,Frame f,float3 wo,float3 wi,float eta=1.5f,int forced=0){return evalLocal(m,f.local(wo),f.local(wi),eta,forced);}
BsdfSample sampleBsdf(Material m,Frame f,float3 woWorld,inout uint seed,float eta=1.5f,int forced=0){
 float3 wo=f.local(woWorld);float3 wi=float3(0,0,0);BsdfSample s = {float3(0,0,0),float3(0,0,0),0,1,0};if(wo.z<=0)return s;
 float ax=max(.0005f,m.microfacetAlpha);float ay=max(.0005f,m.alphaV);uint flags=0;
 if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&abs(eta-1)<1e-7f){if(forced==1)return s;s.wi=woWorld*-1;s.weight=float3(1,1,1)*m.transmission;s.pdf=1;s.eta=1;s.flags=SPECULAR|DELTA|TRANSMISSION;return s;}
 if(m.type==THIN_DIELECTRIC){float R=fresnelDielectric(wo.z,eta);R=2*R/(1+R);bool refl=forced==1||forced!=2&&rng(seed)<R;float probability=forced!=0?1:refl?R:1-R;s.wi=f.world(refl?float3(-wo.x,-wo.y,wo.z):wo*-1);s.weight=float3(1,1,1)*((refl?R:(1-R)*m.transmission)/max(probability,1e-7f));if(!refl)s.weight=s.weight*expNeg(m.sigmaA,m.thickness/max(.01f,wo.z));s.pdf=probability;s.flags=SPECULAR|DELTA|(refl?REFLECTION:TRANSMISSION);return s;}
 if(dielectric(m)){float3 h=sampleNormal(wo,ax,ay,seed);float F=fresnelDielectric(dot(wo,h),eta);bool refl=forced==1||forced!=2&&rng(seed)<F;if(refl){wi=h*(2*dot(wo,h))-wo;flags=GLOSSY|REFLECTION;if(wi.z<=0)return s;}else{float c=dot(wo,h);float k=1-(1-c*c)/(eta*eta);if(k<=0)return s;wi=wo*(-1/eta)+h*(c/eta-sqrt(k));flags=GLOSSY|TRANSMISSION;s.eta=eta;if(wi.z>=0)return s;}}
 else {float cp=m.coatWeight*.5f;float u=rng(seed);if(u<cp){float3 h=sampleNormal(wo,max(.0005f,m.coatAlpha),max(.0005f,m.coatAlpha),seed);wi=h*(2*dot(wo,h))-wo;flags=GLOSSY|REFLECTION;}
  else if(m.type==DIFFUSE_TRANSMISSION){wi=cosine(seed);if(rng(seed)<m.transmission){wi.z=-wi.z;flags=DIFFUSE_LOBE|TRANSMISSION;}else flags=DIFFUSE_LOBE|REFLECTION;}
  else if(m.type!=DIFFUSE&&m.type!=EMISSIVE&&rng(seed)<specProbability(m)){if(rng(seed)<multiscatterMix(m,wo.z))wi=cosine(seed);else{float3 h=sampleNormal(wo,ax,ay,seed);wi=h*(2*dot(wo,h))-wo;}flags=GLOSSY|REFLECTION;if(wi.z<=0)return s;}
  else {wi=cosine(seed);flags=DIFFUSE_LOBE|REFLECTION;}}
 BsdfEval e=evalLocal(m,wo,wi,eta,forced);if(e.pdf<=1e-12f)return s;s.wi=f.world(wi);s.pdf=e.pdf;s.weight=e.f*(abs(wi.z)/e.pdf);s.flags=flags;return s;
}

BsdfEval evalGlossy(Material m,Frame f,float3 woWorld,float3 wiWorld){
 if(m.type==DIFFUSE||m.type==EMISSIVE||m.type==DIFFUSE_TRANSMISSION)return {float3(0,0,0),0};
 float3 wo=f.local(woWorld);float3 wi=f.local(wiWorld);if(wo.z<=0||wi.z<=0)return {float3(0,0,0),0};float3 h=safeNormalize(wo+wi);float oh=abs(dot(wo,h));float ax=max(.0005f,m.microfacetAlpha);float ay=max(.0005f,m.alphaV);float ior=sqrt(m.f0)>=.999f?1000:(1+sqrt(m.f0))/(1-sqrt(m.f0));
 float3 F=metal(m)?conductorFresnel(m,oh):float3(1,1,1)*fresnelDielectric(oh,ior);float3 base=F*(D(h,ax,ay)*G(wo,wi,ax,ay)/(4*wo.z*wi.z));float pdf=normalPdf(wo,h,ax,ay)/max(1e-7f,4*oh);
 float q=multiscatterMix(m,wo.z);base=base+multiscatterF(m,wo,wi);pdf=pdf*(1-q)+wi.z/Pi*q;
 if(m.type==COATED_DIFFUSE){base=float3(0,0,0);pdf=0;}
 float cp=m.type==COATED_DIFFUSE?1:m.coatWeight*.5f;float ca=max(.0005f,m.coatAlpha);if(m.coatWeight>0){float attenuation=1-m.coatWeight+m.coatWeight*(1-fresnelDielectric(wo.z,m.coatIOR))*(1-fresnelDielectric(wi.z,m.coatIOR));float3 coat=float3(1,1,1)*(fresnelDielectric(oh,m.coatIOR)*D(h,ca,ca)*G(wo,wi,ca,ca)/(4*wo.z*wi.z));base=base*attenuation+coat*m.coatWeight;pdf=pdf*(1-cp)+normalPdf(wo,h,ca,ca)/(4*oh)*cp;}
 return {base,pdf};
}
BsdfSample sampleGlossy(Material m,Frame f,float3 woWorld,inout uint seed){
 float3 wo=f.local(woWorld);BsdfSample s = {float3(0,0,0),float3(0,0,0),0,1,0};if(m.type==DIFFUSE||m.type==EMISSIVE||m.type==DIFFUSE_TRANSMISSION)return s;if(wo.z<=0)return s;bool coat=m.type==COATED_DIFFUSE||rng(seed)<m.coatWeight*.5f;float ax=max(.0005f,coat?m.coatAlpha:m.microfacetAlpha);float ay=max(.0005f,coat?m.coatAlpha:m.alphaV);float3 wi;if(!coat&&rng(seed)<multiscatterMix(m,wo.z))wi=cosine(seed);else{float3 h=sampleNormal(wo,ax,ay,seed);wi=h*(2*dot(wo,h))-wo;}if(wi.z<=0)return s;s.wi=f.world(wi);BsdfEval e=evalGlossy(m,f,woWorld,s.wi);if(e.pdf<=0)return s;s.weight=e.f*(wi.z/e.pdf);s.pdf=e.pdf;s.flags=GLOSSY|REFLECTION;return s;
}

// Conditional lobe PDFs: never multiply a specular-only continuation by the
// full mixture's branch probability. Protected layered/transmitting BSDFs use FULL.
bool cacheLobes(Material m){return (m.type==DIFFUSE||m.type==ROUGH_DIFFUSE)&&m.coatWeight==0&&m.transmission==0;}
BsdfEval evalSpecularLobe(Material m,Frame f,float3 wo,float3 wi){
    if(m.type!=ROUGH_DIFFUSE||!cacheLobes(m))return (BsdfEval)0;
    float3 o=f.local(wo),i=f.local(wi);if(o.z<=0||i.z<1e-7)return (BsdfEval)0;BsdfEval e=evalGlossy(m,f,wo,wi);float denominator=4*o.z*i.z;
    // evalLocal clamps its single-scatter denominator. Match that boundary
    // without attenuating the separate GGX multiple-scattering compensation.
    if(o.z>0&&i.z>0&&denominator<1e-7){float3 multi=multiscatterF(m,o,i);e.f=(e.f-multi)*(denominator/1e-7)+multi;}
    return e;
}
BsdfEval evalDiffuseLobe(Material m,Frame f,float3 woWorld,float3 wiWorld){
    BsdfEval e=(BsdfEval)0;float3 wo=f.local(woWorld),wi=f.local(wiWorld);
    if(!cacheLobes(m)||wo.z<=0||wi.z<1e-7)return e;
    e.pdf=wi.z/Pi;e.f=m.baseColor/Pi;if(m.type==DIFFUSE)return e;
    float root=sqrt(m.f0),ior=root>=.999f?1000:(1+root)/(1-root);
    float s2=m.microfacetAlpha*.25,A=1-.5*s2/(s2+.33),B=.45*s2/(s2+.09);
    float so=sqrt(max(0,1-wo.z*wo.z)),si=sqrt(max(0,1-wi.z*wi.z));
    float az=dot(wo.xy,wi.xy)/max(1e-7,so*si);
    e.f*=(1-fresnelDielectric(wo.z,ior))*(1-fresnelDielectric(wi.z,ior))*(A+B*max(0,az)*max(so,si)*min(so/max(wo.z,1e-5),si/max(wi.z,1e-5)));
    return e;
}
// Cached diffuse has no BSDF continuation to compete with NEE. Only the exact
// specular lobe receives the complementary NEE/BSDF MIS weights.
float3 cacheDirectBsdf(Material m,Frame f,float3 wo,float3 wi,float neePdf,bool terminal){
    BsdfEval d=evalDiffuseLobe(m,f,wo,wi),s=evalSpecularLobe(m,f,wo,wi);
    s.pdf*=specProbability(m);
    return d.f+s.f*(terminal?1:powerHeuristic(neePdf,s.pdf));
}
// Preserve FULL's specular branch frequency. The cached diffuse branch emits no
// ray; surviving specular paths use the marginal PDF and inverse probability.
BsdfSample sampleCachedSpecular(Material m,Frame f,float3 wo,inout uint seed){
    float probability=specProbability(m);if(rng(seed)>=probability)return (BsdfSample)0;
    BsdfSample s=sampleGlossy(m,f,wo,seed);if(s.pdf>0){BsdfEval e=evalSpecularLobe(m,f,wo,s.wi);s.pdf=e.pdf*probability;s.weight=e.f*(max(0,dot(f.n,s.wi))/max(1e-20,s.pdf));}return s;
}
