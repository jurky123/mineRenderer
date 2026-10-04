#!/usr/bin/env python3
"""Extract actual native compiler timings. Missing finishes stay missing, never zero."""
import argparse
import json
import re
from pathlib import Path


def summarize(text):
    modules, tasks, groups, links, cache = [], [], [], [], []
    active = {}
    for line in text.splitlines():
        if 'VoxelLight compile start ' in line:
            module = re.search(r'module=(\S+)', line).group(1)
            modules.append({'module': module, 'start': line, 'elapsed_ms': None})
        elif 'VoxelLight compile finish ' in line:
            name = re.search(r'module=(\S+)', line).group(1)
            for module in reversed(modules):
                if module['module'] == name and module['elapsed_ms'] is None:
                    module['elapsed_ms'] = int(re.search(r'elapsed_ms=(\d+)', line).group(1))
                    module['timeout'] = 'timeout=1' in line
                    break
        elif 'VoxelLight task start ' in line:
            key = tuple(re.search(r'module=(\S+) id=(\d+)', line).groups())
            record = {'module': key[0], 'id': int(key[1]), 'start': line, 'finish': None}
            tasks.append(record)
            active[key] = record
        elif 'VoxelLight task finish ' in line:
            key = tuple(re.search(r'module=(\S+) id=(\d+)', line).groups())
            if key in active:
                active.pop(key)['finish'] = line
        elif 'VoxelLight program groups ' in line:
            groups.append(line)
        elif 'VoxelLight pipeline link ' in line:
            links.append(line)
        elif 'VoxelLight OptiX cache' in line:
            cache.append(line)
    return {'modules': modules, 'tasks': tasks, 'program_groups': groups,
            'pipeline_links': links, 'cache': cache,
            'unfinished_tasks': [t for t in tasks if t['finish'] is None],
            'gpu_runtime': 'Use profile export pass CSVs; compiler logs do not measure GPU runtime.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('logs', nargs='+', type=Path, help='First/cold and second/warm startup logs')
    p.add_argument('--build', type=Path, help='compile-build.json from the kit')
    a = p.parse_args()
    result = {'runs': {str(path): summarize(path.read_text(errors='replace')) for path in a.logs}}
    if a.build:
        result['nvrtc_builds'] = json.loads(a.build.read_text())
    print(json.dumps(result, indent=2, ensure_ascii=False))
