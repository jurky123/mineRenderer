# alpha.51 — First module optimization batch

Baseline: mineRenderer `bbd3db6`; Caustica diagnostic checkout `1477059`.
The alpha.50 report and compact evidence are in `alpha50/`. Raw captures remain
in the local workspace's `outputs/alpha.50`; those large artifacts are not in Git.

This batch is a candidate, not a performance-alignment acceptance. Per the user,
validation uses offline production-shader fixtures and compilation without
launching Minecraft or repeating the Caustica suite. GPU frame-time speedup is
not measured by those checks.

| Module | Baseline cost | Change | Evidence / limitation |
| --- | --- | --- | --- |
| EXACT visibility | An opaque pretest followed by ordered nonopaque traversal on mixed scenes | Keep the query-only all-opaque shortcut; mixed scenes trace all geometry in the ordered interface loop | Clear connections remove a redundant traversal. An opaque blocker behind many transparent interfaces can favor the old early pretest; scene-wide speedup remains unverified. |
| Shadow material | Full shading-frame path called for geometric interface transport | Retain the full material decoder, wet behavior, extinction, scattering, IOR and identity; skip tangent/normal-map frame construction | Production visibility fixtures pass. Driver/compiler dead-code elimination may already remove part of the old work; no independent GPU benefit is claimed. Dynamic entity material decoding retains its existing frame path. |
| Continuation storage | A live-path hot state is fetched again; a nonempty medium stack writes the complete 288-byte allocation | Pass the existing hot state to loadPath; write only active entries and necessary ID vectors | Same 64/48-byte hot, 288-byte cold and 48-byte AOV ABIs. Disassembled noncompact indirect shader has two fewer static hot-buffer loads per lane. Cold stores change from 18 static vector stores to a loop with two stores plus one/two ID stores. |
| Direct RIS evaluation | Evaluate the BSDF before rejecting an inconsistent geometric/shading hemisphere | Reject the same invalid hemisphere before evalBsdf | 320 material/direction comparisons against the original formula pass. Proposal and reservoir RNG are unchanged. Diagnostic BSDF counts now count fewer actual calls. |

For one active medium, the emitted cold writes cover 48 bytes instead of 288;
for four, 144; for eight, 288. These are logical shader stores, not measured
memory-bus bytes. The baseline compiler already loaded active absorption and
scattering entries, so that source rewrite is not counted as a new prefix-read
optimization. Only the second ID vector becomes conditional on count > 4.
Inactive entries can remain stale: only indices below mediumCount are consumed,
and an entering medium writes its active state before dispatch continuation.

## Validation

- Build all 110 Vulkan RT variants with Slang 2026.19 and spirv-val, including
  compact and iterative paths; reflection confirms continuation ABI sizes.
- Execute production Slang CPU/native transport parity through
  `tools/verify_vulkan_transport.py` from the repository directory, with Java 25,
  Slang, SPIR-V tools and MSYS2 C++. Set JAVA to the absolute Java executable
  on Windows to avoid legacy system launcher resolution.
- Extended visibility fixture exercises all eight hot-buffer lanes, poisoned
  cold storage, medium depth transitions 8 -> 0 -> 8, material/hemisphere
  comparisons, and existing flame/cutout/glass/water/foliage/extinction plus
  primary known-hit RNG/continuation checks.
- `tools/compare_path_storage_spirv.py` compares spirv-dis output. The checked-in
  JSON records static instruction counts only; loops, branch execution, driver
  lowering, registers, occupancy and cache traffic remain unmeasured.

The existing production transport parity suite also covers native BSDF/PDF,
GGX variants, terrain material, environment, cache lobes, diffuse integration,
reconstruction, path storage, queues and direct-light energy/PDF. The full suite
completed with exit code 0 on 2026-10-11. Its output is preserved in
`alpha51-transport-parity-result.txt`; the storage comparison is in
`alpha51-path-storage-spirv-comparison.json`. All 110 shader variants compiled
and passed Vulkan validation. These checks establish compatibility/numerical
regression coverage, not GPU performance acceptance.

## Remaining work

Primary/Guides reuse, CPU scene preparation, GPU AS updates and RR/display are
unchanged by this batch. Keep the existing FULL and ENDPOINT defaults; do not
enable approximate visibility or guide variants to manufacture performance
parity. Continue with the measured primary and first two transport stages before
changing low-cost later passes. These candidates require targeted GPU timing
before acceptance when runtime measurement is resumed.
