#!/usr/bin/env python3
"""Run a saved alpha.79 lighting capture on Vulkan without starting Minecraft.

Requires Java 25, LWJGL jars/natives, the five saved capture buffers and the
four compiled replay0..3.spv compute stages. Results include GPU timestamps
and numerical diagnostics, not production-frame performance claims.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=pathlib.Path, required=True)
    parser.add_argument('--shaders', type=pathlib.Path, required=True)
    parser.add_argument('--classpath-file', type=pathlib.Path, required=True)
    parser.add_argument('--natives', type=pathlib.Path, required=True)
    parser.add_argument('--java-bin', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--stages', type=int, default=4, choices=range(1, 65))
    parser.add_argument('--groups', type=int, default=32)
    parser.add_argument('--trials', type=int, default=100)
    parser.add_argument('--table-bytes', type=int, default=0,
                        help='Optional diagnostic table readback after each stage')
    args = parser.parse_args()
    if not 1 <= args.groups <= 65535 or not 1 <= args.trials <= 10000 or args.table_bytes < 0:
        parser.error('Invalid replay dimensions')
    if args.output.exists():
        raise SystemExit('Choose a new output directory to preserve earlier evidence')
    required = [args.capture / (name + '.bin') for name in
                ('camera', 'assets', 'geometry', 'normals', 'feedback')]
    required += [args.shaders / f'replay{k}.spv' for k in range(args.stages)]
    for path in required:
        if not path.is_file():
            raise SystemExit('Missing replay input: ' + str(path))
    if (args.capture / 'camera.bin').stat().st_size != 96:
        raise SystemExit('Expected the 96-byte captured camera ABI')
    args.output.mkdir(parents=True)
    classes = args.output / 'classes'
    classes.mkdir()
    classpath = args.classpath_file.read_text().strip()
    source = pathlib.Path(__file__).resolve().parent / 'native/OfflineLightingReplay.java'
    import os
    suffix = '.exe' if os.name == 'nt' else ''
    subprocess.run([str(args.java_bin / ('javac' + suffix)), '-cp', classpath,
                    '-d', str(classes), str(source)], check=True)
    identity = {str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in [*required, source]}
    (args.output / 'inputs.json').write_text(json.dumps({
        'sha256': identity, 'standalone': True, 'formalTimingEligible': False,
        'scope': 'Frozen compute replay; not an in-game speedup test',
        'stages': args.stages, 'groups': args.groups, 'trials': args.trials,
        'tableBytes': args.table_bytes
    }, indent=2))
    # Match the isolated profiling clients; do not load third-party overlays
    # into a headless compute-only process. This changes only the child env.
    env = dict(os.environ, VK_LOADER_LAYERS_DISABLE='~implicit~',
               DISABLE_VK_LAYER_RTSS_1='1')
    completed = subprocess.run([str(args.java_bin / ('java' + suffix)),
                    '--enable-native-access=ALL-UNNAMED',
                    '-Djava.library.path=' + str(args.natives.resolve()),
                    '-Dreplay.stages=' + str(args.stages),
                    '-Dreplay.groups=' + str(args.groups),
                    '-Dreplay.trials=' + str(args.trials),
                    '-Dreplay.tableBytes=' + str(args.table_bytes),
                    '-cp', str(classes.resolve()) + os.pathsep + classpath,
                    'OfflineLightingReplay', str(args.capture.resolve()),
                    str(args.shaders.resolve()), str(args.output.resolve())],
                    env=env, text=True, capture_output=True)
    log = args.output / 'process.log'
    log.write_text(completed.stdout + completed.stderr, encoding='utf-8')
    if completed.returncode:
        raise SystemExit(f'Replay exited {completed.returncode}; inspect {log}')
    print(completed.stdout.strip())


if __name__ == '__main__':
    main()
