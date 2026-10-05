#!/usr/bin/env python3
"""Explicit Linux x86_64 build-tool bootstrap, version and archive digest pinned."""
import hashlib, pathlib, platform, tarfile, urllib.request
VERSION = '2026.19'
SHA256 = '5899bd40c3d1ee60eadd1d1b37acc4e88ae65cc25ac61651534d55b71a85fe75'
def main():
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise SystemExit('Install Slang 2026.19 for your host and set SLANGC; install spirv-val separately.')
    root = pathlib.Path.home() / f'.cache/voxellight/tools/slang-{VERSION}'
    root.mkdir(parents=True, exist_ok=True)
    archive = root / 'download.tar.gz'
    urllib.request.urlretrieve(f'https://github.com/shader-slang/slang/releases/download/v{VERSION}/slang-{VERSION}-linux-x86_64-glibc-2.27.tar.gz', archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        archive.unlink(); raise SystemExit('Slang archive digest mismatch')
    with tarfile.open(archive) as package: package.extractall(root, filter='data')
    archive.unlink()
    print(f'Installed Slang {VERSION}: {root / "bin/slangc"}; also install spirv-tools (spirv-val).')
if __name__ == '__main__': main()
