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
