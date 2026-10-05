#!/usr/bin/env python3
"""Build-only Slang/SPIR-V compiler. No runtime compiler or source shader fallback."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
STAGES = {'primary': 'raygeneration', 'closest_hit': 'closesthit', 'sky': 'miss'}
def tool(name, variable):
    found = os.environ.get(variable) or shutil.which(name)
    if not found and name == 'slangc':
        cached = pathlib.Path.home() / '.cache/voxellight/tools/slang-2026.19/bin/slangc'
        if cached.is_file(): found = str(cached)
    if not found:
        raise SystemExit(f'{name} required at build time; set {variable}. See docs/VULKAN-RT-MIGRATION.md')
    return found

def validate_layout(reflection):
    parameters = {p['name']: p for p in reflection['parameters']}
    for name, index in {'world': 0, 'output': 1, 'normals': 2, 'camera': 3}.items():
        if parameters[name]['binding']['index'] != index:
            raise ValueError(f'descriptor ABI mismatch: {name}')
    camera = parameters['camera']['type']['elementType']
    offsets = {f['name']: f['binding']['offset'] for f in camera['fields']}
    if offsets != {'inverseClip0': 0, 'inverseClip1': 16, 'inverseClip2': 32, 'inverseClip3': 48, 'origin': 64, 'width': 80, 'height': 84, 'padding': 88}:
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
        source = ROOT / f'shaders/rt/world/{entry}.slang'; spv = output / f'{entry}.spv'; reflection = output / f'{entry}.json'
        subprocess.run([compiler, str(source), '-target', 'spirv', '-profile', 'spirv_1_5', '-entry', entry,
                        '-stage', stage, '-matrix-layout-column-major', '-O2', '-o', str(spv), '-reflection-json', str(reflection)], check=True)
        subprocess.run([validator, '--target-env', 'vulkan1.2', str(spv)], check=True)
        validate_layout(json.loads(reflection.read_text()))
        manifest['shaders'][entry] = {'stage': stage, 'sha256': hashlib.sha256(spv.read_bytes()).hexdigest(), 'bytes': spv.stat().st_size}
    (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(f'Validated {len(STAGES)} Vulkan RT stages, camera ABI=96 bytes')
if __name__ == '__main__': main()
