#!/usr/bin/env python3
"""Summarize a VoxelLight *.passes.csv export; timings are passes/world render regions, never total frames."""
import csv
import math
import statistics
import sys
from collections import defaultdict


def summarize(path):
    groups = defaultdict(lambda: [[], []])
    with open(path, newline='', encoding='utf-8') as source:
        for row in csv.DictReader(source):
            cpu, gpu = groups[row['mode']]
            cpu.append(int(row['pass_cpu_submission_ns']) / 1e6)
            if row['pass_gpu_ns']:
                gpu.append(int(row['pass_gpu_ns']) / 1e6)
    print('pass,samples,cpu_p50_ms,cpu_p95_ms,gpu_samples,gpu_p50_ms,gpu_p95_ms')
    def pair(values):
        if not values:
            return ', '
        ordered = sorted(values)
        return f'{statistics.median(values):.4f},{ordered[math.ceil(.95 * len(values))-1]:.4f}'
    for name, (cpu, gpu) in sorted(groups.items()):
        print(f'{name},{len(cpu)},{pair(cpu)},{len(gpu)},{pair(gpu)}')


if __name__ == '__main__':
    summarize(sys.argv[1])
