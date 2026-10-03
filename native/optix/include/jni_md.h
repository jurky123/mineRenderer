#pragma once
#ifdef _WIN32
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL
#else
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL
#endif
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
