#!/usr/bin/env python3
"""Execute Slang's real CPU target against the canonical native BSDF, not a Python reimplementation."""
import hashlib,json,pathlib,shutil,subprocess
from build_vulkan_rt import ROOT,tool

def main():
    output=ROOT/'build/vulkan-transport-parity';output.mkdir(parents=True,exist_ok=True)
    provenance=json.loads((ROOT/'shaders/rt/common/transport-port.json').read_text())
    for source,expected in provenance.items():
        if hashlib.sha256((ROOT/source).read_bytes()).hexdigest()!=expected:
            raise SystemExit(f'{source} changed; reconcile the Slang transport port and rerun numerical parity')
    compiler=tool('slangc','SLANGC');cxx=shutil.which('c++')
    if not cxx:raise SystemExit('C++ compiler required for native/Slang numerical parity')
    source=ROOT/'shaders/rt/tests/transport_parity.slang';spv=output/'transport_parity.spv'
    subprocess.run([compiler,str(source),'-target','spirv','-profile','spirv_1_5','-entry','transport_parity','-stage','compute','-o',str(spv)],check=True)
    subprocess.run([tool('spirv-val','SPIRV_VAL'),'--target-env','vulkan1.2',str(spv)],check=True)
    subprocess.run([compiler,str(source),'-target','cpp','-entry','transport_parity','-stage','compute','-o',str(output/'transport_parity.cpp')],check=True)
    executable=output/'transport_parity'
    subprocess.run([cxx,'-std=c++17','-O2','-I'+str(output),str(ROOT/'tools/native/vulkan_transport_parity.cpp'),'-o',str(executable)],check=True)
    result=subprocess.check_output([str(executable)],text=True)
    (output/'water_fixture_native.h').write_text((ROOT/'native/rt/water_surface.h').read_text())
    source=ROOT/'shaders/rt/tests/terrain_material_parity.slang'
    spv=output/'terrain_material_parity.spv'
    subprocess.run([compiler,str(source),'-target','spirv','-profile','spirv_1_5','-entry','terrain_material_parity','-stage','compute','-o',str(spv)],check=True)
    subprocess.run([tool('spirv-val','SPIRV_VAL'),'--target-env','vulkan1.2',str(spv)],check=True)
    subprocess.run([compiler,str(source),'-target','cpp','-entry','terrain_material_parity','-stage','compute','-o',str(output/'terrain_material_parity.cpp')],check=True)
    executable=output/'terrain_material_parity'
    subprocess.run([cxx,'-std=c++17','-O2','-I'+str(output),str(ROOT/'tools/native/vulkan_terrain_material_parity.cpp'),'-o',str(executable)],check=True)
    result+=subprocess.check_output([str(executable)],text=True)
    source=ROOT/'shaders/rt/tests/environment_parity.slang';spv=output/'environment_parity.spv'
    subprocess.run([compiler,str(source),'-target','spirv','-profile','spirv_1_5','-entry','environment_parity','-stage','compute','-o',str(spv)],check=True)
    subprocess.run([tool('spirv-val','SPIRV_VAL'),'--target-env','vulkan1.2',str(spv)],check=True)
    subprocess.run([compiler,str(source),'-target','cpp','-entry','environment_parity','-stage','compute','-o',str(output/'environment_parity.cpp')],check=True)
    executable=output/'environment_parity'
    subprocess.run([cxx,'-std=c++17','-O2','-I'+str(output),str(ROOT/'tools/native/vulkan_environment_parity.cpp'),'-o',str(executable)],check=True)
    result+=subprocess.check_output([str(executable)],text=True)
    # Execute the exact GLSL mean function through Slang's CPU target.
    glsl=(ROOT/'src/main/resources/assets/voxellight/shaders/vulkan_rt_accumulate.fsh').read_text()
    functions=glsl[glsl.index('bool finiteRgb'):glsl.index('void main()')].replace('vec3','float3').replace('vec4','float4')
    (output/'accumulation_fixture.slang').write_text(functions)
    source=ROOT/'shaders/rt/tests/numerical_edges.slang'
    subprocess.run([compiler,str(source),'-I',str(output),'-target','cpp','-entry','numerical_edges','-stage','compute','-o',str(output/'numerical_edges.cpp')],check=True)
    executable=output/'numerical_edges'
    subprocess.run([cxx,'-std=c++17','-O2','-I'+str(output),str(ROOT/'tools/native/vulkan_numerical_edges.cpp'),'-o',str(executable)],check=True)
    result+=subprocess.check_output([str(executable)],text=True)
    source=ROOT/'shaders/rt/tests/emitter_sampling.slang'
    subprocess.run([compiler,str(source),'-target','cpp','-entry','emitter_sampling','-stage','compute','-o',str(output/'emitter_sampling.cpp')],check=True)
    executable=output/'emitter_sampling'
    subprocess.run([cxx,'-std=c++17','-O2','-I'+str(output),str(ROOT/'tools/native/vulkan_emitter_sampling.cpp'),'-o',str(executable)],check=True)
    result+=subprocess.check_output([str(executable)],text=True)
    (output/'result.txt').write_text(result);print(result,end='')

if __name__=='__main__':main()
