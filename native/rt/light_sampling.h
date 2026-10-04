#pragma once
namespace rt {
struct LightCluster {unsigned begin,end;float cdf;};
RT_FN unsigned lightClusterIndex(const LightCluster* clusters,unsigned count,float power){unsigned lo=0,hi=count-1;while(lo<hi){unsigned mid=(lo+hi)/2;if(clusters[mid].cdf<=power)lo=mid+1;else hi=mid;}return lo;}
template<class Light> RT_FN int emitterIdentityIndex(const Light* lights,int count,unsigned identity){int lo=0,hi=count;while(lo<hi){int mid=(lo+hi)/2;if(lights[mid].identity<identity)lo=mid+1;else hi=mid;}return lo<count&&lights[lo].identity==identity?lo:-1;}
}
