#!/usr/bin/env python3
"""Build the NGX RR adapter against a pinned official SDK; package release RR runtime only."""
from pathlib import Path
import os, subprocess, shutil
ROOT=Path(__file__).resolve().parents[1]
CACHE=Path(os.environ.get('VOXELLIGHT_RT_DEPS','/tmp/voxellight-rt-deps'))
SDK=Path(os.environ.get('DLSS_SDK','/tmp/voxellight-dlss-sdk'))
VULKAN=Path(os.environ.get('VULKAN_HEADERS','/tmp/voxellight-vulkan-headers'))/'include'
JDK=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-25-openjdk-amd64'))/'include'
MINGW=Path(os.environ.get('MINGW_ROOT',str(CACHE/'llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64')))
OUT=ROOT/'build/dlss';WIN=ROOT/'build/optix-denoiser-jni'
PIN='374959484e79a640feaba44c93ac8cfb0a03f5b5'
if subprocess.check_output(['git','-C',str(SDK),'rev-parse','HEAD'],text=True).strip()!=PIN:
    raise SystemExit('DLSS SDK must be pinned to '+PIN)
for header in (SDK/'include/nvsdk_ngx_helpers_dlssd_vk.h',VULKAN/'vulkan/vulkan.h',JDK/'jni.h'):
    if not header.exists():raise SystemExit('Missing DLSS build dependency: '+str(header))
WIN.mkdir(parents=True,exist_ok=True)
(WIN/'jni_md.h').write_text('#define JNIEXPORT __declspec(dllexport)\n#define JNIIMPORT __declspec(dllimport)\n#define JNICALL\ntypedef int jint; typedef long long jlong; typedef signed char jbyte;\n')
MSVC=Path(os.environ.get('MSVC_SDK',str(ROOT/'build/msvc-sdk')))
linux=OUT/'linux-x86_64';linux.mkdir(parents=True,exist_ok=True)
subprocess.run(['c++','-std=c++17','-O2','-shared',str(ROOT/'native/dlss/bridge.cpp'),'-I'+str(JDK/'linux'),'-I'+str(JDK),'-I'+str(SDK/'include'),'-I'+str(VULKAN),str(SDK/'lib/Linux_x86_64/libnvsdk_ngx.a'),'-fPIC','-ldl','-lpthread','-o',str(linux/'libvoxellight_dlss.so')],check=True)
windows=OUT/'windows-x86_64';windows.mkdir(parents=True,exist_ok=True)
command=[str(MINGW/'bin/clang'),'--driver-mode=cl','--target=x86_64-pc-windows-msvc','-fuse-ld=lld','/nologo','/std:c++17','/O2','/MT','/EHsc','/LD',str(ROOT/'native/dlss/bridge.cpp'),'/Fo'+str(windows/'bridge.obj')]
for include in [WIN,JDK,SDK/'include',VULKAN]:command+=['/I'+str(include)]
for include in [MSVC/'crt/include',MSVC/'sdk/include/ucrt',MSVC/'sdk/include/um',MSVC/'sdk/include/shared']:command+=['/imsvc'+str(include)]
command+=['/link','advapi32.lib','user32.lib','/LIBPATH:'+str(MSVC/'crt/lib/x86_64'),'/LIBPATH:'+str(MSVC/'sdk/lib/ucrt/x86_64'),'/LIBPATH:'+str(MSVC/'sdk/lib/um/x86_64'),str(SDK/'lib/Windows_x86_64/x64/nvsdk_ngx_s.lib'),'/OUT:'+str(windows/'voxellight_dlss.dll'),'/IMPLIB:'+str(windows/'voxellight_dlss.lib')]
subprocess.run(command,check=True)
(windows/'bridge.obj').unlink();(windows/'voxellight_dlss.lib').unlink(missing_ok=True);(windows/'voxellight_dlss.exp').unlink(missing_ok=True)
shutil.copyfile(SDK/'lib/Linux_x86_64/rel/libnvidia-ngx-dlssd.so.310.9.1',linux/'libnvidia-ngx-dlssd.so.310.9.1')
shutil.copyfile(SDK/'lib/Windows_x86_64/rel/nvngx_dlssd.dll',windows/'nvngx_dlssd.dll')
shutil.copyfile(SDK/'LICENSE.txt',OUT/'NVIDIA-DLSS-LICENSE.txt')
(OUT/'SDK.txt').write_text('NVIDIA/DLSS 374959484e79a640feaba44c93ac8cfb0a03f5b5; RR 310.9.1\nThis software contains source code provided by NVIDIA Corporation.\n')

print("Built Windows/Linux Vulkan DLSS RR bridges and bundled release RR runtimes; frame generation absent.")
