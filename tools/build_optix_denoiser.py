#!/usr/bin/env python3
"""Build the denoiser-only host bridge; no nvcc, PTX compiler or tracing payload."""
from pathlib import Path
import os, subprocess, shutil
ROOT=Path(__file__).resolve().parents[1]
CACHE=Path(os.environ.get('VOXELLIGHT_RT_DEPS','/tmp/voxellight-rt-deps'))
OPTIX=Path(os.environ.get('OPTIX_INCLUDE',str(CACHE/'optix-dev-f1f6dd803f3159992d248178f6e09421c6eb8b6d/include')))
CUDA=Path(os.environ.get('CUDA_INCLUDE',str(CACHE/'cuda/nvidia/cuda_runtime/include')))
JDK=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-25-openjdk-amd64'))/'include'
MINGW=Path(os.environ.get('MINGW_ROOT',str(CACHE/'llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64')))
for header in (OPTIX/'optix.h',CUDA/'cuda.h',JDK/'jni.h'):
    if not header.exists(): raise SystemExit(f'Missing denoiser build header: {header}. Set OPTIX_INCLUDE, CUDA_INCLUDE and JAVA_HOME (see docs/VULKAN-RT-MIGRATION.md).')
OUT=ROOT/'build/optix-denoiser'
WIN=ROOT/'build/optix-denoiser-jni'
WIN.mkdir(parents=True,exist_ok=True)
(WIN/'jni_md.h').write_text('#define JNIEXPORT __declspec(dllexport)\n#define JNIIMPORT __declspec(dllimport)\n#define JNICALL\ntypedef int jint; typedef long long jlong; typedef signed char jbyte;\n')
for platform,compiler,extra,name,platform_headers in [
 ('linux-x86_64',os.environ.get('CXX','c++'),['-fPIC','-ldl'],'libvoxellight_denoiser.so',JDK/'linux'),
 ('windows-x86_64',str(MINGW/'bin/x86_64-w64-mingw32-clang++'),['-static','-static-libstdc++','-lcfgmgr32','-ladvapi32'],'voxellight_denoiser.dll',WIN)]:
    if not shutil.which(compiler): raise SystemExit(f'Missing denoiser compiler: {compiler}')
    directory=OUT/platform;directory.mkdir(parents=True,exist_ok=True)
    command=[compiler,'-std=c++17','-O2','-shared',str(ROOT/'native/denoiser/bridge.cpp'),'-I'+str(platform_headers),'-I'+str(JDK),'-I'+str(CUDA),'-I'+str(OPTIX),*extra,'-o',str(directory/name)]
    subprocess.run(command,check=True)
shutil.copyfile(OPTIX.parent/'LICENSE.txt',OUT/'NVIDIA-OptiX-LICENSE.txt')
shutil.copyfile(MINGW/'LICENSE.TXT',OUT/'LLVM-runtime-LICENSE.txt')
print('Built Windows/Linux OptiX temporal AOV denoiser helpers (tracing absent).')
