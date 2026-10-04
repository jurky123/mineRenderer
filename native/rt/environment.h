#pragma once
// A piecewise-constant lat-long proposal. Each texel is sampled uniformly in solid angle.
namespace rt {
constexpr int EnvironmentWidth=256,EnvironmentHeight=128;
RT_FN float environmentSolidAngle(int row){return 2*Pi/EnvironmentWidth*(cosf(Pi*row/EnvironmentHeight)-cosf(Pi*(row+1)/EnvironmentHeight));}
RT_FN int cdfIndex(const float* cdf,int size,float value){int lo=0,hi=size-1;while(lo<hi){int mid=(lo+hi)/2;if(cdf[mid]<=value)lo=mid+1;else hi=mid;}return lo;}
RT_FN int environmentCell(Vec d){int row=(int)(acosf(clamp(d.y,-1,1))*EnvironmentHeight/Pi);float phi=atan2f(d.z,d.x);if(phi<0)phi+=2*Pi;int column=(int)(phi*EnvironmentWidth/(2*Pi));return (int)clamp(row,0,EnvironmentHeight-1)*EnvironmentWidth+(int)clamp(column,0,EnvironmentWidth-1);}
RT_FN float environmentDensity(const float* cdf,Vec d){int cell=environmentCell(d),row=cell/EnvironmentWidth,column=cell%EnvironmentWidth;float total=cdf[EnvironmentWidth*EnvironmentHeight+EnvironmentHeight-1];if(total<=0)return 1/(4*Pi);float mass=cdf[cell]-(column?cdf[cell-1]:0);return mass/(total*environmentSolidAngle(row));}
RT_FN Vec sampleEnvironment(const float* cdf,unsigned& seed){const float* rows=cdf+EnvironmentWidth*EnvironmentHeight;float total=rows[EnvironmentHeight-1];if(total<=0){float z=1-2*rng(seed),a=2*Pi*rng(seed),r=sqrtf(fmaxf(0,1-z*z));return V(r*cosf(a),z,r*sinf(a));}int row=cdfIndex(rows,EnvironmentHeight,rng(seed)*total);const float* cells=cdf+row*EnvironmentWidth;int column=cdfIndex(cells,EnvironmentWidth,rng(seed)*cells[EnvironmentWidth-1]);float z=cosf(Pi*row/EnvironmentHeight)+(cosf(Pi*(row+1)/EnvironmentHeight)-cosf(Pi*row/EnvironmentHeight))*rng(seed),phi=2*Pi*(column+rng(seed))/EnvironmentWidth,r=sqrtf(fmaxf(0,1-z*z));return V(r*cosf(phi),z,r*sinf(phi));}
}
