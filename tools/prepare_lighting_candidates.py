#!/usr/bin/env python3
"""Prepare frozen candidate tables for build_lighting_blocks.py diagnostics.

First run without --generated-table, execute generate/replay0.spv with
--stages 1 --table-bytes 4194304, then run again with its stage-0-table.bin
to prepare the dense benchmark inputs. Always choose new output directories.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import shutil
import struct

import numpy as np


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--capture', type=pathlib.Path, required=True)
    ap.add_argument('--output', type=pathlib.Path, required=True)
    ap.add_argument('--generated-table', type=pathlib.Path)
    args = ap.parse_args()
    if args.output.exists():
        ap.error('Choose a new output directory to preserve earlier evidence')
    paths = [args.capture / (n + '.bin') for n in
             ('camera', 'assets', 'geometry', 'normals', 'feedback')]
    if not all(p.is_file() for p in paths):
        ap.error('Missing captured resource')
    camera = paths[0].read_bytes()
    if len(camera) != 96:
        ap.error('Expected 96-byte camera ABI')
    width, height, padding = struct.unpack_from('<II4xI', camera, 80)
    if min(width, height) < 1 or max(width, height) > 16384 or padding > 64:
        ap.error('Invalid captured dimensions')
    base = 1648 + 4 * width * height * max(1, padding)
    table_offset = (base + 2048 * 128 + 2048) * 16
    feedback = bytearray(paths[-1].read_bytes())
    if len(feedback) < table_offset:
        ap.error('Feedback does not contain the complete capture record area')
    count = struct.unpack_from('<I', feedback, 1634 * 16)[0]
    if not 0 < count <= 2048:
        ap.error('Capture overflow or empty capture')
    with paths[1].open('rb') as stream:
        stream.seek(216)
        if max(struct.unpack('<III', stream.read(12))) > 8:
            ap.error('Candidate table supports at most eight proposals per parent')
    feedback.extend(bytes(max(0, table_offset + 16384 * 256 - len(feedback))))
    coverage = {'capturedFunctions': count, 'formalTimingEligible': False,
                'tableBytes': 16384 * 256,
                'scope': 'Frozen diagnostic data, not a frame-cost measurement'}
    table = None
    if args.generated_table:
        data = args.generated_table.read_bytes()
        if len(data) != 16384 * 256:
            ap.error('Expected the complete 16384-slot candidate table')
        table = np.frombuffer(data, dtype='<u4').reshape(-1, 64)
        candidates = table[table[:, 0] == 1]
        if not len(candidates) or np.any(candidates[:, 1] >= count):
            ap.error('Invalid generated parent identities')
        parents = np.frombuffer(feedback, dtype='<u4', offset=base * 16,
                                count=2048 * 512).reshape(-1, 512)
        if np.any(parents[candidates[:, 1], 0] != 0):
            ap.error('Candidate parent is not a captured complete RIS call')
        dense = candidates[np.arange(16384) % len(candidates)]
        feedback[table_offset:table_offset + dense.nbytes] = dense.tobytes()
        struct.pack_into('<I', feedback, 1635 * 16, len(dense))
        accepted = candidates[candidates[:, 6] != 0]
        coverage.update(candidates=len(candidates), accepted=len(accepted),
                        repeatedInvocations=len(dense),
                        distinctParents=len(set(map(int, candidates[:, 1]))),
                        kinds=dict(collections.Counter(map(int, candidates[:, 16]))),
                        acceptedMaterial=dict(collections.Counter(
                            map(int, parents[accepted[:, 1], 44]))))
    args.output.mkdir(parents=True)
    for path in paths[:-1]:
        shutil.copyfile(path, args.output / path.name)
    (args.output / 'feedback.bin').write_bytes(feedback)
    if table is not None:
        shutil.copyfile(args.generated_table, args.output / 'original-table.bin')
    (args.output / 'coverage.json').write_text(json.dumps(coverage, indent=2))
    (args.output / 'identity.json').write_text(json.dumps({
        'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
        'generatedTableSha256': hashlib.sha256(args.generated_table.read_bytes()).hexdigest()
        if args.generated_table else None,
        'formalTimingEligible': False}, indent=2))
    print(json.dumps(coverage, indent=2))


if __name__ == '__main__':
    main()
