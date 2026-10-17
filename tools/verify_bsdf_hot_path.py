#!/usr/bin/env python3
"""Focused BSDF regression against native transport and a frozen local baseline.

The optional three paired CPU fixture timings are diagnostic, not GPU/FPS data.
"""
import argparse
import array
import hashlib
import json
import math
import pathlib
import statistics
import subprocess

from build_vulkan_rt import ROOT, VARIANTS, tool, validate_layout, validate_byte_address_layout, validate_kernel_storage


def run(args):
    return subprocess.check_output([str(a) for a in args], text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline-dir', required=True, type=pathlib.Path)
    parser.add_argument('--output', default='build/bsdf-hot-path', type=pathlib.Path)
    parser.add_argument('--cpu-timing', action='store_true')
    parser.add_argument('--skip-production-stages', action='store_true', help='Use only when the full production bank is validated separately')
    parser.add_argument('--self-tolerance', type=float, default=0, help='Maximum normalized float32 difference; default requires bitwise equality')
    args = parser.parse_args()
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)
    compiler, validator, cxx = tool('slangc', 'SLANGC'), tool('spirv-val', 'SPIRV_VAL'), tool('c++', 'CXX')
    baseline = args.baseline_dir.resolve()
    report = {'scope': 'BSDF native parity + float32 self comparison', 'checks': [], 'timingScope': 'CPU numerical fixture only; not GPU performance'}
    harness = (ROOT/'tools/native/vulkan_transport_parity.cpp').read_text()
    harness = harness.replace('int main() {', 'int main(int argc,char** argv) {')
    anchor = 'float largest=0;unsigned compared=0;'
    harness = '#include <chrono>\n' + harness.replace(anchor, '''
 if(argc>1 && std::strcmp(argv[1],"--snapshot")==0){
  FILE* file=std::fopen(argv[2],"wb");if(!file)return 20;
  auto count=std::fwrite(actual.data(),sizeof(actual[0]),actual.size(),file);
  std::fclose(file);return count==actual.size()?0:21;
 }
 if(argc>1 && std::strcmp(argv[1],"--bench")==0){
  varying.endGroupID={900,1,1};volatile float checksum=0;
  auto start=std::chrono::steady_clock::now();
  for(unsigned i=0;i<128;i++){transport_parity(&varying,nullptr,&globals);checksum+=actual[i%900*14].x;}
  auto stop=std::chrono::steady_clock::now();
  std::printf("%g\\n",std::chrono::duration<double,std::milli>(stop-start).count());
  return std::isfinite(checksum)?0:22;
 }
 ''' + anchor)
    # Absolute native includes preserve the independent reference implementation.
    harness = harness.replace('"../../native/rt/', '"'+(ROOT/'native/rt').as_posix()+'/')
    binaries = {}
    for mode in ('default', 'no-multiscatter'):
        define = ['-DRT_PARITY_NO_MULTISCATTER=1'] if mode != 'default' else []
        snapshots = []
        for label, source in (('baseline', baseline/'tests/transport_parity.slang'), ('candidate', ROOT/'shaders/rt/tests/transport_parity.slang')):
            folder = output/(label+'-'+mode)
            folder.mkdir(exist_ok=True)
            if label == 'baseline':
                # Same coverage; redirect fixture includes without changing the baseline.
                source = folder/'fixture.slang'
                fixture = (ROOT/'shaders/rt/tests/transport_parity.slang').read_text()
                fixture = fixture.replace('"../common/', '"'+(baseline/'common').as_posix()+'/')
                source.write_text(fixture)
            run([compiler, source, *define, '-target', 'cpp', '-entry', 'transport_parity', '-stage', 'compute', '-o', folder/'transport_parity.cpp'])
            (folder/'runner.cpp').write_text(harness)
            executable = folder/'runner.exe'
            run([cxx, '-std=c++17', '-O2', *define, '-I'+str(folder), folder/'runner.cpp', '-o', executable])
            native = run([executable]).strip()
            snapshot = folder/'output.bin'
            run([executable, '--snapshot', snapshot])
            snapshots.append(snapshot.read_bytes())
            binaries[label, mode] = executable
            report['checks'].append({'version':label, 'mode':mode, 'native':native})
            print(label, mode, native, flush=True)
        error = 0.0
        if snapshots[0] != snapshots[1]:
            if args.self_tolerance <= 0:
                raise RuntimeError('Self-comparison is not bitwise identical: '+mode)
            values = []
            for snapshot in snapshots:
                floats = array.array('f'); floats.frombytes(snapshot); values.append(floats)
            if len(values[0]) != len(values[1]) or not all(math.isfinite(v) for row in values for v in row):
                raise RuntimeError('Self-comparison contains nonfinite values or mismatched lengths: '+mode)
            error = max(abs(a-b)/max(1,abs(a)) for a,b in zip(*values))
            if error > args.self_tolerance:
                raise RuntimeError(f'Self-comparison exceeded {args.self_tolerance}: {mode} error={error}')
        report['checks'].append({'mode':mode, 'bitwiseSelfParity':snapshots[0]==snapshots[1], 'maxNormalizedSelfError':error, 'selfTolerance':args.self_tolerance, 'bytes':len(snapshots[0])})
    stages = () if args.skip_production_stages else ('material_primary_full', 'material_indirect_full', 'material_primary_full_query_ser', 'material_indirect_full_query_ser')
    report['productionStagesCheckedSeparately'] = args.skip_production_stages
    for entry in stages:
        base, defines = VARIANTS[entry]
        spv, reflection = output/(entry+'.spv'), output/(entry+'.json')
        run([compiler, ROOT/f'shaders/rt/world/{base}.slang', '-target', 'spirv', '-profile', 'spirv_1_5', '-entry', base,
             *['-D'+d+'=1' for d in defines], *(['-capability','spvShaderInvocationReorderNV'] if 'RT_SER' in defines else []),
             *(['-capability','spvRayQueryKHR'] if 'RT_RAY_QUERY' in defines else []), '-stage', 'raygeneration', '-matrix-layout-column-major', '-O2', '-o', spv, '-reflection-json', reflection])
        run([validator, '--target-env', 'vulkan1.2', spv])
        validate_byte_address_layout(spv.read_bytes())
        validate_kernel_storage(spv.read_bytes(), allow_ggx_inline=True)
        validate_layout(json.loads(reflection.read_text()), True, True)
        report['checks'].append({'stage':entry, 'validated':True, 'sha256':hashlib.sha256(spv.read_bytes()).hexdigest()})
    if args.cpu_timing:
        pairs = []
        for i in range(3):
            values = {}
            for label in (('baseline','candidate') if i%2==0 else ('candidate','baseline')):
                values[label] = float(run([binaries[label,'default'], '--bench']).strip())
            pairs.append(values)
        report['cpuFixtureMs'] = pairs
        report['cpuMedianMs'] = {label:statistics.median(p[label] for p in pairs) for label in ('baseline','candidate')}
        print('CPU fixture only:', report['cpuMedianMs'], flush=True)
    report['bsdfSha256'] = {label:hashlib.sha256((folder/'bsdf.slang').read_bytes()).hexdigest() for label,folder in (('baseline',baseline/'common'),('candidate',ROOT/'shaders/rt/common'))}
    report['ggxEnergySha256'] = {label:hashlib.sha256((folder/'ggx_energy.slang').read_bytes()).hexdigest() for label,folder in (('baseline',baseline/'common'),('candidate',ROOT/'shaders/rt/common'))}
    (output/'result.json').write_text(json.dumps(report, indent=2)+'\n')
    print('Focused BSDF checks passed', flush=True)


if __name__ == '__main__':
    main()
