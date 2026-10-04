#pragma once
#include <condition_variable>
#include <deque>
#include <exception>
#include <mutex>
#include <thread>
#include <vector>

namespace rt {
// Tasks may return dependent tasks. Join every running task before propagating failure;
// the caller owns the module, input bytes and compiler log until this function returns.
template<class Task,class Execute>
void compileTasks(Task first,unsigned workers,Execute execute){
 if(!first)return;
 std::deque<Task> queue{first};std::mutex mutex;std::condition_variable wake;
 size_t outstanding=1;std::exception_ptr failure;
 auto run=[&]{for(;;){Task task;
  {std::unique_lock<std::mutex> lock(mutex);wake.wait(lock,[&]{return failure||!queue.empty()||outstanding==0;});if(failure||outstanding==0)return;task=queue.front();queue.pop_front();}
  std::vector<Task> children;
  try{children=execute(task);}catch(...){std::lock_guard<std::mutex> lock(mutex);if(!failure)failure=std::current_exception();wake.notify_all();return;}
  {std::lock_guard<std::mutex> lock(mutex);outstanding=outstanding-1+children.size();for(auto child:children)queue.push_back(child);wake.notify_all();}
 }};
 std::vector<std::thread> threads;
 try{for(unsigned i=0;i<(workers?workers:1);i++)threads.emplace_back(run);}catch(...){std::lock_guard<std::mutex> lock(mutex);failure=std::current_exception();wake.notify_all();}
 for(auto& thread:threads)thread.join();
 if(failure)std::rethrow_exception(failure);
}
}
