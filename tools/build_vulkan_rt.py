#!/usr/bin/env python3
"""Build-only Slang/SPIR-V compiler. No runtime compiler or source shader fallback."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, struct
ROOT = pathlib.Path(__file__).resolve().parents[1]
STAGES = {'primary': 'raygeneration', 'closest_hit': 'closesthit', 'sky': 'miss', 'transport_primary': 'raygeneration', 'transport_indirect': 'raygeneration', 'transport_closest_hit': 'closesthit', 'transport_sky': 'miss', 'material_primary':'raygeneration', 'material_indirect':'raygeneration', 'material_closest_hit':'closesthit', 'material_sky':'miss', 'material_cutout':'anyhit','material_shadow_cutout':'anyhit','material_resolve':'raygeneration','transport_resolve':'raygeneration', 'material_visibility_miss':'miss','material_shadow_transmission':'anyhit','material_transmission_miss':'miss'}
VARIANTS={name+suffix:(name,defines) for suffix,defines in [("_query",["RT_RAY_QUERY"]),("_ser",["RT_SER"]),("_query_ser",["RT_RAY_QUERY","RT_SER"])] for name in ["material_primary","material_indirect"]}
VARIANTS.update(material_visibility_trace=('material_visibility_benchmark',['RT_VIS_BENCH']),material_visibility_query=('material_visibility_benchmark',['RT_VIS_BENCH','RT_RAY_QUERY']))
# Production compile-time FULL/Realtime and direct-light specialization; the old
# runtime family is retained exclusively for the footprint A/B experiment.
for family,policy in [("full",["RT_CLEAN_FULL"]),("realtime",[])]:
    for direct,lighting in [("",["RT_DIRECT_RIS"]),("_legacy",["RT_DIRECT_LEGACY"])]:
        for suffix,features in [("",[]),("_query",["RT_RAY_QUERY"]),("_ser",["RT_SER"]),("_query_ser",["RT_RAY_QUERY","RT_SER"])]:
            for stage in ["material_primary","material_indirect"]:
                VARIANTS[stage+"_"+family+direct+suffix]=(stage,policy+lighting+features)
    VARIANTS["material_resolve_"+family]=("material_resolve",policy+["RT_DIRECT_RIS"])
# A single raygen keeps B1..B5 state local; the estimator is unchanged.
for name,(base,defines) in list(VARIANTS.items()):
    if base=='material_indirect':VARIANTS[name+'_iterative']=(base,defines+['RT_ITERATIVE'])
VARIANTS['material_indirect_iterative']=('material_indirect',['RT_ITERATIVE'])
VARIANTS['material_primary_visibility_full']=('material_primary_visibility',['RT_CLEAN_FULL','RT_DIRECT_RIS'])
VARIANTS['material_primary_visibility_realtime']=('material_primary_visibility',['RT_DIRECT_RIS'])
VARIANTS['material_cache_train']=('material_cache_train',['RT_DIRECT_RIS'])
for name,(base,defines) in list(VARIANTS.items()):
    if base=='material_primary':VARIANTS[name.replace('material_primary','material_primary_shade')]=('material_primary_shade',defines)
VARIANTS['material_primary_shade']=('material_primary_shade',[])
VARIANTS['material_primary_simple']=('material_primary',['RT_CLEAN_FULL','RT_DIRECT_RIS','RT_SIMPLE_MATERIAL'])
VARIANTS['material_indirect_simple']=('material_indirect',['RT_CLEAN_FULL','RT_DIRECT_RIS','RT_SIMPLE_MATERIAL'])
VARIANTS['material_primary_visibility_compact']=('material_primary_visibility',['RT_CLEAN_FULL','RT_DIRECT_RIS','RT_COMPACT48'])
VARIANTS['material_primary_shade_compact']=('material_primary_shade',['RT_CLEAN_FULL','RT_DIRECT_RIS','RT_COMPACT48','RT_TWO_PASS'])
VARIANTS['material_resolve_compact']=('material_resolve',['RT_CLEAN_FULL','RT_DIRECT_RIS','RT_COMPACT48'])
VARIANTS['material_guides']=('material_guides',['RT_CLEAN_FULL','RT_DIRECT_RIS'])
STAGES.update({name:'raygeneration' for name in VARIANTS})
def tool(name, variable):
    found = os.environ.get(variable) or shutil.which(name)
    if not found and name == 'slangc':
        cached = pathlib.Path.home() / '.cache/voxellight/tools/slang-2026.19/bin/slangc'
        if cached.is_file(): found = str(cached)
    if not found:
        raise SystemExit(f'{name} required at build time; set {variable}. See docs/VULKAN-RT-MIGRATION.md')
    return found

def validate_byte_address_layout(binary):
    """Reject padded vec3 runtime aliases that corrupt ByteAddressBuffer.Load3 offsets."""
    words=struct.unpack('<'+'I'*(len(binary)//4),binary)
    vectors={};arrays={};strides={};i=5
    while i<len(words):
        count=words[i]>>16;opcode=words[i]&65535;args=words[i+1:i+count]
        if opcode==23:vectors[args[0]]=args[2] # OpTypeVector
        elif opcode==29:arrays[args[0]]=args[1] # OpTypeRuntimeArray
        elif opcode==71 and args[1]==6:strides[args[0]]=args[2] # ArrayStride
        if count==0:raise ValueError('invalid SPIR-V instruction')
        i+=count
    for array,element in arrays.items():
        if vectors.get(element)==3 and strides.get(array)!=12:
            raise ValueError('padded vec3 ByteAddressBuffer alias: 12-byte indexing with '+str(strides.get(array))+'-byte ArrayStride; use scalar word loads')

def validate_kernel_storage(binary):
    """The rough-diffuse LUT must live in the persistent assets buffer, not function arrays."""
    words=struct.unpack('<'+'I'*(len(binary)//4),binary);constants={};arrays=[];i=5
    while i<len(words):
        count=words[i]>>16;opcode=words[i]&65535;args=words[i+1:i+count]
        if opcode==43 and len(args)==3:constants[args[1]]=args[2]
        elif opcode==28:arrays.append(args[2])
        if count==0:raise ValueError('invalid SPIR-V instruction')
        i+=count
    if any(constants.get(length) in (2145,6435) for length in arrays):
        raise ValueError('preintegrated diffuse LUT compiled as a per-invocation array; use asset-buffer loads')

def validate_layout(reflection, transport=False, material=False, compact=False):
    parameters = {p['name']: p for p in reflection['parameters']}
    for name, index in {'world': 0, 'output': 1, 'normals': 2, 'camera': 3}.items():
        if parameters[name]['binding']['index'] != index:
            raise ValueError(f'descriptor ABI mismatch: {name}')
    if transport and parameters['paths']['binding']['index'] != 4:
        raise ValueError('continuation descriptor ABI mismatch')
    if material:
        for name,index in dict(geometry=5,assets=6).items():
            if parameters[name]['binding']['index']!=index: raise ValueError('material descriptor ABI mismatch: '+name)
    camera = parameters['camera']['type']['elementType']
    offsets = {f['name']: f['binding']['offset'] for f in camera['fields']}
    expected = {'inverseClip0': 0, 'inverseClip1': 16, 'inverseClip2': 32, 'inverseClip3': 48, 'origin': 64, 'width': 80, 'height': 84, 'padding': 88}
    if transport:
        expected.pop('padding'); expected.update(frame=88, padding=92)
        path = parameters['paths']['type']['resultType']
        expected_path=dict(origin=0,direction=16,throughput=32,radiance=48)
        if material:
            expected_path=dict(origin=0,direction=16,throughput=32,state=48,etaScale=52,seed=56,reserved=60)
            if compact:expected_path=dict(origin=0,direction=12,pdf=28,throughput=16,state=32,etaScale=36,seed=40,reserved=44)
            aov=parameters['pathAovs']['type']['resultType']
            if parameters['pathAovs']['binding']['index']!=16 or aov['sizes'][0]['value']!=48:raise ValueError('AOV accumulation ABI mismatch')
            medium=parameters['pathMedia']['type']['resultType']
            if parameters['pathMedia']['binding']['index']!=15 or medium['sizes'][0]['value']!=288 or {f['name']:f['binding']['offset'] for f in medium['fields']}!=dict(absorptionIor=0,scatteringPhase=128,mediumIds=256):raise ValueError('medium cold ABI mismatch')
        if path['sizes'][0]['value'] != (48 if compact else 64) or {f['name']: f['binding']['offset'] for f in path['fields']} != expected_path:
            raise ValueError('continuation ABI mismatch')
    if offsets != expected:
        raise ValueError(f'camera ABI mismatch: {offsets}')
    if camera['sizes'][0]['value'] != 96:
        raise ValueError('camera ABI size mismatch')

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--output', default='build/vulkan-rt'); args = parser.parse_args()
    output = ROOT / args.output; output.mkdir(parents=True, exist_ok=True)
    compiler, validator = tool('slangc', 'SLANGC'), tool('spirv-val', 'SPIRV_VAL')
    version = subprocess.check_output([compiler, '-version'], stderr=subprocess.STDOUT, text=True).strip()
    manifest = {'compiler': version, 'target': 'vulkan1.2', 'shaders': {}, 'sources': {}}
    for source in sorted((ROOT/'shaders/rt').rglob('*.slang')):
        manifest['sources'][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    for entry, stage in STAGES.items():
        base,defines=VARIANTS.get(entry,(entry,[]))
        source = ROOT / f'shaders/rt/world/{base}.slang'; spv = output / f'{entry}.spv'; reflection = output / f'{entry}.json'
        subprocess.run([compiler, str(source), '-target', 'spirv', '-profile', 'spirv_1_5', '-entry', base, *['-D'+define+'=1' for define in defines],
                        *(['-capability','spvShaderInvocationReorderNV'] if 'RT_SER' in defines else []), *(['-capability','spvRayQueryKHR'] if 'RT_RAY_QUERY' in defines else []), '-stage', stage, '-matrix-layout-column-major', '-O2', '-o', str(spv), '-reflection-json', str(reflection)], check=True)
        subprocess.run([validator, '--target-env', 'vulkan1.2', str(spv)], check=True)
        validate_byte_address_layout(spv.read_bytes())
        validate_kernel_storage(spv.read_bytes())
        if entry != "material_visibility_miss":validate_layout(json.loads(reflection.read_text()), (entry.startswith("transport_") or entry.startswith("material_")), entry.startswith("material_"), "RT_COMPACT48" in defines)
        manifest['shaders'][entry] = {'stage': stage, 'defines':defines, 'sha256': hashlib.sha256(spv.read_bytes()).hexdigest(), 'bytes': spv.stat().st_size}
    (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(f'Validated {len(STAGES)} Vulkan RT stages, camera ABI=96 bytes, continuation ABIs=64 bytes + 288-byte cold media + 48-byte AOV accumulator')
if __name__ == '__main__': main()
