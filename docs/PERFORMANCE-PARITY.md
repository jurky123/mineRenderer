# Performance parity work

User direction (2026-10-09): prioritize Caustica performance parity, preserve
VoxelLight's metal/albedo tint contract, then fix exact mirrors and learn from
Caustica's material filtering and display pipeline.

## GGX storage candidate

The installed alpha.49 SPIR-V contains 52 Function-storage 1024-element arrays
and 52 stores of the canonical GGX constant composite in each of
`material_primary_full`, `material_indirect_full` and its iterative variant.
The composite's float32 bytes match the native GGX table. This establishes
compiler expansion, **not** actual driver spills, stack usage or GPU cost.

Production material stages now define `RT_GGX_ASSETS` and load the same table
from the existing binding-6 assets buffer. Header byte 248 contains its byte
offset. `VulkanRtMaterialAssets` allocates and uploads 4096 bytes once per
material-resource lifetime. Descriptor layouts remain unchanged.

`ggx_energy_data.slang` retains the canonical values for reference fixtures.
`tools/generate_ggx_asset.py --check` verifies serialized float32 data before
shader builds. A Java test compares every uploaded value to the native table.
The production and reference paths retain the same bilinear lookup, 16-sample
average-energy integration and Fresnel/BSDF equations. Metal tint, bounce
budgets, sampling budgets and default renderer settings are unchanged.

The compiler rejects 1024-, 2145- and 6435-element table arrays in production
SPIR-V. The transport fixture additionally tests `RT_GGX_ASSETS` against the
canonical native BSDF, exercising the byte-offset header and buffer loads.

## Same-quality integrator experiment

`/voxellight rt_benchmark integrator [seconds]` performs two ABBA rounds,
Wavefront versus Iterative. FULL transport and EXACT shadows are held fixed;
the configurations differ only in integrator scheduling. TRACE, FIXED,
OMM/SER off, MONOLITHIC, ENDPOINT and LINEAR guides remain matched.
Existing readiness, camera/focus, sample validity and comparison gates apply.
This does not make Iterative a new default or establish its superiority.

## Acceptance and remaining work

Compile/validate all shader variants, compare native/Slang transport including
the asset path, run isolated GPU fixture captures and inspect finite HDR/Guide
values. A selected Java/shader overlay retaining installed native libraries is
a development artifact, not a complete product release build.

Claim a speedup only after repeated same-quality GPU measurements with valid
blocks and controlled resource/scene/settings identities. Cross-renderer parity
also requires matched resolution, actual RR state, bounce definitions, lights,
materials and timing scopes. Background captures do not establish FPS.

Next performance targets are repeated multiscatter/Fresnel evaluations,
Wavefront dispatch/state traffic, and sampling overhead. Avoid trading quality
for speed during this phase. Exact mirror delta handling and material/display
changes remain separate acceptance work.

## Local validation on RTX 4060 Laptop (2026-10-09)

Slang 2026.19 compiled all 110 variants; SPIRV-Tools validated Vulkan 1.2,
descriptor/camera ABI checks passed, and no production table arrays remained.
Native/Slang transport passed 57,600 cases / 3,225,600 components per GGX path,
with the same maximum normalized error 0.000381917. The broader transport,
material, environment, queue and RIS fixtures passed through a local MSVC
adapter. Java compilation and the new asset/integrator tests passed.

The selected Java/shader overlay retained the installed native libraries.
Isolated terrain/mirror captures completed normally: four HDR/Guide exports,
zero nonfinite float values. Static captures establish resource/readback sanity,
not temporal quality equivalence or a resolved mirror model.

A separate launch after all compilation/regression tasks finished produced
8/8 valid integrator blocks. Wavefront / Iterative GPU medians (median of four
block medians): World 9.977344 / 11.424352 ms; RT batch 7.894184 / 9.314224 ms.
ABBA reports Iterative 18.0895% slower, repeat variation 1.1306%; CPU batch
medians 0.08190 / 0.06635 ms. Keep Wavefront. These results do not isolate GGX
storage benefit, prove the register/spill cause, or establish Caustica parity.
An earlier exploratory launch overlapped native regression work and is not
the formal measurement, despite passing the built-in block gates.


## Module acceptance update (2026-10-10)

116 valid exported blocks: 96 controlled A/B blocks and 20 per-camera production baseline blocks. One additional prepared launch stalled in vkDestroyPipeline and was terminated by the exact-owned-JVM watchdog after 300 seconds. Keep that failure evidence; lifecycle root cause remains unresolved. No formal sampling overlapped builds, and all existing validity/focus/workload/alive/variation gates remain unchanged.

GGX ASSETS storage is not accepted: RT batch improvement 4.61% initially, only 1.00% against 5.05% variation independently. `build_vulkan_rt.py` defaults to INLINE. Rebuild a current ASSETS bank explicitly (`--ggx-storage assets --output build/vulkan-rt-ggx-assets`) before the storage experiment; source/binary hashes must match. October 9 statements above describe the historical ASSETS artifact.

Exact environment caching is default off. Child prepare cost dropped from roughly 0.205 ms to 0.001 ms, but a fresh World comparison improved only 2.98%, within the unchanged threshold. Cloud animation inputs remain exact dependencies whenever clouds are enabled.

Same-hit GGX average/Fresnel reuse is default off, guarded by RT_GGX_PREPARED and Shader.GGX_PREPARED. The matched INLINE bank adds only FULL Primary/Indirect variants. Successful independent terrain ABBA launches each have 8/8 valid blocks: RT batch improvement 17.83% / 18.55%; World secondary analysis 13.87% / 14.39%. Repeat client frame interval improved 14.45%. This confirms terrain performance, not global material/temporal acceptance. The interrupted second launch remains documented.

FULL/EXACT queue-only comparisons: COMPACT improves terrain World 14.31% / 14.42% in independent launches, but glass/water World -0.88% is within variation. Its glass/water B1 cost grew roughly 4.82 to 6.22 ms. Do not globally enable COMPACT.

Explicit Queue.LATE keeps B1 fixed and compacts B2-B5 (mask 60). Glass/water independent World results 10.23% / 10.08%, both pass; terrain World 15.27% passes two ABBA rounds but has no independent terrain repeat. Repeat glass/water client frame interval improved 10.52%. AUTO/HYBRID defaults are unchanged. Do not add isolated speedup percentages to claim combined benefit.

Built-in GGX/queue comparisons use RT batch. `tools/acceptance/module_abba.py` analyzes unchanged World or client timestamps using the original numerical ABBA rule, only when original completeness/validity/comparability permits it. Missing timestamps and malformed order fail closed.

Both GGX_PREPARED/FIXED and CLEAN/LATE completed five-camera quality-only captures: 10 HDR/Guide exports per candidate, zero nonfinite floats, authored mirror ROI roughness unchanged. LATE actual execution state is recorded in capture-states.log. These checks establish data sanity, not stochastic image equivalence, temporal quality, or a repaired mirror model. Metal tint/material filtering/display are unchanged.

All 110 default plus 2 prepared shaders pass Vulkan SPIR-V/ABI validation. Complete native/Slang regression passes, including three GGX paths at 57600 cases / 3225600 components each, max normalized error 0.000381917. Java compilation and targeted new logic tests pass; final full suite has 386 tests, 3 failures: two unchanged native material source contracts and missing packaged RR libraries. Native release rebuilding remains unavailable; selected overlays retain original native libraries and are development artifacts.

Production baseline allows live terrain/dynamic models/sky/wind, unlike frozen-input controlled A/B. Resource logs show dynamic BLAS/TLAS updates in mirror/glass/entity regions. Treat its five-camera costs as descriptive module localization. Hardware ALU/cache/spill breakdowns, moving entities/chunks, reload/resize/dimension changes, and cross-renderer same-workload parity remain unproven.

First prepared launch telemetry was overwritten by a reused state; its raw suite ZIP remains. Future launches archive previous metadata/telemetry, verify prepared artifact SHA and record it with process identity. Do not invent missing frequency/temperature data.

A separate compiler diagnostic launch used pipelineStatistics=true with no performance suite. Driver status reports captureEnabled=true but no pipeline executables for these RT stages. Nsight Systems 2024.5.1 GPU metric enumeration reports ERR_NVGPUCTRPERM / insufficient privilege. Runtime occupancy/cache/spill metrics remain unavailable under current permissions; empty compiler CSV does not establish zero usage. No global driver permission change was made.
