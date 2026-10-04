#include "../task_pool.h"
#include <atomic>
#include <cassert>
#include <stdexcept>
int main(){
 std::atomic<int> visited[32]{};rt::compileTasks(1,4,[&](int node){visited[node]++;std::vector<int> children;if(node<16)children={node*2,node*2+1};return children;});
 for(int i=1;i<32;i++)assert(visited[i]==1);
 std::atomic<int> active{0};bool failed=false;
 try{rt::compileTasks(1,4,[&](int node){if(node==1)return std::vector<int>{2,3,4,5};if(node==2){while(active.load()==0)std::this_thread::yield();throw std::runtime_error("compiler failed");}active++;std::this_thread::sleep_for(std::chrono::milliseconds(5));active--;return std::vector<int>{};});}catch(const std::runtime_error&){failed=true;}
 assert(failed&&active==0);int calls=0;rt::compileTasks(0,4,[&](int){calls++;return std::vector<int>{};});assert(calls==0);
}
