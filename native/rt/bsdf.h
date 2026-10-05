#pragma once
// Original implementation of GGX visible-normal sampling and RGB transport.
// Shared verbatim by CUDA and the CPU Monte Carlo regression executable.
#ifdef __CUDACC__
#define RT_FN static __forceinline__ __device__
#define RT_HEAVY static __noinline__ __device__
#define RT_METHOD __forceinline__ __device__
#else
#include <cmath>
#include <algorithm>
#define RT_FN static inline
#define RT_HEAVY static inline
#define RT_METHOD inline
#endif
namespace rt {
constexpr float Pi=3.14159265358979323846f;
struct Vec {float x,y,z;};
RT_FN Vec V(float x,float y,float z){return {x,y,z};}
RT_FN Vec operator+(Vec a,Vec b){return V(a.x+b.x,a.y+b.y,a.z+b.z);}
RT_FN Vec operator-(Vec a,Vec b){return V(a.x-b.x,a.y-b.y,a.z-b.z);}
RT_FN Vec operator*(Vec a,float b){return V(a.x*b,a.y*b,a.z*b);}
RT_FN Vec operator*(float b,Vec a){return a*b;}
RT_FN Vec operator/(Vec a,float b){return a*(1/b);}
RT_FN Vec operator*(Vec a,Vec b){return V(a.x*b.x,a.y*b.y,a.z*b.z);}
RT_FN float dot(Vec a,Vec b){return a.x*b.x+a.y*b.y+a.z*b.z;}
RT_FN Vec cross(Vec a,Vec b){return V(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
RT_FN Vec normalize(Vec a){return a/sqrtf(fmaxf(1e-20f,dot(a,a)));}
RT_FN float clamp(float a,float lo,float hi){return fmaxf(lo,fminf(hi,a));}
RT_FN float sq(float x){return x*x;}
RT_FN float maxComponent(Vec a){return fmaxf(a.x,fmaxf(a.y,a.z));}
RT_FN Vec expNeg(Vec a,float d){return V(expf(-a.x*d),expf(-a.y*d),expf(-a.z*d));}
RT_FN float rng(unsigned& s){s=s*1664525u+1013904223u;return (s>>8)*0x1p-24f;}
RT_FN float powerHeuristic(float a,float b){if(std::isnan(a)||std::isnan(b)||a<=0)return 0;if(std::isinf(a))return std::isinf(b)?.5f:1;if(std::isinf(b))return 0;b=fmaxf(b,0);float scale=fmaxf(a,b);a/=scale;b/=scale;return sq(a)/(sq(a)+sq(b));}
enum MaterialClass : unsigned {DIFFUSE,ROUGH_DIFFUSE,CONDUCTOR,DIELECTRIC,THIN_DIELECTRIC,COATED_DIFFUSE,COATED_CONDUCTOR,DIFFUSE_TRANSMISSION,EMISSIVE,WATER};
enum Flags : unsigned {DIFFUSE_LOBE=1,GLOSSY=2,SPECULAR=4,REFLECTION=8,TRANSMISSION=16,DELTA=32,DISPERSIVE=64};
struct Material {
 Vec baseColor=V(.5f,.5f,.5f),eta=V(1.5f,1.5f,1.5f),k=V(0,0,0),emission=V(0,0,0),sigmaA=V(0,0,0),sigmaS=V(0,0,0);
 float microfacetAlpha=.5f,alphaV=.5f,f0=.04f,ior=1.5f,transmission=0,coatWeight=0,coatAlpha=.04f,coatIOR=1.5f,porosity=0,sss=0,phaseG=0,thickness=.0625f,abbeNumber=0;bool multipleScattering=true;
 unsigned type=ROUGH_DIFFUSE,conductorId=0,materialId=0,mediumId=0;
};
struct Frame {Vec n,t,b;RT_METHOD explicit Frame(Vec normal):n(normalize(normal)){t=normalize(cross(fabsf(n.y)<.9f?V(0,1,0):V(1,0,0),n));b=cross(n,t);}
 RT_METHOD Frame(Vec normal,Vec tangent,Vec bitangent):Frame(normal){
  Vec projected=tangent-n*dot(tangent,n);
  if(dot(projected,projected)>1e-12f){t=normalize(projected);b=cross(n,t);if(dot(b,bitangent)<0)b=b*-1;}
 }
 RT_METHOD Vec local(Vec a)const{return V(dot(a,t),dot(a,b),dot(a,n));}
 RT_METHOD Vec world(Vec a)const{return t*a.x+b*a.y+n*a.z;}
};
struct BsdfEval {Vec f;float pdf;};
struct BsdfSample {Vec wi,weight;float pdf,eta;unsigned flags;};
RT_FN float fresnelDielectric(float c,float eta){c=clamp(fabsf(c),0,1);if(fabsf(eta-1)<1e-7f)return 0;if(c==0||std::isinf(eta))return 1;float sin2=(1-c*c)/(eta*eta);if(sin2>=1)return 1;float ct=sqrtf(1-sin2);float rp=(eta*c-ct)/(eta*c+ct),rs=(c-eta*ct)/(c+eta*ct);return .5f*(rp*rp+rs*rs);}
RT_FN float conductorChannel(float c,float eta,float k){c=clamp(fabsf(c),0,1);float c2=c*c,s2=1-c2,e2=eta*eta,k2=k*k,t0=e2-k2-s2,a2b2=sqrtf(t0*t0+4*e2*k2),a=sqrtf(.5f*(a2b2+t0)),t1=a2b2+c2,t2=2*c*a;float rs=(t1-t2)/(t1+t2);float t3=c2*a2b2+s2*s2,t4=t2*s2;return .5f*rs*(1+(t3-t4)/(t3+t4));}
RT_FN Vec conductorFresnel(const Material& m,float c){if(m.conductorId==255){float f=powf(1-clamp(c,0,1),5);return m.baseColor+(V(1,1,1)-m.baseColor)*f;}return m.baseColor*V(conductorChannel(c,m.eta.x,m.k.x),conductorChannel(c,m.eta.y,m.k.y),conductorChannel(c,m.eta.z,m.k.z));}
RT_FN void setConductor(Material& m,unsigned id){
 // LabPBR predefined 230..237: iron, gold, aluminium, chrome, copper, lead, platinum, silver.
 const Vec eta[8]={V(2.9114f,2.9497f,2.5845f),V(.18299f,.42108f,1.3734f),V(1.3456f,.96521f,.61722f),V(3.1071f,3.1812f,2.323f),V(.27105f,.67693f,1.3164f),V(1.91f,1.83f,1.44f),V(2.3757f,2.0847f,1.8453f),V(.15943f,.14512f,.13547f)};
 const Vec k[8]={V(3.0893f,2.9318f,2.767f),V(3.4242f,2.3459f,1.7704f),V(7.4746f,6.3995f,5.3031f),V(3.3314f,3.3291f,3.135f),V(3.6092f,2.6248f,2.2921f),V(3.51f,3.4f,3.18f),V(4.2655f,3.7153f,3.1365f),V(3.9291f,3.19f,2.3808f)};
 m.conductorId=id;if(id>=230&&id<=237){m.eta=eta[id-230];m.k=k[id-230];}m.type=m.coatWeight>0?COATED_CONDUCTOR:CONDUCTOR;
}
RT_FN float D(Vec h,float ax,float ay){if(h.z<=0)return 0;float q=sq(h.x/ax)+sq(h.y/ay)+h.z*h.z;return 1/(Pi*ax*ay*q*q);}
RT_FN float lambda(Vec w,float ax,float ay){if(fabsf(w.z)<1e-7f)return 1e10f;return .5f*(sqrtf(1+(sq(ax*w.x)+sq(ay*w.y))/(w.z*w.z))-1);}
RT_FN float G1(Vec w,float ax,float ay){return 1/(1+lambda(w,ax,ay));}
RT_FN float G(Vec a,Vec b,float ax,float ay){return 1/(1+lambda(a,ax,ay)+lambda(b,ax,ay));}
RT_FN float normalPdf(Vec wo,Vec h,float ax,float ay){return D(h,ax,ay)*G1(wo,ax,ay)*fabsf(dot(wo,h))/fmaxf(1e-7f,fabsf(wo.z));}
RT_FN Vec sampleNormal(Vec wo,float ax,float ay,unsigned& s){Vec vh=normalize(V(ax*wo.x,ay*wo.y,wo.z));float lens=vh.x*vh.x+vh.y*vh.y;Vec t1=lens>1e-10f?V(-vh.y,vh.x,0)/sqrtf(lens):V(1,0,0),t2=cross(vh,t1);float r=sqrtf(rng(s)),phi=2*Pi*rng(s),x=r*cosf(phi),y=r*sinf(phi),blend=.5f*(1+vh.z);y=(1-blend)*sqrtf(fmaxf(0,1-x*x))+blend*y;Vec nh=t1*x+t2*y+vh*sqrtf(fmaxf(0,1-x*x-y*y));return normalize(V(ax*nh.x,ay*nh.y,fmaxf(0,nh.z)));}
RT_FN Vec cosine(unsigned& s){float r=sqrtf(rng(s)),a=2*Pi*rng(s);return V(r*cosf(a),r*sinf(a),sqrtf(fmaxf(0,1-r*r)));}
RT_FN bool dielectric(const Material& m){return m.type==DIELECTRIC||m.type==THIN_DIELECTRIC||m.type==WATER;}
RT_FN bool metal(const Material& m){return m.type==CONDUCTOR||m.type==COATED_CONDUCTOR;}
RT_FN float specProbability(const Material& m){return m.type==COATED_DIFFUSE?0:metal(m)?1:clamp(m.f0*2,.05f,.5f);}
} // namespace rt
#include "ggx_energy.h"
namespace rt {
RT_FN float multiscatterMix(const Material& m,float cosine){return m.multipleScattering?clamp(1-ggxEnergy(sqrtf(m.microfacetAlpha*m.alphaV),cosine),0,.9f):0;}
RT_HEAVY Vec multiscatterF(const Material& m,Vec wo,Vec wi){if(!m.multipleScattering||m.type==COATED_DIFFUSE||dielectric(m))return V(0,0,0);float alpha=sqrtf(m.microfacetAlpha*m.alphaV),eo=ggxEnergy(alpha,wo.z),ei=ggxEnergy(alpha,wi.z),average=ggxAverageEnergy(alpha);if(average>.99999f)return V(0,0,0);Vec F=V(0,0,0);
#ifdef __CUDACC__
#pragma unroll 1
#endif
for(int i=0;i<8;i++){float c=(i+.5f)/8;F=F+(metal(m)?conductorFresnel(m,c):V(1,1,1)*fresnelDielectric(c,(1+sqrtf(m.f0))/(1-sqrtf(m.f0))))*(2*c/8);}Vec factor=V(F.x*F.x*average/fmaxf(1e-6f,1-F.x*(1-average)),F.y*F.y*average/fmaxf(1e-6f,1-F.y*(1-average)),F.z*F.z*average/fmaxf(1e-6f,1-F.z*(1-average)));return factor*((1-eo)*(1-ei)/(Pi*fmaxf(1e-6f,1-average)));}
RT_HEAVY BsdfEval evalLocal(const Material& m,Vec wo,Vec wi,float eta=1.5f,int forced=0){
 BsdfEval result{V(0,0,0),0};if(wo.z<=0||fabsf(wi.z)<1e-7f)return result;
 float ax=fmaxf(.0005f,m.microfacetAlpha),ay=fmaxf(.0005f,m.alphaV);
 bool refl=wi.z>0;
 if(dielectric(m)){
  if((forced==1&&!refl)||(forced==2&&refl))return result;
  if(m.type==THIN_DIELECTRIC)return result; // discrete two-interface sheet, evaluated through sample only
  Vec h=normalize(wo+(refl?wi:wi*eta));if(h.z<0)h=h*-1;
  float oh=dot(wo,h),ih=dot(wi,h);if(oh*wo.z<=0||ih*wi.z<=0)return result;
  float F=fresnelDielectric(oh,eta),select=forced==1?1:forced==2?0:F;
  if(refl){result.f=V(1,1,1)*(D(h,ax,ay)*G(wo,wi,ax,ay)*F/(4*wo.z*wi.z));result.pdf=normalPdf(wo,h,ax,ay)/(4*fabsf(oh))*select;}
  else{float den=sq(ih+oh/eta);if(den<1e-15f)return result;result.f=V(1,1,1)*(D(h,ax,ay)*G(wo,wi,ax,ay)*(1-F)*fabsf(ih*oh/(den*wi.z*wo.z))/(eta*eta)*m.transmission);result.pdf=normalPdf(wo,h,ax,ay)*fabsf(ih)/den*(forced==2?1:1-select);}
  return result;
 }
 if(m.type==DIFFUSE_TRANSMISSION&&!refl){result.f=m.baseColor*(m.transmission/Pi);result.pdf=fabsf(wi.z)/Pi*m.transmission;return result;}
 if(!refl)return result;
 Vec h=normalize(wo+wi);float oh=fmaxf(0,dot(wo,h));float pdfSpec=normalPdf(wo,h,ax,ay)/fmaxf(1e-7f,4*oh);
 Vec F=metal(m)?conductorFresnel(m,oh):V(1,1,1)*fresnelDielectric(oh,sqrtf(fmaxf(0,m.f0))>=.999f?1000:(1+sqrtf(m.f0))/(1-sqrtf(m.f0)));
 Vec spec=F*(D(h,ax,ay)*G(wo,wi,ax,ay)/fmaxf(1e-7f,4*wo.z*wi.z));
 if(m.type==COATED_DIFFUSE)spec=V(0,0,0);
 float q=multiscatterMix(m,wo.z);spec=spec+multiscatterF(m,wo,wi);pdfSpec=pdfSpec*(1-q)+wi.z/Pi*q;
 float p=specProbability(m);float root=sqrtf(m.f0),baseIOR=root>=.999f?1000:(1+root)/(1-root);Vec diffuse=m.baseColor*((1-fresnelDielectric(wo.z,baseIOR))*(1-fresnelDielectric(wi.z,baseIOR))/Pi);
 if(m.type==COATED_DIFFUSE)diffuse=m.baseColor/Pi;
 if(m.type==ROUGH_DIFFUSE){float sigma=sqrtf(m.microfacetAlpha)*.5f,s2=sigma*sigma,A=1-.5f*s2/(s2+.33f),B=.45f*s2/(s2+.09f);float so=sqrtf(fmaxf(0,1-wo.z*wo.z)),si=sqrtf(fmaxf(0,1-wi.z*wi.z)),az=(wo.x*wi.x+wo.y*wi.y)/fmaxf(1e-7f,so*si);diffuse=diffuse*(A+B*fmaxf(0,az)*fmaxf(so,si)*fminf(so/fmaxf(wo.z,1e-5f),si/fmaxf(wi.z,1e-5f)));}
 if(m.type==DIFFUSE||m.type==EMISSIVE){result={m.baseColor/Pi,wi.z/Pi};}
 else if(m.type==DIFFUSE_TRANSMISSION){result={m.baseColor*((1-m.transmission)/Pi),wi.z/Pi*(1-m.transmission)};}
 else result={metal(m)?spec:diffuse+spec,p*pdfSpec+(1-p)*wi.z/Pi};
 if(m.coatWeight>0){float fcO=fresnelDielectric(wo.z,m.coatIOR),fcI=fresnelDielectric(wi.z,m.coatIOR),fc=fresnelDielectric(oh,m.coatIOR),ca=fmaxf(.0005f,m.coatAlpha),cp=m.coatWeight*.5f;Vec coat=V(1,1,1)*(fc*D(h,ca,ca)*G(wo,wi,ca,ca)/(4*wo.z*wi.z));float pdf=normalPdf(wo,h,ca,ca)/fmaxf(1e-7f,4*oh);result.f=result.f*(1-m.coatWeight+m.coatWeight*(1-fcO)*(1-fcI))+coat*m.coatWeight;result.pdf=result.pdf*(1-cp)+pdf*cp;}
 return result;
}
RT_FN BsdfEval evalBsdf(const Material& m,const Frame& f,Vec wo,Vec wi,float eta=1.5f,int forced=0){return evalLocal(m,f.local(wo),f.local(wi),eta,forced);}
RT_HEAVY BsdfSample sampleBsdf(const Material& m,const Frame& f,Vec woWorld,unsigned& seed,float eta=1.5f,int forced=0){
 Vec wo=f.local(woWorld),wi=V(0,0,0);BsdfSample s{V(0,0,0),V(0,0,0),0,1,0};if(wo.z<=0)return s;
 float ax=fmaxf(.0005f,m.microfacetAlpha),ay=fmaxf(.0005f,m.alphaV);unsigned flags=0;
 if(dielectric(m)&&m.type!=THIN_DIELECTRIC&&fabsf(eta-1)<1e-7f){if(forced==1)return s;s.wi=woWorld*-1;s.weight=V(1,1,1)*m.transmission;s.pdf=1;s.eta=1;s.flags=SPECULAR|DELTA|TRANSMISSION;return s;}
 if(m.type==THIN_DIELECTRIC){float R=fresnelDielectric(wo.z,eta);R=2*R/(1+R);bool refl=forced==1||forced!=2&&rng(seed)<R;float probability=forced?1:refl?R:1-R;s.wi=f.world(refl?V(-wo.x,-wo.y,wo.z):wo*-1);s.weight=V(1,1,1)*((refl?R:(1-R)*m.transmission)/fmaxf(probability,1e-7f));if(!refl)s.weight=s.weight*expNeg(m.sigmaA,m.thickness/fmaxf(.01f,wo.z));s.pdf=probability;s.flags=SPECULAR|DELTA|(refl?REFLECTION:TRANSMISSION);return s;}
 if(dielectric(m)){Vec h=sampleNormal(wo,ax,ay,seed);float F=fresnelDielectric(dot(wo,h),eta);bool refl=forced==1||forced!=2&&rng(seed)<F;if(refl){wi=h*(2*dot(wo,h))-wo;flags=GLOSSY|REFLECTION;if(wi.z<=0)return s;}else{float c=dot(wo,h),k=1-(1-c*c)/(eta*eta);if(k<=0)return s;wi=wo*(-1/eta)+h*(c/eta-sqrtf(k));flags=GLOSSY|TRANSMISSION;s.eta=eta;if(wi.z>=0)return s;}}
 else {float cp=m.coatWeight*.5f;float u=rng(seed);if(u<cp){Vec h=sampleNormal(wo,fmaxf(.0005f,m.coatAlpha),fmaxf(.0005f,m.coatAlpha),seed);wi=h*(2*dot(wo,h))-wo;flags=GLOSSY|REFLECTION;}
  else if(m.type==DIFFUSE_TRANSMISSION){wi=cosine(seed);if(rng(seed)<m.transmission){wi.z=-wi.z;flags=DIFFUSE_LOBE|TRANSMISSION;}else flags=DIFFUSE_LOBE|REFLECTION;}
  else if(m.type!=DIFFUSE&&m.type!=EMISSIVE&&rng(seed)<specProbability(m)){if(rng(seed)<multiscatterMix(m,wo.z))wi=cosine(seed);else{Vec h=sampleNormal(wo,ax,ay,seed);wi=h*(2*dot(wo,h))-wo;}flags=GLOSSY|REFLECTION;if(wi.z<=0)return s;}
  else {wi=cosine(seed);flags=DIFFUSE_LOBE|REFLECTION;}}
 auto e=evalLocal(m,wo,wi,eta,forced);if(e.pdf<=1e-12f)return s;s.wi=f.world(wi);s.pdf=e.pdf;s.weight=e.f*(fabsf(wi.z)/e.pdf);s.flags=flags;return s;
}

RT_HEAVY BsdfEval evalGlossy(const Material& m,const Frame& f,Vec woWorld,Vec wiWorld){
 if(m.type==DIFFUSE||m.type==EMISSIVE||m.type==DIFFUSE_TRANSMISSION)return {V(0,0,0),0};
 Vec wo=f.local(woWorld),wi=f.local(wiWorld);if(wo.z<=0||wi.z<=0)return {V(0,0,0),0};Vec h=normalize(wo+wi);float oh=fabsf(dot(wo,h)),ax=fmaxf(.0005f,m.microfacetAlpha),ay=fmaxf(.0005f,m.alphaV),ior=sqrtf(m.f0)>=.999f?1000:(1+sqrtf(m.f0))/(1-sqrtf(m.f0));
 Vec F=metal(m)?conductorFresnel(m,oh):V(1,1,1)*fresnelDielectric(oh,ior);Vec base=F*(D(h,ax,ay)*G(wo,wi,ax,ay)/(4*wo.z*wi.z));float pdf=normalPdf(wo,h,ax,ay)/fmaxf(1e-7f,4*oh);
 float q=multiscatterMix(m,wo.z);base=base+multiscatterF(m,wo,wi);pdf=pdf*(1-q)+wi.z/Pi*q;
 if(m.type==COATED_DIFFUSE){base=V(0,0,0);pdf=0;}
 float cp=m.type==COATED_DIFFUSE?1:m.coatWeight*.5f,ca=fmaxf(.0005f,m.coatAlpha);if(m.coatWeight>0){float attenuation=1-m.coatWeight+m.coatWeight*(1-fresnelDielectric(wo.z,m.coatIOR))*(1-fresnelDielectric(wi.z,m.coatIOR));Vec coat=V(1,1,1)*(fresnelDielectric(oh,m.coatIOR)*D(h,ca,ca)*G(wo,wi,ca,ca)/(4*wo.z*wi.z));base=base*attenuation+coat*m.coatWeight;pdf=pdf*(1-cp)+normalPdf(wo,h,ca,ca)/(4*oh)*cp;}
 return {base,pdf};
}
RT_HEAVY BsdfSample sampleGlossy(const Material& m,const Frame& f,Vec woWorld,unsigned& seed){
 Vec wo=f.local(woWorld);BsdfSample s{V(0,0,0),V(0,0,0),0,1,0};if(m.type==DIFFUSE||m.type==EMISSIVE||m.type==DIFFUSE_TRANSMISSION)return s;if(wo.z<=0)return s;bool coat=m.type==COATED_DIFFUSE||rng(seed)<m.coatWeight*.5f;float ax=fmaxf(.0005f,coat?m.coatAlpha:m.microfacetAlpha),ay=fmaxf(.0005f,coat?m.coatAlpha:m.alphaV);Vec wi;if(!coat&&rng(seed)<multiscatterMix(m,wo.z))wi=cosine(seed);else{Vec h=sampleNormal(wo,ax,ay,seed);wi=h*(2*dot(wo,h))-wo;}if(wi.z<=0)return s;s.wi=f.world(wi);auto e=evalGlossy(m,f,woWorld,s.wi);if(e.pdf<=0)return s;s.weight=e.f*(wi.z/e.pdf);s.pdf=e.pdf;s.flags=GLOSSY|REFLECTION;return s;
}
RT_FN float hg(float cosine,float g){float d=1+g*g-2*g*cosine;return (1-g*g)/(4*Pi*d*sqrtf(d));}
// Truncated exponential proposal for the finite homogeneous segment. RGB extinction
// remains in the integrand; the scalar majorant only chooses the sampling distribution.
struct MediumDistanceSample {float distance,pdf;};
RT_FN MediumDistanceSample sampleMediumDistance(Vec sigmaT,float length,unsigned& seed){
 float majorant=maxComponent(sigmaT),mass=-expm1f(-majorant*length);
 if(majorant<1e-6f||mass<1e-6f)return {rng(seed)*length,1/fmaxf(length,1e-8f)};
 float distance=-log1pf(-rng(seed)*mass)/majorant;
 return {distance,majorant*expf(-majorant*distance)/mass};
}
RT_FN Vec sampleHg(Vec axis,float g,unsigned& seed){float u=rng(seed),cosine;if(fabsf(g)<1e-3f)cosine=1-2*u;else{float ratio=(1-g*g)/(1-g+2*g*u);cosine=clamp((1+g*g-ratio*ratio)/(2*g),-1,1);}float phi=2*Pi*rng(seed),r=sqrtf(fmaxf(0,1-cosine*cosine));return Frame(axis).world(V(r*cosf(phi),r*sinf(phi),cosine));}
struct Medium {Vec sigmaA,sigmaS;float g,ior;unsigned id;};
struct MediumStack {Medium entries[8];int count=0;
 RT_METHOD float ior()const{return count?entries[count-1].ior:1;}
 RT_METHOD int find(unsigned id)const{for(int i=count-1;i>=0;i--)if(entries[i].id==id)return i;return -1;}
 RT_METHOD float outside(unsigned id)const{int i=find(id);return i>0?entries[i-1].ior:1;}
 RT_METHOD bool enter(Medium m){if(count>=8)return false;entries[count++]=m;return true;}
 RT_METHOD bool exit(unsigned id){int i=find(id);if(i<0)return false;for(int j=i;j<count-1;j++)entries[j]=entries[j+1];count--;return true;}
};
}
