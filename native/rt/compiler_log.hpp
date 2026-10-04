#pragma once
#include <cstdarg>
#include <cstdio>
#include <deque>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
// Native compiler workers never call Java or touch renderer resources. A bounded
// queue is drained by Java, while a flushed file preserves diagnostics across a crash.
static std::mutex rtLogMutex;
static std::deque<std::string> rtLogQueue;
static size_t rtLogBytes=0,rtLogDropped=0;
static std::ofstream rtLogFile;
static void rtLog(const char* format,...){
 va_list args;va_start(args,format);va_list copy;va_copy(copy,args);int size=vsnprintf(nullptr,0,format,copy);va_end(copy);if(size<0){va_end(args);return;}
 size_t length=std::min(size_t(size),size_t(1024*1024));std::vector<char> buffer(length+1);vsnprintf(buffer.data(),buffer.size(),format,args);va_end(args);std::string line(buffer.data(),length);
 std::lock_guard<std::mutex> guard(rtLogMutex);fwrite(line.data(),1,line.size(),stderr);fflush(stderr);
 if(rtLogFile){rtLogFile.write(line.data(),line.size());rtLogFile.flush();}
 while(!rtLogQueue.empty()&&rtLogBytes+line.size()>1024*1024){rtLogBytes-=rtLogQueue.front().size();rtLogQueue.pop_front();rtLogDropped++;}
 rtLogBytes+=line.size();rtLogQueue.push_back(std::move(line));
}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_configureDiagnostics(JNIEnv* e,jclass,jstring path){
 const char* value=e->GetStringUTFChars(path,nullptr);std::string file(value);e->ReleaseStringUTFChars(path,value);
 bool opened;{std::lock_guard<std::mutex> guard(rtLogMutex);rtLogFile.open(file,std::ios::app|std::ios::binary);opened=bool(rtLogFile);}
 rtLog("VoxelLight compiler diagnostic session file=%s opened=%d\n",file.c_str(),int(opened));
}
EXPORT jstring JNICALL Java_com_voxellight_nvidia_OptixNative_drainDiagnostics(JNIEnv* e,jclass){
 std::string lines;{std::lock_guard<std::mutex> guard(rtLogMutex);if(rtLogDropped){lines="VoxelLight compiler queue dropped "+std::to_string(rtLogDropped)+" records; see dedicated diagnostic file\n";rtLogDropped=0;}while(!rtLogQueue.empty()){lines+=rtLogQueue.front();rtLogQueue.pop_front();}rtLogBytes=0;}
 return e->NewStringUTF(lines.c_str());
}
