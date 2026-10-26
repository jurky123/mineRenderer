# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Build the 26.2 integration prototype using an installed named client classpath.

No Gradle task, native rebuild, network request, install or game launch occurs.
The fixed upstream facade/interop sources are compiled without modification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--classpath-file', type=Path, required=True)
parser.add_argument('--fabric-api', type=Path, help='26.2 Fabric API jar, compile-only renderer, command and lifecycle dependencies')
args = parser.parse_args()
module = Path(__file__).resolve().parent
repo = module.parent
output = module / 'build'
classes = output / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)
facade = sorted((repo / 'rtxremix-java/src').rglob('*.java'))
interop = repo / 'src/main/java/com/nvidia/rtxremix/minecraft/gl/GlInterop.java'
input_sources = sorted((repo / 'src/main/java/com/nvidia/rtxremix/minecraft/input').glob('*.java'))
heap = repo / 'src/main/java/com/nvidia/rtxremix/minecraft/util/ClosestKHeap.java'
sources = facade + [interop, heap] + input_sources + sorted((module / 'src/main/java').rglob('*.java'))
argfile = output / 'javac.args'
argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
classpath = args.classpath_file.read_text().strip()
if args.fabric_api:
    with zipfile.ZipFile(args.fabric_api) as api:
        dependencies = output / 'fabric-compile-only'
        dependencies.mkdir(exist_ok=True)
        for name in api.namelist():
            if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                dependency = dependencies / Path(name).name
                dependency.write_bytes(api.read(name))
                classpath += ';' + str(dependency)
    (output / 'compile-classpath.txt').write_text(classpath,encoding='utf-8')
command = [str(args.java_home / 'bin/javac.exe'), '--release', '25', '-proc:none', '-encoding', 'UTF-8',
           '-cp', classpath, '-d', str(classes), '@' + str(argfile)]
result = subprocess.run(command, capture_output=True, encoding='utf-8', errors='replace')
(output / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8')
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
jar = output / 'rtx-remix-26.2-prototype.1.jar'
with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for folder in [classes, module / 'src/main/resources']:
        for path in folder.rglob('*'):
            if path.is_file():
                archive.write(path, path.relative_to(folder).as_posix())
    for name in ['LICENSE', 'NOTICE']:
        archive.write(repo / name, name)
    for path in (repo / 'src/main/resources/assets').rglob('*'):
        if path.is_file():
            archive.write(path, path.relative_to(repo / 'src/main/resources').as_posix())
receipt = {'minecraft': '26.2', 'java': 25, 'sourceCount': len(sources),
           'modVersion': json.loads((module / 'src/main/resources/fabric.mod.json').read_text())['version'],
           'upstreamFacadeFiles': len(facade), 'compiledClasses': len(list(classes.rglob('*.class'))),
           'jar': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
           'nativeBundled': False, 'nativeAbiModified': False,
           'sources': {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
if args.fabric_api:
    receipt['fabricApiCompileSha256'] = hashlib.sha256(args.fabric_api.read_bytes()).hexdigest()
(output / 'build-receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in receipt.items() if k != 'sources'}, indent=2))
