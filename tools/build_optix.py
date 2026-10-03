#!/usr/bin/env python3
"""Build optional native binaries with externally installed SDKs. No SDK files are vendored."""
import argparse,ctypes as C,pathlib,subprocess,os,hashlib,json,shutil
p=argparse.ArgumentParser();p.add_argument('--deps',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];d=pathlib.Path(a.deps);out=r/'build/optix-native';out.mkdir(parents=True,exist_ok=True)
optix=next(d.glob('optix-dev-*/include'));cuda=d/'cuda/nvidia/cuda_runtime/include';nvlib=next((d/'cuda/nvidia/cuda_nvrtc/lib').glob('libnvrtc.so*'))
nv=C.CDLL(str(nvlib));prog=C.c_void_p();source=(r/'native/optix/pathtrace.cu').read_bytes()
assert nv.nvrtcCreateProgram(C.byref(prog),source,b'pathtrace.cu',0,None,None)==0
opts=(C.c_char_p*2)(b'--gpu-architecture=compute_75',b'--std=c++17');result=nv.nvrtcCompileProgram(prog,2,opts)
n=C.c_size_t();nv.nvrtcGetProgramLogSize(prog,C.byref(n));log=C.create_string_buffer(n.value);nv.nvrtcGetProgramLog(prog,log);print(log.value.decode())
if result:raise SystemExit('NVRTC failed')
nv.nvrtcGetPTXSize(prog,C.byref(n));ptx=C.create_string_buffer(n.value);nv.nvrtcGetPTX(prog,ptx);(out/'pathtrace.ptx').write_bytes(ptx.raw);nv.nvrtcDestroyProgram(C.byref(prog))
jdk=pathlib.Path('/usr/lib/jvm/java-25-openjdk-amd64');args=['-std=c++17','-O2','-shared','-I'+str(optix),'-I'+str(cuda),'-I'+str(jdk/'include'),'-I'+str(r/'native/optix/include'),str(r/'native/optix/bridge.cpp')]
subprocess.run(['g++','-fPIC',*args,'-ldl','-o',str(out/'libvoxellight_optix.so')],check=True)
mingw=next(d.glob('llvm-mingw-*/bin/x86_64-w64-mingw32-g++'));subprocess.run([str(mingw),*args,'-static-libstdc++','-static-libgcc','-lcfgmgr32','-ladvapi32','-o',str(out/'voxellight_optix.dll')],check=True)

licenseDir=out/'licenses';licenseDir.mkdir(exist_ok=True)
shutil.copy(optix.parent/'LICENSE.txt',licenseDir/'NVIDIA-OptiX-SDK.txt')
shutil.copy(next(d.glob('llvm-mingw-*/LICENSE.TXT')),licenseDir/'LLVM-runtime.txt')
shutil.copy(next((d/'cuda').glob('nvidia_cuda_runtime_cu12-*.dist-info/licenses/License.txt')),licenseDir/'NVIDIA-CUDA.txt')
for name in ['optix_stubs.h','optix_function_table_definition.h']:
    text=(optix/name).read_text();(licenseDir/(name+'.txt')).write_text(text[:text.index('*/')+2]+'\n')
sources=['native/optix/pathtrace.cu','native/optix/bridge.cpp','native/optix/include/jni_md.h','tools/build_optix.py']
(out/'build.json').write_text(json.dumps({'optix':'9.1.0','cuda':'12.9','sources':{name:hashlib.sha256((r/name).read_bytes()).hexdigest() for name in sources}},indent=2)+'\n')
