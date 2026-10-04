#!/usr/bin/env python3
"""Summarize raw VoxelLight timing CSVs; missing GPU samples are never treated as zero."""
import argparse
import csv
import json
from pathlib import Path


def percentile(values, fraction):
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lo = int(index)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (index - lo)


def summarize(paths):
    groups = {}
    for path in paths:
        with Path(path).open(newline='') as stream:
            reader = csv.DictReader(stream)
            for row in reader:
                name = row.get('mode', 'unknown')
                for key, value in row.items():
                    if not key.endswith('_ns') or value in (None, ''):
                        continue
                    nanos = int(value)
                    # Native GPU-only records have placeholder zero CPU submission times.
                    if nanos < 0 or nanos == 0 and 'cpu' in key:
                        continue
                    groups.setdefault((name, key), []).append(nanos / 1_000_000)
    return [{'pass': name, 'metric': key, 'samples': len(values),
             'p50_ms': percentile(values, .5), 'p95_ms': percentile(values, .95)}
            for (name, key), values in sorted(groups.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', nargs='+', type=Path)
    parser.add_argument('--metadata', type=Path, required=True,
                        help='Pinned resolution, scene, distance, version, GPU and screenshot metadata JSON')
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text())
    print(json.dumps({'metadata': metadata, 'timings': summarize(args.csv),
                      'limitations': 'Submission timings are not CPU frame timings; pass sums are not GPU frame timings. Missing counters/VRAM/screenshots must be captured separately.'}, indent=2))


if __name__ == '__main__':
    main()
