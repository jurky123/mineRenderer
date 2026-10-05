#pragma once
// Ported from native/rt/bsdf.h; preserve Material 3 transport semantics.
float log1p(float x) { return abs(x)<1e-3 ? x*(1-x*(.5-x/3)) : log(1+x); }
float expm1(float x) { return abs(x)<1e-3 ? x*(1+x*(.5+x/6)) : exp(x)-1; }
static const float Pi=3.14159265358979323846f;


float sq(float x){return x*x;}
float maxComponent(float3 a){return max(a.x,max(a.y,a.z));}
float3 expNeg(float3 a,float d){return float3(exp(-a.x*d),exp(-a.y*d),exp(-a.z*d));}
float rng(inout uint s){s=s*1664525u+1013904223u;return (s>>8)*(1.0/16777216.0);}
float powerHeuristic(float a,float b){return a>0?sq(a)/(sq(a)+sq(b)):0;}

// Native normalize clamps its squared length before division, including degenerate vectors.
float3 safeNormalize(float3 v) { return v/sqrt(max(1e-20,dot(v,v))); }
