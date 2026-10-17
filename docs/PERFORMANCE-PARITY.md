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

## October 13 alpha.53 — adopt measured late compaction

The initial execution queue is now LATE: B1 remains fixed and B2–B5 use compaction (mask 60). The existing Vulkan capability/capacity and iterative-integrator gates still fall back to fixed dispatch. Explicit saved queue choices, including AUTO, are preserved and can override the initial default.

This decision uses the accepted October 10 self-comparisons above: glass/water World improved 10.23% and 10.08% in independent clients; terrain improved 15.27% in two ABBA rounds within one client. Those are historical queue-only results, not fresh alpha.53 FPS measurements or additive gains on top of alpha.52. No Caustica runs were repeated.

Validation was limited to the four affected Java test classes: 14 tests passed, zero failures/errors/skips; Java main/client compilation passed. Production shader sources match the already validated alpha.52 110-stage bank, so that bank was reused. Runtime validation is capture-only in one glass/water scene. The first capture inherited a saved AUTO override and executed FIXED; it is excluded from LATE acceptance. A separate state removes only that saved override to check the initial default directly.

The default-state confirmation passed: both ENDPOINT and ENDPOINT_FAST captured observed `queue=LATE`, `transport=FULL`, `shadow=EXACT`, `realtimePolicy=FULL`, `shader=CLEAN`, `integrator=WAVEFRONT`. All 26 raw float layers were finite; HDR input, RR output and transmission AOV were nonzero. Input resolution was 524x336, RR output 1048x672. Windows-MCP verified the owned foreground window and the client exited normally with code 0. The accepted diagnostic overlay SHA-256 is `5d5b2bed6f1efbf2538419eec6b077193f52f0fad80f7cc12cbea077068ddc9e`. This confirms integration of LATE with alpha.52, not a new measurement of their combined speedup.

A proposed BSDF preparation change was rejected and reverted. With the same 57,600 material cases, both ordinary and multiscattering-disabled outputs matched the frozen alpha.52 CPU target bitwise and passed native parity; four FULL raygen stages passed SPIR-V/ABI validation. Three alternating CPU fixture pairs nevertheless gave medians 271.547 ms (baseline) and 279.178 ms (candidate), approximately 2.81% slower. This CPU result does not establish a GPU regression, but gives no basis to accept the change as an acceleration. Production retains the alpha.52 BSDF byte-for-byte. `tools/verify_bsdf_hot_path.py` supports these focused native/self checks without running unrelated modules or launching Minecraft; its timing option is explicitly CPU-only.

Runtime evidence and the rejected candidate report are archived locally under workspace `outputs/alpha.53`. Development overlays retain the baseline native DLLs; the original installed game is untouched.

## October 13 alpha.54 — GGX average experiments rejected

The user accepts material numerical differences below `1e-2`. Self comparisons use `abs(candidate-baseline)/max(1,abs(baseline))`; this tolerance is not an image-quality equivalence test and does not excuse changes to visibility, medium identity or sampling probabilities.

The measured alpha.52 + LATE baseline in multi_light remains far from the historical alpha.50 Caustica target. Baseline GPU P50: World 14.706464 ms, Primary/Guides 3.785568 ms, Transport 8.752128 ms, PT ready for RR 12.663456 ms, RR 1.177296 ms. Historical Caustica PT ready was 2.214 ms and RR 1.180 ms. Pass fusion differs between renderers, so individual Primary/Transport ratios are not like-for-like; this is a localization reference, not a new cross-renderer parity test. No Caustica client was launched.

Source inspection found that `ggxAverageEnergy` integrates 16 bilinear GGX samples for eligible material hits. We tested preintegrating that fixed weighted sum at the 32 canonical roughness nodes, then interpolating two values. One implementation used scalar selection branches, the other used a 128-byte resource in the existing material asset buffer. Both retain the canonical angular energy table, scattering model, sample budgets and random stream. The buffer variant uses reserved header word 252 and uploads once per material resource lifetime; it adds no descriptor or per-frame barrier.

| GPU stage P50 (ms) | Baseline | Scalar lookup | Buffer lookup |
| --- | ---: | ---: | ---: |
| World | 14.706464 | 14.919968 | 15.039824 |
| Primary + Guides | 3.785568 | 3.775840 | 3.777920 |
| Transport | 8.752128 | 9.006912 | 9.031808 |
| PT ready for RR | 12.663456 | 12.923808 | 12.962064 |
| B1 | 4.791008 | 4.971840 | 4.990048 |
| B2 | 3.599680 | 3.681440 | 3.689920 |
| RR | 1.177296 | 1.176448 | 1.177040 |

Neither implementation demonstrates acceleration. Buffer Transport increased 3.20% and total PT 2.36% in this observation; both implementations are rejected and their production changes reverted. The scalar run retained the old near-mirror quadrature as a numerical guard; the buffer run removes that guard under the newly accepted tolerance. These are one short production block per artifact against one shared baseline, not independent repeated statistical estimates. They are sufficient to withhold acceptance, not to prove a universal regression or a hardware bottleneck.

Comparison controls: multi_light, 524x336 input / 1048x672 output, 1 SPP, CLEAN, FULL/EXACT, Wavefront, LATE, QUERY with OMM/SER. Each block follows warmup and measures 12 seconds. Camera position/projection/rotation, world clock, terrain signature, render controls and focus match; blocks pass exported validity checks and GPU timestamp coverage exceeds 95%. Counter frames are excluded. GPU clock was 2520 MHz throughout all observed intervals, which include benchmark warmup. Temperature medians were 77 C baseline and 79 C for each candidate. No build overlapped sampling.

Numerical and integration evidence:

- Buffer-path Slang/native tests pass for 57,600 cases / 3,225,600 components in each of ordinary and multiscattering-disabled modes. Maximum normalized self error is `1.3366341590881348e-5`; disabled multiscattering is bitwise identical. Native-reference maximum errors remain `0.000381917` and `0.000381945` respectively.
- Two Java tests verify the canonical asset and dense roughness interpolation against the original quadrature; main/client compilation passes. All 110 actual buffer-path production stages pass SPIR-V and descriptor/continuation ABI validation.
- Both candidate clients exit normally. Each exports ENDPOINT and ENDPOINT_FAST at FULL/EXACT/LATE: 26 finite float layers, nonzero HDR/RR/transmission. This checks integration/data sanity, not stochastic or temporal image equivalence. Buffer overlay SHA-256: `e4a97f97913d6ded380d828e3fc5c75584cc68eb5894c798e704e8b6ef70a61d`.
- After rejection, all 44 production common/world source hashes match the accepted alpha.52 shader bank. Alpha.53's LATE initial queue remains enabled. Candidate source, resource, binaries and evidence remain in local experiment directories, outside production.

The experiment closes one hypothesis: removing the visible GGX average arithmetic did not improve this scene. Branch cost or buffer latency alone cannot explain the complete gap from these measurements, and register/cache/traversal attribution remains unmeasured. The next investigation should focus on the B1/B2 hit path: exact visibility/medium traversal, RIS candidate processing and material preparation. Together B1/B2 dominate the current Transport cost; RR is already close to the historical reference. Use a narrowly scoped source change with module validation and a short self comparison before accepting it. Do not lower path budgets to manufacture parity.

Local evidence: workspace `outputs/alpha.54/self-comparison.json` (rejected scalar), `buffer-self-comparison.json`, `buffer/bsdf-validation.json`, `buffer/e2e-validation.json`, `buffer/rejected-source/`, and `restoration.json`. Development overlays retain baseline native libraries; installed game files are unchanged.

## October 13 alpha.55 — exact RIS family decision rejected

Code comparison identified additional candidate-generation work: VoxelLight computes a complete 24-bit nested Owen sample for each RIS candidate, while its only production consumer compares that value with the environment-family probability (0.2 with emitters, 1 otherwise). Caustica's `lighting.slang` uses ordinary RNG and deterministic local/global candidate scheduling, not this 24-level decision. Historical candidate counts indicate this operation occurs frequently, but do not establish its share of current GPU time.

The experiment terminated Owen digit generation as soon as the remaining suffix could no longer change the family choice. For integer sample bits `b`, the original strict comparison is exactly `b < ceil(probability * 2^24)`. This preserves the nested scramble, chosen family, RNG consumption and candidate budget; digital-shift sampling remains unchanged. Seven million comparisons include strict threshold equality, adjacent thresholds, zero/one probabilities and varied 32-bit indices/scrambles. All pass. The full production clean RIS fixture passes 64 configurations of 200,000 samples each (points/mixed/adapted/empty, surface/volume, four bounce depths, both sequences); baseline and candidate snapshots are bitwise identical, SHA-256 `a2dee1e94627307fd5725717f3d66e116dd0cdbf77d98a42bf4b58417d236662`, 409,600,000 bytes each.

All 110 production stages pass SPIR-V and ABI checks. Artifact comparison confirms Java classes and non-shader resources are identical to baseline. One multi_light client measures one 12-second production block after warmup and captures both guide modes. It exits normally; all 26 captured float layers are finite, with nonzero HDR/RR/transmission. Candidate overlay SHA-256: `7e5324de59e3a3be286f37acc57763b468f10bd28062a4aa12d696b5d06f498d`. Sampling remains OWEN, LATE, FULL/EXACT, 1 SPP at 524x336 input.

The candidate fails performance acceptance. Because recent candidates all raised B1 relative to the older baseline, one additional profile-only original-bank client checks baseline drift. This is not a Caustica rerun or a full scene sweep.

| GPU P50 ms | Older alpha.54 baseline | Fresh original-bank recheck | Candidate |
| --- | ---: | ---: | ---: |
| World | 14.706464 | 14.507504 | 14.878432 |
| Primary + Guides | 3.785568 | 3.804496 | 3.674816 |
| Transport | 8.752128 | 8.484496 | 9.040032 |
| PT ready for RR | 12.663456 | 12.510768 | 12.853792 |
| B1 | 4.791008 | 4.580064 | 4.992640 |
| B2 | 3.599680 | 3.557888 | 3.683360 |
| RR | 1.177296 | 1.176336 | 1.176320 |

Against the fresh original-bank run, Primary improves 3.41%, but Transport increases 6.55% and total PT increases 2.74%. The same rejection holds against the older baseline. All measured blocks pass validity and >95% GPU timestamp coverage, with matching camera/clock/render controls. Candidate and recheck both hold 2520 MHz and have median GPU temperature 79 C; their median reported board powers differ (79.88 vs 81.55 W). These short independent windows do not isolate shader hardware causes or establish universal percentages. They do establish that the proposed all-stage change has no acceptance basis.

Production is restored: all 44 common/world source hashes again match the accepted alpha.52 bank, preserving alpha.53 LATE. The proposed helper and test runner are archived under workspace `outputs/alpha.55/rejected-source/` rather than left as dead production code. Do not interpret fewer scalar hash operations as GPU acceleration. Early-exit divergence or compiler scheduling are hypotheses; hardware counters remain unavailable. Primary's small directional improvement is insufficient to deploy a stage-specific variant without its own validation.

Next causal investigation should isolate the direct-light contribution work inside B1/B2 (candidate geometry/emission, BSDF target evaluation, exact visibility), retaining continuation and RNG for diagnostic variants wherever possible. Report diagnostic removal as a cost bound, not a production optimization, and verify that work outside the removed component is preserved before assigning causality. Existing historical visibility counts must be refreshed narrowly before applying the old two-traversal explanation: current mixed EXACT traversal already removed that pretest.

Evidence: workspace `outputs/alpha.55/family-validation.json`, `artifact-difference.json`, `self-comparison.json`, `recheck/self-comparison.json`, `e2e-validation.json`, and `restoration.json`. The recheck uses the frozen alpha.52 shader manifest; overlay source inventory at packaging time also contains the experimental source and must not be substituted for that bank's provenance. No production performance change was accepted in alpha.55.

## October 14 alpha.56 — partial Caustica code reuse

At the user's request, a real source transplant was tested. Caustica's project license is LGPL-3.0-or-later (`LICENSE.md`); the experiment preserves copyright, upstream commit and COPYING/COPYING.LESSER. GGX D/G1 and Schlick Fresnel helpers were adapted from `shaders/pipelines/world/math.slang` at commit `14770591086232e13a6b1d5320b0f2f8ef8731e3`. No NVIDIA proprietary implementation or binary was copied.

For uncoated, nontransmitting, isotropic ROUGH_DIFFUSE receivers, a Caustica-style Lambert+GGX surrogate replaces full BSDF evaluation of every RIS candidate. The selected contribution is then evaluated with VoxelLight's original FULL BSDF and multiplied by `weightSum/(M * surrogateTarget * survival)`. The selected surrogate target must remain in that denominator: substituting the exact target there would invalidate the estimator. Spatial emission, geometry, proposal PMFs, sample count, continuation BSDF and EXACT shadows remain unchanged. Metals, dielectric/coated/anisotropic and volume/cache-specular paths retain the original target.

Validation: the existing 64-configuration RIS fixture passes native energy/PDF/finite/stratification checks and gives identical reported fallback results. A separate 16-configuration ROUGH_DIFFUSE comparison uses 500,000 samples per configuration, maximum normalized mean difference `0.0017014456318347779` (tolerance 0.01), variance ratios 0.6012–1.0146. This is statistical mean evidence for those fixtures, not samplewise equality, a guarantee across all material parameters, or a temporal image-quality score. All 110 production stages pass SPIR-V/ABI checks. Java classes are unchanged. One multi_light client confirms LATE/FULL/EXACT and 1 SPP, exports ENDPOINT and ENDPOINT_FAST (26 finite float layers, nonzero HDR/RR/transmission), and exits normally. Candidate SHA-256: `a4594ae2b6f8a985a5ef0997be71f51c7d38986477b8d0bc3ae8a79006332d95`.

| GPU P50 ms | Oct 13 original-bank recheck | Oct 14 transplanted target |
| --- | ---: | ---: |
| World | 14.507504 | 16.013248 |
| Primary + Guides | 3.804496 | 3.916672 |
| Transport | 8.484496 | 9.786592 |
| PT ready for RR | 12.510768 | 13.880704 |
| B1 | 4.580064 | 5.814848 |
| B2 | 3.557888 | 3.683456 |
| RR | 1.176336 | 1.178272 |

No acceleration is demonstrated; the transplant is rejected. Both windows hold 2520 MHz, but dates differ and median temperature is 79 vs 82 C, reported median board power 81.55 vs 79.96 W. Matching pose/render/terrain controls and >95% GPU timestamp coverage do not eliminate all between-run variation. The apparent PT increase is 10.95%, concentrated in B1; it is not a universal causal regression estimate. Candidate selection changes shadow-ray destinations, and hybrid shader expansion/control flow may also affect cost; current data cannot separate these mechanisms. Default production is restored, with all 44 common/world hashes matching the accepted alpha.52 bank and alpha.53 LATE preserved. The imported source, licenses, test runner and raw evidence are retained locally under `outputs/alpha.56/rejected-source/`.

A more substantial execution difference was confirmed directly in source after this experiment:

- Caustica `trace_ser.slang:traceRadianceReordered` constructs a hit object, reorders by shader/bucket/path-phase coherence, and only then invokes the hit shader that performs divergent geometry/material decoding. It rebuilds the payload after reorder to avoid carrying unnecessary trace outputs across that point.
- VoxelLight `material_transport.slang:advanceHitCore` currently loads media, traces, calls `terrainSurface`/primary-guide classification, writes guides and handles the initial medium before `ReorderThread(surface.material.type,4)`. Thus this reorder cannot improve the preceding decode's access coherence, and more decoded state is live at the reorder. The source order is proven; spill/occupancy and the size of the performance penalty remain unmeasured.
- Caustica uses a packed shaded payload and hit-object dispatch; VoxelLight's closest-hit writes only distance/barycentric/triangle/instance, leaving full shading in raygen. An exact transplant therefore requires adapting hit responsibilities, payload and capability handling together, rather than copying a shading formula or substituting a simplified material model.

The next bounded reuse experiment should move reorder ahead of full material decode, defer medium/context preparation where semantics permit, and preserve the supported NV SER/fallback paths. Caustica's hit-object implementation must not be assumed drop-in compatible with VoxelLight's currently compiled NV capability. First verify the active Slang/Vulkan implementation and live-state boundary, then validate output and GPU cost. Do not claim that moving SER is already an acceleration.

Evidence: workspace `outputs/alpha.56/ris-validation.json`, `shader-validation.log`, `artifact-difference.json`, `self-comparison.json`, `e2e-validation.json`, `rejected-source/`, `restoration.json`. No Caustica client was relaunched.

## October 14 alpha.57 — accepted pre-decode Indirect SER boundary

Caustica's pre-shading reorder design is adapted to VoxelLight's existing NV SER interface. Indirect SER variants now trace first, use one geometric flags word plus path phase as a four-bit coherence hint, reorder, and only then construct the medium context and decode the complete surface. The hint is scheduling metadata, not material classification used for shading. Existing BSDF, optical interfaces, guide math, proposal/RNG and path budgets are unchanged. The full PathState is still live across this boundary; this is not a complete packed-payload or hit-object migration and does not establish spill/occupancy measurements.

Production selection is explicit in `tools/build_vulkan_rt.py`: only `material_indirect` variants with RT_SER receive `RT_SER_PRE_DECODE`. Primary retains its established boundary and original binary. Non-SER fallback code retains its original order. The first exploratory bank moved all SER stages; its slightly slower Primary observation motivated limiting deployment. The final Primary binaries are bitwise identical to the accepted bank, and all 20 changed Indirect binaries are bitwise identical to the exploratory candidate's corresponding binaries.

`tools/build_ser_boundary_experiment.py` rebuilds changed stages and reuses a baseline stage only when the current and frozen sources have exactly equal compiler preprocessor output under that stage's defines, the compiler version matches, and binary hashes match. The final bank rebuilds 20 Indirect SER stages and reuses 90; every one of the 110 binaries is validated again for SPIR-V, descriptor/camera/continuation ABI and storage layout. SPIR-V disassembly confirms `OpReorderThreadWithHintNV` follows the initial radiance trace, before full shading. No unsupported EXT/hit-object capability is introduced.

`tools/verify_ser_boundary.py` enables the changed order in Slang's CPU target, replacing only the hardware reorder with a no-op. Baseline and candidate pass eight-lane medium stacks 8→0→8 with poisoned storage, 320 material/hemisphere comparisons, held/flame irradiance, opaque/cutout/glass/water/foliage/extinction and known-hit diffuse/metal/glass/water/miss RNG/continuation checks. This verifies transport order, not hardware scheduling equivalence. Final hardware integration additionally passes ENDPOINT and ENDPOINT_FAST captures: 26 finite float layers, nonzero HDR/RR/transmission, observed LATE/FULL/EXACT/OWEN/Wavefront, and normal exit 0 with Windows-MCP focus verification.

One contemporaneous original-bank client and two candidate clients were used, each with one 12-second production block after warmup, all in multi_light at 524x336 input / 1048x672 output and 1 SPP. Original is profile-only; each candidate also captures guides after sampling. No Caustica run was repeated. Compiler and numerical fixture processes completed before these sampling blocks. Matching pose, world clock, terrain signature and render controls pass; invalid/counter frames are excluded and GPU timestamp coverage exceeds 95%.

| GPU P50 ms | Original | Exploratory all-SER | Final Indirect-only | Final reduction |
| --- | ---: | ---: | ---: | ---: |
| World | 15.626656 | 13.952000 | 13.793728 | 11.73% |
| Primary + Guides | 3.715616 | 3.834208 | 3.818464 | -2.77% |
| Transport | 9.697728 | 7.953536 | 7.884704 | 18.70% |
| PT ready for RR | 13.600512 | 11.894304 | 11.802688 | 13.22% |
| B1 | 5.722752 | 4.592512 | 4.555264 | 20.40% |
| B2 | 3.589376 | 2.976480 | 2.903712 | 19.10% |
| RR | 1.178304 | 1.182944 | 1.178464 | -0.014% |

Final P95 also improves: Transport 10.170509→8.459014 ms, PT ready 14.082586→12.393485 ms, World 16.209779→14.493325 ms. The final candidate and original median clock are both 2520 MHz (range 2505–2520); GPU temperature medians are 83/82 C and reported power medians 82.275/80.64 W. The exploratory candidate was hotter and had a lower median clock, yet shows the same Indirect improvement direction. These are short scene-specific observations against one shared original run, not independent repeated statistical estimates or a general FPS gain. Primary timing varies even with identical binaries, so its small difference cannot be attributed to this change. All-stage and Indirect-only candidates are not two independent repeats of one identical complete artifact.

Retain the Indirect-only change under the requested reduced verification scope: substantial B1/B2 and PT/World gains appear in both candidate observations, including the exact final artifact. The experiment changes reorder position, coherence hint and medium-context timing together; it does not isolate which mechanism contributes how much. Hardware cache/spill metrics remain unavailable. Historical Caustica PT ready is 2.214 ms versus the final 11.802688 ms; pass fusion/lighting differ, and performance parity has not been reached.

Final local overlay SHA-256: `f8ce61c543803f92420432fd1aa9d2e604cc5ef785b42e98b4f464e27103f9bc`. Source plus default build selection contain the accepted change; the original installed game is untouched and native libraries remain baseline binaries. Evidence is in workspace `outputs/alpha.57/` and `outputs/alpha.57/final/`: comparison JSON, raw suites/CSV, source/artifact identity, shader/CPU validation, guide ZIPs and verified exit/focus. The source reference is Caustica `trace_ser.slang:traceRadianceReordered`; its HitObject implementation was not copied into the NV path.

## October 14 alpha.58 — accepted ordinary Primary tracing

Caustica `primary.rgen.slang` deliberately uses ordinary TraceRay for the coherent camera/interface prefix, while Indirect uses reorder. VoxelLight adopts that execution split: Primary and Primary-shade use ordinary binaries; Indirect retains alpha.57's pre-decode NV SER. The global SER setting still controls Indirect. Existing Primary `_ser` resource names remain compatible aliases, but their build defines omit RT_SER. No UI option, Java change, material approximation, sampling change or path-budget reduction is introduced.

The experiment initially maps the 20 Primary SER aliases to already validated ordinary counterparts. `tools/build_primary_ser_bypass_experiment.py` checks that the compiler-preprocessed Primary bodies differ solely by the late `ReorderThread` call, verifies binary hashes and revalidates all 110 stages for SPIR-V/ABI/storage layout. Every Indirect binary remains identical to alpha.57 final. After acceptance, default VARIANTS in `tools/build_vulkan_rt.py` are updated and checked against all 110 tested manifest records. The incremental SER builder also resolves legacy Primary SER aliases to their ordinary baseline entry rather than reusing the old SER binary.

One additional multi_light candidate client measures one 12-second production block after warmup, compared with the same-day alpha.57 final observation. Input 524x336, output 1048x672, 1 SPP; FULL/EXACT/CLEAN, LATE, Wavefront, OWEN, QUERY/OMM and Indirect SER stay enabled. Camera/projection/rotation, world clock, terrain signature and renderer controls match. The block passes validity and >95% GPU timestamp coverage; counter frames are excluded. No Caustica client is run.

| GPU time | alpha.57 final P50 / P95 ms | alpha.58 P50 / P95 ms | P50 reduction |
| --- | ---: | ---: | ---: |
| World | 13.793728 / 14.493325 | 12.985344 / 13.668762 | 5.86% |
| Primary + Guides | 3.818464 / 4.654413 | 2.407520 / 3.742462 | 36.95% |
| Transport | 7.884704 / 8.459014 | 8.249984 / 8.620614 | -4.63% |
| PT ready for RR | 11.802688 / 12.393485 | 10.963136 / 11.610826 | 7.11% |
| B1 | 4.555264 / 5.181414 | 4.457840 / 5.098038 | 2.14% |
| B2 | 2.903712 / 3.785632 | 3.145616 / 4.146891 | -8.33% |
| RR | 1.178464 / 1.341734 | 1.179840 / 1.382950 | -0.117% |

Primary improvement is substantial and total PT/World P50 and P95 also improve, so retain this routing under the requested reduced verification scope. Indirect's timing increase is observed despite identical binaries: changed scheduling/upstream data or between-run variation cannot be separated by this window. Do not call it a proven Indirect code regression or conceal it by quoting only Primary. Original/candidate median GPU clocks are both 2520 MHz; original range 2505–2520 and candidate fixed 2520. Median temperatures are both 83 C; reported median powers 82.275/86.95 W. These measurements are one scene and one candidate window against a prior same-day accepted observation, not an independent statistical repeat or a universal FPS improvement. Do not add alpha.57 and alpha.58 percentages.

Integration passes: observed FULL/EXACT/LATE, two guide modes, 26 finite float layers with nonzero HDR/RR/transmission, Windows-MCP focus verification and normal exit 0. Local overlay SHA-256: `aa354738a0739599c1ddc2af8fc3b68304b3dd87a954f733ffdf6dbaf810a143`. Java/native artifacts and all non-Primary shader binaries remain baseline; the installed game is unchanged. The estimator and material formulas are the existing ordinary implementation, so this does not introduce a new numerical tolerance exception. Captures establish data sanity and integration, not temporal equivalence.

Current multi_light PT ready is 10.963136 ms versus historical Caustica 2.214 ms; the remaining gap is approximately 4.95x in that reference, with lighting/pass-boundary caveats. Source and default build selection retain both accepted changes: ordinary coherent Primary and pre-decode SER Indirect. Evidence is in workspace `outputs/alpha.58/`: shader validation/difference proof, raw suites and normalized comparison, guide exports, process/source identity and verified exit/focus. Changes remain uncommitted.

## October 14 alpha.59 — deferred cold-media experiment rejected

Source inspection found that Indirect `loadPath` reads active medium arrays before tracing/reordering, even though alpha.57 constructs the MediumStack context after reorder. The experiment defers those buffer reads until after the first SER using a local-only pending flag. The flag clears on restoration and local `storeMedia`, avoiding stale global reloads on subsequent iterative hits; loadMedia and storePath also handle unadvanced deferred records. No hot/cold/AOV buffer ABI, eight-entry limit, optical field, RNG or sampling budget changes. The local pending flag is not serialized.

Module checks pass: fixed 64B and compact 48B roundtrips retain eight media, proposal identity, RNG and AOV state; the deferred-only test detects stale reload after a local medium update and checks unadvanced storage. Existing poisoned eight-lane stacks, 320 material/hemisphere comparisons, exact visibility and known-hit transport tests pass. CPU fixture scheduling is a no-op and does not establish hardware SER equivalence. The experimental bank rebuilds only 20 Indirect SER variants, reuses 90 after exact preprocessor equality and revalidates all 110 SPIR-V/ABI layouts. Disassembly confirms cold-buffer access chains before the first reorder change from two to zero for FULL/QUERY/SER Indirect. This verifies the intended code movement, not spill or cache cost.

A new glass_water self pair was measured to exercise real medium paths rather than repeating multi_light: original alpha.58 and candidate each use one 12-second production block after warmup. Original is profile-only; candidate then exports ENDPOINT and ENDPOINT_FAST. Both exit normally with Windows-MCP focus verification; 26 candidate float layers are finite with nonzero HDR/RR/transmission. Identical camera/clock/render/terrain controls pass, GPU timestamp coverage exceeds 95%, and counter frames are excluded. No compiler/native fixture work overlaps sampling; no Caustica client is run.

| GPU P50 ms | Accepted alpha.58 | Deferred candidate |
| --- | ---: | ---: |
| World | 10.540576 | 10.615040 |
| Primary + Guides | 1.991584 | 2.005904 |
| Transport | 6.111216 | 6.137136 |
| PT ready for RR | 8.406448 | 8.455216 |
| B1 | 2.187648 | 2.124032 |
| B2 | 2.527904 | 2.583904 |
| RR | 1.182912 | 1.184832 |

The candidate does not accelerate this observation: Transport increases 0.42%, total PT 0.58% and World 0.71%; P95 likewise does not improve. B1's 2.91% reduction is insufficient to accept a net-neutral change when B2 increases 2.22%. Both runs hold 2520 MHz; median temperatures are 75/76.5 C and reported powers 74.55/75.28 W. These small changes are not asserted as a statistically established regression. They give no acceptance basis under the user's requirement that retained optimizations demonstrate acceleration.

Restore alpha.58 production and reject the pending flag/loader changes. All 44 common/world source hashes again match its accepted bank, preserving ordinary Primary and pre-decode Indirect SER. The experiment's source, helper, tests, binaries, raw results and integration captures remain archived locally. Candidate overlay SHA-256: `11d8f4beca8a1cf14970de1d9a39d04a0fe962bf7dd2ba6b66e15ab39daf04f1`.

The measured result does not prove cold media have zero cost; it rejects this particular deferral as an acceleration in glass_water. Accepted glass_water PT ready is 8.406448 ms versus historical Caustica 0.969 ms (nonidentical lighting/pass boundaries), with Transport 6.111216 ms dominating. Further investigation should partition exact visibility/ordered boundary work before another state-copy change. Historical two-traversal counts must not be applied to the current already-single ordered mixed-geometry path without fresh narrowly scoped evidence.

Evidence: workspace `outputs/alpha.59/`: `module-validation.json`, `spirv-medium-boundary.json`, shader difference/validation, paired normalized comparison and raw suites, `e2e-validation.json`, `rejected-source/`, `restoration.json`. No new production optimization is accepted in this round.


## October 14 alpha.60 — opaque EXACT shadow decode experiment rejected

The ordered EXACT visibility path calls terrainExactShadow for each accepted hit. Source inspection found geometric position loads/cross-product normalization before material classification, even for opaque materials that immediately terminate visibility. Flame-owner normal/floor calculation also preceded its flame-source condition. The candidate moves geometric-normal preparation after transmitting-material classification and guards the flame-owner read with the source/geometry flags. Flame ownership still uses the original vertex normal; ordered traversal, Fresnel, absorption, eight-entry media, FULL material semantics, proposal/RNG and path budgets stay unchanged. This is a source-level redundant-work hypothesis, not proof of the hardware compiler's executed cost.

Focused module validation passes existing held/placed lighting, flame self-shell versus independent occluders, opaque/cutout/glass/water/foliage/extinction and known-hit RNG/continuation checks, plus poisoned eight-lane medium stacks and 320 material/hemisphere cases. Terrain decode/native parity passes 32 cases / 1408 components with max normalized error 2.98023e-8. Its normal assertion is restricted to transmitting interfaces, since opaque EXACT normals become unused zero values; the production beauty/visibility output contract does not change. All 110 production stages rebuild and validate SPIR-V, camera/descriptor/storage ABI; 83 binary hashes change and 27 remain identical. No CPU/native build work overlaps sampling.

One additional glass_water candidate is compared with the accepted alpha.58 glass_water observation recorded during alpha.59 earlier today. No new baseline or Caustica client is launched. Both observations use one 12-second production block, identical pose/clock/terrain/render controls, 524x336 input / 1048x672 output, 1 SPP, LATE/FULL/EXACT/CLEAN and Indirect SER. Invalid/counter frames are excluded; timestamp coverage exceeds 95%. ENDPOINT and ENDPOINT_FAST integration captures provide 26 finite float layers and nonzero HDR/RR/transmission. Windows-MCP verifies focus; the client exits normally with code 0.

| GPU P50 ms | Accepted baseline | Candidate |
| --- | ---: | ---: |
| World | 10.540576 | 10.556608 |
| Primary + Guides | 1.991584 | 1.993600 |
| Transport | 6.111216 | 6.123776 |
| PT ready for RR | 8.406448 | 8.425216 |
| B1 | 2.187648 | 2.185312 |
| B2 | 2.527904 | 2.539840 |
| RR | 1.182912 | 1.182208 |

No acceleration is demonstrated: Transport increases 0.21% and PT 0.22%; P95 does not improve. These small differences are not a statistically established regression. Both observations hold 2520 MHz, but median temperatures differ (75 versus 80 C) and reported powers are 74.55 versus 76.515 W. This is a single candidate window against an earlier baseline, with residual between-run variation. Reject the candidate under the requirement to retain demonstrated acceleration; restore the two production shader files and the terrain fixture. All 44 tracked common/world hashes match the accepted alpha.58 bank. Candidate overlay SHA-256: `797a1c4c432e3c8598c126e628506669f70cf36e1eb4a8d8c60778e0185969f0`.

Reusing the baseline's existing 140 counter frames adds a narrow current-workload check: mean surface visibility connections 266542/frame, shadow traversals 366635/frame, ratio 1.3755, and RIS candidates 1109594/frame. Counter instrumentation has aggregate scope, so this ratio is not an isolated per-boundary cost or proof that a particular shadow connection repeats traversal. It does disallow blindly applying the old fixed-two-traversal explanation. Caustica trace.slang/any_hit.rahit.slang uses one any-hit shadow traversal with bucket-level tint/average data; VoxelLight's EXACT ordered media/spatial/Fresnel work is a source-confirmed semantic difference, not a measured attribution of the remaining gap. Copying that simplified attenuation into EXACT would require a separate fidelity decision.

The failed experiment does not prove normal computation has zero cost. It shows this guarded-read/normal-deferral refactor alone does not reduce total cost in the tested scene. The next investigation should count and isolate the cost of exact RIS candidate evaluation/BSDF work before further scalar cleanup, preserving sampling budgets and checking whether source redundancy already disappears in compiled code. High candidate counts alone do not establish a timing bottleneck.

Evidence: workspace `outputs/alpha.60/`: shader difference/validation, focused module validation, baseline-workload counters, normalized self comparison and raw suites/provenance/telemetry, integration captures, rejected source and restoration hashes. No production optimization is accepted in this round; accepted alpha.57/58 remain active. Changes remain uncommitted.


## October 14 alpha.61 — exact local-PMF index implementation (subsequently accepted below)

Following the code-only audit and Caustica proposalPdf comparison, RtLightRuntime now appends a dense cell/section index of the existing float32 local PMFs. The 64B header's formerly unused offset +60 stores the index offset; zero selects the existing scanning fallback. Existing alias-table offsets, samples, PMFs and RNG are retained. The index is bounded to 256KiB and the original 2MiB runtime buffer budget, with no index for empty or oversized cases. Newly allocated zero entries represent sections absent from a cell's local support. Updating/publishing uses the established generation/camera-cell/64-frame-throttled proposal conditions.

runtimeSectionPdf now reads the indexed local PMF when present and retains the original `.2*global+.8*local` expression. Cell zero remains global-only. Local proposal selection was already O(1) and is unchanged; the optimized consumers are cell-local global-proposal PDF queries and emitter-hit MIS. Caustica's closed-form PDF is not copied because its support, weighting and stratified proposal schedule differ. No BSDF, metal, optical interface, candidate count or Owen sequence change is made.

Focused Java tests pass 8 cases, including direct PMF/alias equality, absent local sections, empty sources, negative camera cells, and large-runtime fallback (groups 0/1/17/160/512). Existing production Slang CPU estimator checks run both the original builder/source and candidate over 64 configurations with 200000 samples each: colored/occluded point lights, area/environment sources, adapted proposals, volume/surface, OWEN/SHIFT and four depths. All 409600000 output bytes per version are bitwise identical, SHA-256 `a2dee1e94627307fd5725717f3d66e116dd0cdbf77d98a42bf4b58417d236662`; original alias prefixes differ only at header +60. These checks verify selection/reservoir/surface MIS data, not GPU speed or driver execution equivalence.

All 110 shader stages rebuild and pass SPIR-V, camera/descriptor/continuation/storage validation; 83 binary hashes change, 27 remain identical. Isolated overlay comparison changes only RtLightRuntime and its nested classes plus shaders/manifest; all native binaries remain identical to the previous accepted overlay. The first unrestricted Gradle test attempt triggered compileDlss and failed because the configured /tmp SDK checkout is absent. The successful directed Java test excludes compileDlss/compileOptixDenoiser/compileVulkanRt, with shaders validated separately and established native binaries retained; this does not establish a fresh native release build.

Candidate overlay SHA-256: `45490fd715bedd14fbbdab01a43af5485f7b7841135bb5772a9eb3adaece5bf6`; shader bank `build/alpha61-pmf-rt`, isolated state `work/alpha61-pmf-candidate`. Windows-MCP returned USER_CONTROL before launch. The user explicitly chose to retain code and defer runtime verification. No candidate client was started, no timing window or image capture exists, and no acceleration/end-to-end acceptance is claimed. At that deferral point source/default builds included the pending candidate; alpha.57/58 were the last performance-accepted changes. The installed game is unchanged.

Next verification remains one multi_light candidate window against the existing accepted alpha.58 self reference, plus normal end-to-end/guide checks. Assess B1/B2, total PT and table update cost. If meaningful improvement is absent, archive and restore this candidate; do not add its hypothetical saving to previous accepted percentages. Changes are uncommitted.

Evidence: workspace `outputs/alpha.61/`: module JSON/log and isolated harnesses, directed Java results, shader validation/difference, artifact identity/difference and status.json. Full sample snapshots remain in the ignored build directory for reproducibility. GPU performance and end-to-end verification are explicitly pending.


### alpha.61 runtime verification completed — accepted

The user subsequently authorized runtime testing. Windows-MCP USER_CONTROL initially delayed launch; after release, the same isolated overlay was launched and focused through Windows-MCP, with no manual world entry. One multi_light candidate uses one 12-second production block after warmup, followed by separate ENDPOINT and ENDPOINT_FAST captures. It is compared with the earlier same-day accepted alpha.58 observation; no fresh Caustica or baseline client is run. Camera/projection/rotation, world clock, render controls, terrain signature and section count match; invalid/counter frames are excluded and GPU timestamp coverage exceeds 95%.

| GPU scope | alpha.58 P50 / P95 ms | alpha.61 P50 / P95 ms | P50 reduction |
| --- | ---: | ---: | ---: |
| World | 12.985344 / 13.668762 | 11.829248 / 12.548320 | 8.90% |
| Primary + Guides | 2.407520 / 3.742462 | 2.380384 / 2.871197 | 1.13% |
| Transport | 8.249984 / 8.620614 | 7.107616 / 7.655856 | 13.85% |
| PT ready for RR | 10.963136 / 11.610826 | 9.805216 / 10.443242 | 10.56% |
| B1 | 4.457840 / 5.098038 | 3.819200 / 4.169520 | 14.33% |
| B2 | 3.145616 / 4.146891 | 2.970048 / 3.332346 | 5.58% |
| RR | 1.179840 / 1.382950 | 1.179328 / 1.375194 | 0.043% |

Production counter means remain effectively unchanged: RIS candidates 2279900.58→2279866.79/frame (-0.00148%), visibility traversals 526947.46→526928.77 (-0.00355%), surface connections 526877.84→526860.06 (-0.00338%), surface hits 397764.94→397756.48 (-0.00213%), active radiance queue entries 472838.09→472839.48 (+0.00029%). Queue entries are not all guide/radiance tracing calls. Counter frames are separate from timing. These observations and module parity support unchanged work budgets; the improvement is not obtained by materially cutting candidate/ray counts.

Original/candidate GPU clock medians are both 2520 MHz; original remains fixed 2520 while candidate spans 2415–2520 in the telemetry interval including benchmark warmup. Candidate is hotter (median 86 versus 83 C) with reported power 89.62 versus 86.95 W. Substantial Transport/B1/B2 and PT/World gains, along with improved P95, support retaining this bounded index under the requested reduced verification scope. This is one candidate window against an earlier same-day reference, not an independent repeated causal estimate or a universal FPS gain. Register, spill and cache costs remain unmeasured; shader/compiler scheduling may contribute to the observed total change alongside removing lookup scans. The measurement does not isolate scalar scan cost or guarantee all scene sizes benefit, especially those using fallback.

End-to-end passes: normal exit 0, verified focus, FULL/EXACT/LATE/CLEAN, 26 finite float layers across both guide modes and nonzero HDR/RR/transmission. These captures establish integration sanity, not stochastic/temporal image equivalence. Native libraries and the installed game remain unchanged. Overlay SHA stays `45490fd715bedd14fbbdab01a43af5485f7b7841135bb5772a9eb3adaece5bf6`. Source/default builds retain alpha.61 alongside accepted alpha.57/58; changes are uncommitted.

Current multi_light PT ready is 9.805216 ms versus historical Caustica 2.214 ms, approximately 4.43x with different lighting/pass-boundary caveats; parity is not reached. Next priority remains FULL material-specific evaluation and/or exact shadow traversal, with separate experiments rather than adding hypothetical savings. Evidence now additionally includes `outputs/alpha.61/self-comparison.json`, paired raw suites/normalized CSV and telemetry, `workload-comparison.json`, capture archives and `e2e-validation.json`. Status.json records acceptance.

### alpha.62 — Exact rough-diffuse angular cancellation: not accepted (2026-10-15)

Source comparison reconfirmed that Caustica `lighting.slang::evalSampleContrib` uses Lambert diffuse plus isotropic GGX/Schlick, while FULL VoxelLight evaluates Oren–Nayar, exact Fresnel, anisotropic GGX, multiple-scattering compensation and the BSDF PDF. This establishes additional work, but does not measure each feature's GPU cost. Copying Lambert would change FULL semantics. The isolated candidate instead cancelled Oren–Nayar's sine/azimuth factors algebraically, preserving both the near-pole azimuth clamp and the clamped tangent denominators. Metal, dielectric, path budgets, RIS selection and the accepted alpha.61 PMF index were retained. No Caustica code was copied.

The actual Slang CPU target passed native transport parity with and without multiple scattering (57,600 cases each); maximum normalized baseline/candidate difference was 1.1921e-7. A separate 1,048,576-direction angular fixture, including 4,096 pole/grazing/clamp cases, remained finite with maximum normalized error 2.1908e-7. All 110 production SPIR-V variants and buffer layouts validated; 83 shader binaries changed. Two representative FULL shaders reduced static FMax counts by six and FMin counts by three, but retained 163 static Sqrt instructions and grew by 68 bytes. These static counts neither establish executed instruction counts nor register/occupancy gains.

One isolated multi_light client was launched and focused using Windows-MCP. It exited normally with FULL/EXACT/LATE/CLEAN, two separate guide captures and 26 finite float layers, with nonzero HDR/RR/transmission. Only 83 shader entries plus their manifest differed from the accepted alpha.61 overlay; Java and native libraries were identical. Candidate SHA256: `04d2b152900478a2f98133ec35bfdc406f36d41b7126657da2327fa0b06ebdbc`.

The historical acceptance check rejected the comparison: terrain signature changed from `1417246207890208711` to `8747807857720345800`, and scene bytes from 5,453,040 to 5,464,560 despite matching camera, world time, fixture anchors, 40 sections and render controls. Geometry versions are hashes of actual triangle bytes (`RtGeometryStream.geometryVersion`), so this is not merely a different update sequence number. The precise geometry difference is unresolved. Telemetry over the benchmark interval including warmup also differs: clock median 2520→2445 MHz (candidate range 2325–2505), temperature median 86→87 C and power median 89.62→83.45 W.

| Diagnostic scope | Historical alpha.61 P50 ms | Candidate P50 ms |
| --- | ---: | ---: |
| World | 11.829248 | 12.851040 |
| Primary + Guides | 2.380384 | 2.521216 |
| Transport | 7.107616 | 7.820864 |
| PT ready for RR | 9.805216 | 10.765376 |
| B1 | 3.819200 | 4.148672 |
| B2 | 2.970048 | 2.994336 |
| RR | 1.179328 | 1.225952 |

Counter frames were excluded from timing; mean workload changes were below 0.005% for RIS, surface hits/connections, shadow traversals and active radiance queue entries. This does not override the geometry/thermal mismatch. The table is an observation, not an isolated regression estimate or an acceptable speedup. Acceleration was not demonstrated; no repeat client or fresh Caustica run was added. Candidate source and patch are archived under `outputs/alpha.62/rejected-source`; production source was restored and every common/world source hash matches the accepted alpha.61 shader manifest. The installed game is unchanged.

This closes the small angular-rewrite hypothesis without accepting an unproven optimization. It provides no evidence that Oren–Nayar alone explains the remaining Transport gap. Next code work should target the larger per-candidate FULL material context/branch footprint, with exact coefficients and protected material paths preserved. Any future GPU acceptance still requires matching geometry and comparable clocks; a mismatched historical run must remain diagnostic. Evidence: `outputs/alpha.62/{status,source-audit,module-validation,angular-boundary-validation,shader-validation,compiler-static-counts,artifact-difference,self-comparison,workload-comparison,e2e-validation,restoration}.json`, raw suites/CSV/telemetry and both capture archives. Source changes remain uncommitted.

### alpha.63 — FULL RIS receiver specialization: slower, reverted (2026-10-15)

The inspected call chain is `advanceHitCore → selectDirectPrepared → directReceiverAccept/directIntegrand → evalBsdf → evalPreparedLocal`. A surviving surface candidate still passes through general material-type, interface, conductor and coat decisions, whereas Caustica's `risInitial → evalSampleContrib` accepts a smaller set of surface parameters and evaluates Lambert/isotropic GGX/Schlick. FULL's preparation is already hit-local; it does not repeat the eight-point Fresnel quadrature for every candidate. Source struct size alone does not establish physical copies or register pressure after compiler optimization.

The candidate classified uncoated ROUGH_DIFFUSE receivers before the proposal loop and instantiated a dedicated Slang generic loop with a narrow `RoughDiffuseBsdf` context. Its exact anisotropic GGX, dielectric Fresnel, Oren–Nayar, multiple-scattering, PDF and MIS formulas were retained. Metal/interface/coat, volume, diffuse-cache and SIMPLE cases used the original general path. Proposal budgets, RNG, reservoir arithmetic and selection rules were unchanged. Generated Slang C++ contains separate general/rough receiver loops; the rough loop has no generic BSDF calls and its evaluation accesses no conductor, dielectric-interface, coat or complete Material data. This is specialization evidence, not a hardware register or spill measurement. All 110 production SPIR-V variants and ABIs validated. A representative Primary FULL binary grew from 403,388 to 439,784 bytes (+9.02%) due to the extra specialized code.

Actual Slang CPU RIS outputs were bitwise identical over 524,288 samples (64 configurations × 8,192), including RNG/sequence, eight material groups, anisotropy/roughness/F0, multiple scattering, terminal/cache/volume fallbacks, point/area/environment/adaptive proposals, depths and OWEN/SHIFT. This RIS fixture uses an outgoing direction along the surface normal; it is not a full arbitrary-view-angle equivalence proof. Existing native transport regression also passed with bitwise baseline equality with and without multiple scattering, but does not route through the new receiver. There was no new material approximation or copied Caustica implementation.

To avoid alpha.62's cross-run geometry mismatch, one isolated multi_light client ran three 12-second production windows, A–B–A. A loads byte-identical accepted alpha.61 material stages through existing `_ggx_inline` resource aliases; B loads candidate CLEAN stages. GGX_INLINE is a diagnostic resource selector here, not a different GGX algorithm/storage mode. All alias hashes were checked. Only the isolated overlay's benchmark-plan class schedules these three windows; production Java sources/classes remain unchanged. Captures restore CLEAN and use the candidate. Diagnostic overlay SHA256: `373fd952ef1c5d30ee0cb7042eccfc3897f5e3c4d2647aee29c9217c99762346`.

| GPU scope | A1 P50 ms | B P50 ms | A2 P50 ms | B vs mean A P50 |
| --- | ---: | ---: | ---: | ---: |
| World | 13.134496 | 13.552240 | 12.492624 | 5.76% slower |
| Primary + Guides | 2.376832 | 2.503840 | 2.379136 | 5.29% slower |
| Transport | 8.348416 | 8.815104 | 7.770160 | 9.38% slower |
| PT ready for RR | 11.128352 | 11.553840 | 10.538160 | 6.65% slower |
| B1 | 4.539264 | 4.980976 | 4.101056 | 15.30% slower |
| B2 | 3.142880 | 3.436768 | 2.952480 | 12.77% slower |
| RR | 1.181056 | 1.176896 | 1.179376 | 0.28% lower |

All blocks passed benchmark validity, with GPU timestamp coverage above 95% after excluding counter frames. Terrain signature `1417246207890208711`, 40 sections and 5,453,040 scene bytes match across blocks and sampled resource states. Camera, projection, rotation, world time and execution controls match except the documented shader resource selector. Mean counter changes versus the two A windows are below 0.001% for active radiance queue entries, shadow traversals, surface connections, RIS candidates and surface hits. Estimated sample-window clock medians are 2505/2520/2520 MHz, with B fixed at 2520; temperature medians are recorded in the raw report. A2 briefly drops to 2385 MHz, yet is faster than B. Transport A-window drift is 7.18%, so the 9.38% figure is descriptive, not a repeated causal estimate. B is slower than both A windows for Transport and PT, with worse P95 as well; it cannot pass acceleration acceptance. RR remains approximately unchanged.

Two separate ENDPOINT/ENDPOINT_FAST captures passed all 26 finite float-layer checks, with nonzero HDR/RR/transmission. The stdio focus helper failed; the main task successfully verified foreground using installed Windows-MCP directly. The completed benchmark, driver shutdown, saved dimensions and disappeared owned PID were observed, but a later sandboxed process-handle observer failed to open the process: the exit code is **unverified**, not assumed zero. This limitation is preserved in `e2e-exit.json`; no additional client was launched to obtain it.

The candidate is not retained. Both changed shader sources were restored; 44 normalized common/world source paths were hash-checked against the accepted alpha.61 manifest. Candidate sources/patch are archived under `outputs/alpha.63/rejected-source`. The isolated diagnostic plan/aliases are not part of normal builds; the installed game is unchanged. alpha.61 remains the accepted production source baseline.

Conclusion: moving these branches/state into a dedicated loop is not a demonstrated performance fix. Larger shader footprint is a concrete tradeoff; instruction-cache, scheduling, register and spill explanations remain hypotheses without hardware metrics. The experiment does not isolate the cost of required Fresnel/GGX/energy lookups, and does not prove all BSDF work is cheap. Do not expand this specialization or repeatedly shrink structs based on source size. Next separate investigation can follow the existing EXACT visibility/closest-hit handoff plan; any further BSDF change should identify a particular repeated operation/load rather than general branch/state size. Evidence: `outputs/alpha.63/{status,module-validation,bsdf-regression,compiler-specialization,self-comparison,workload-comparison,e2e-validation,restoration}.json`, raw per-block CSV/telemetry/suite, both captures, generated rough-loop excerpt and reproducible diagnostic scripts. Changes remain uncommitted.


### alpha.64 — Why alpha.63 regressed: loop-sharing intervention (2026-10-15)

The alpha.63 report established regression, but not its mechanism. A focused follow-up keeps its exact narrow BSDF evaluator and switches from duplicated generic/specialized RIS loops to one shared candidate loop. Actual Indirect SPIR-V A/B/C sizes are 418448/454880/427752 bytes, instruction counts 24654/26750/25217 and static loop-merge counts 54/59/54. None contains aggregate Material/PreparedBsdf types: source struct size was not evidence of a whole-material memory copy per candidate. This does not prove all material predicates or register costs were eliminated. GGX Function-array declarations/stores remain 24/28/28; these are compiler structure, not measured driver spills.

One isolated client ran A–B–C–A, 8 seconds each, with verified binary aliases: A=accepted alpha.61, B=alpha.63 duplicated loop, C=same narrow evaluation with shared loop. All sampled GPU clocks were 2520MHz, geometry and resource samples match, workload mean changes are below 0.002%, and all blocks are valid. Transport P50 is 8.325280 / 8.867440 / 7.893248 / 7.770064 ms; PT ready is 11.047536 / 11.605104 / 10.647200 / 10.529248 ms. C removes most B regression (Transport -10.99%, PT -8.25% against B), supporting the duplicated-loop/control-flow code-generation change as an important regression contributor. Baseline Transport drift is 6.90%, there is no inverse-order repeat, and C does not beat A2, so this is not a precise single-factor hardware attribution or a production speedup. Registers, spill, instruction-cache stalls and occupancy remain unmeasured.

The actual Slang CPU RIS comparison is bitwise over 524288 samples; an added 262144-case rotated-frame/view-angle/grazing/anisotropy/F0/multiscatter comparison between general and narrow evaluation is also bitwise and finite. C compiles/validates only the two runtime-used diagnostic stages; A/B retain their previously validated full banks. No new production code or default bank is accepted. Windows-MCP controls launch/focus and immediate owned-handle observation confirms exit code 0. No Caustica client or repeated quality captures are run. All 44 accepted common/world source hashes remain unchanged.

The Caustica comparison also corrects the causal premise: there is no measurement proving this specialization made Caustica faster. Its existing candidate evaluator does less work (Lambert/isotropic GGX/Schlick, no matching multiscatter or BSDF-PDF work) and decodes packed Light.le instead of fetching spatial face-emission textures. Retaining VL FULL math and emission while copying its candidate loop is not the same implementation. These source differences do not individually explain measured milliseconds. Detailed Chinese analysis, limitations and reproduction: [ALPHA-64-RIS-REGRESSION-CAUSE.md](performance/ALPHA-64-RIS-REGRESSION-CAUSE.md). Evidence: workspace outputs/alpha.64, including compiler structures/disassemblies, selector hashes, numerical checks, raw four-window suite/CSV/telemetry, B-to-C source patch, diagnostic scripts and unchanged production identity.

### alpha.65 — Exact sRGB preparation: no demonstrated acceleration, reverted (2026-10-15)

Following Caustica's prepared packed light radiance, this experiment replaces only VoxelLight's repeated 8-bit sRGB transfer with a 256-float table in the existing asset buffer. Spatial emission, cutout and FULL/EXACT math remain unchanged. Actual Java-table/Slang arithmetic comparison is bitwise across 4096 colors covering all channel values; native terrain fixtures are bitwise between versions. All 110 SPIR-V/ABI checks pass. Primary/Indirect static Pow counts fall 59→5 and binaries shrink 7208 bytes each; these are not executed instruction or hardware-stall measurements.

One isolated 8-second A–B–A multi_light client compares accepted alpha.61 binaries against lookup. Geometry/controls match, sampled clocks are all 2520 MHz, maximum workload mean difference is 0.001944%. Transport P50 is 8.260768 / 8.159968 / 7.828320 ms; PT ready is 10.991040 / 10.889120 / 10.613088 ms. B does not beat both A windows, and A drift exceeds B-versus-mean differences. No acceleration is established; no fresh Caustica or repeat client is run. Added data dependencies versus removed arithmetic are a possible explanation, not a measured hardware attribution.

After all benchmark blocks and the suite ZIP completed, native pipeline destruction hung. Two thread dumps approximately a minute apart show vkDestroyPipeline in VulkanRtPipeline.destroy; the underlying cause and any relationship to this candidate remain unresolved. Only the owned isolated PID was force-ended through Windows-MCP after creation-time verification; exit 4294967295 is observed. Quality captures and post-suite pose are missing, so end-to-end success is not claimed. Candidate sources/classes are archived and restored from pre-experiment snapshots; all 44 common/world source hashes match alpha.61. Production remains alpha.61. Details: [ALPHA-65-SRGB-TRANSFER.md](performance/ALPHA-65-SRGB-TRANSFER.md); raw evidence and reproducible scripts: workspace outputs/alpha.65.

### alpha.66–67 — Scoped latency identifies shadow handoff; inline closest Query accepted (2026-10-15)

A sparse Device shader-clock diagnostic measures actual function invocation elapsed latency, calibrated to approximately 1ns/tick with coarse 1024ns quantization. Two sampling densities produce consistent rankings: BSDF eval about 2.9–3.0us, candidate emission about 2.4us, shadow TraceRay closest-hit about 44–45us, radiance TraceRay about 5.3us. These are inclusive latency observations with instrumentation overhead and overlapping invocations, not additive per-frame GPU milliseconds. Details and unit/overhead limitations: [ALPHA-66-SCOPED-GPU-LATENCY.md](performance/ALPHA-66-SCOPED-GPU-LATENCY.md).

Caustica skips shadow closest-hit and terminates early; VL EXACT still used ordinary closest-surface TraceRay despite QUERY being selected for other visibility work. The accepted change in material_transport.slang uses inline closest Query only for shadow traces, preserving cutout/OMM and instance/geometry/primitive mapping. The original ordered 24-boundary, eight-medium, Fresnel/extinction and flame-source logic is unchanged; radiance and unsupported-Query fallbacks retain TraceRay. This learns from Caustica's lighter handoff without adopting its approximate transparency model.

GPU replay checks 40960 real glass/water shadow rays, including 8349 transmission hits and 133 cutout-flagged geometry hits: zero mismatches at distance/barycentric tolerance 1e-5 and exact triangle/instance IDs. Pure candidate captures pass 26 finite float layers with nonzero HDR/RR/transmission and exit 0. A separate stable multi_light client runs pure A–B–A, 8 seconds each: Transport 7.573728 / 3.531552 / 7.206400ms (-52.21% against mean A); PT ready 10.377664 / 5.510592 / 10.064640ms (-46.09%); World 12.523520 / 7.599104 / 12.243968ms (-38.64%). P95 improves too, same geometry/controls/resources, maximum workload mean change 0.009681%, sampled clock medians 2520/2505/2475MHz. Counter frames are excluded; pure A/B raygens have neither internal clocks nor parity capture. The containing bank retains diagnostic classification. The client exits 0. Glass/water also improves strongly but dynamic sceneBytes varies, so it is diagnostic rather than the strict timing acceptance baseline.

All 110 final production SPIR-V stages and ABIs validate; the two active final stage hashes are byte-identical to timed B. Default build/vulkan-rt is updated; one common/world source changes relative to alpha.61, no production Java/native changes and no installed-game replacement. No Caustica client is run. Historical Caustica multi_light PT ready remains about 2.214ms, so parity is not claimed. A/B share the same per-process implicit-layer disable; unrelated native-destruction hang root cause remains unresolved. Detailed evidence/limitations: [ALPHA-67-EXACT-SHADOW-QUERY.md](performance/ALPHA-67-EXACT-SHADOW-QUERY.md), workspace outputs/alpha.66 and outputs/alpha.67. Changes remain uncommitted.

### alpha.68 — Remaining costs after shadow Query: diagnosis only (2026-10-15)

One 12-second-per-window multi_light A–P–A client brackets a sparse shader-clock diagnostic with byte-identical accepted alpha.67 stages. Pure baseline PT is 5.465888/5.499760ms, Transport 3.520080/3.515088ms; per-frame B1+B2 share is 94.39/94.31% of Transport, while independent Guides is about 0.064ms. Invocation elapsed latency is about 4.6us for proposal geometry/alias/PDF, 3.2us for BSDF eval, 1.9us for candidate emission and 2.8us for shadow Query. Environment sampling is about 2.5us for an estimated 0.455M calls/frame; directional preparedMultiscatter about 0.36us, excluding hit-local energy preparation. These overlap and include child instrumentation; they cannot be multiplied into additive frame milliseconds. Normal-frame instrumentation adds about 5.24% PT overhead; sampled counter frames add further overhead. A2 has a roughly 4.07s CPU hitch and telemetry timeout, so sustained FPS and complete clock coverage are not claimed. Geometry is stable within this run but differs from older observations; no cross-run causal latency ratio is claimed.

Source audit corrects the current Caustica Light record to 32B (Java writer 8 floats plus matching Shader ABI), rather than the old 48B note; VL is 64B. This does not establish a cache/bandwidth bottleneck. VL also has environment RIS candidates where Caustica uses emitter-only RIS and sky continuation. More concretely, accepted Indirect SPIR-V retains two atan2/acos direction-to-texel mappings for the same surviving environment candidate, first for PDF and then radiance. The next minimal experiment can cache that actual direction-derived index in the existing environment candidate field, retaining RNG/PDF and zero-power fallback; it must not substitute the initially sampled CDF cell at rounding boundaries. No algorithm change is implemented in this audit. All 44 accepted sources, 293 production classes and the default 110-stage bank are unchanged; the isolated client exits 0. Full evidence, raw timing/frequency, compiler SSA excerpts, limitations and next experiment: [ALPHA-68-REMAINING-COSTS.md](performance/ALPHA-68-REMAINING-COSTS.md), workspace outputs/alpha.68.

### alpha.69 — Exact environment texel reuse: acceleration not established (2026-10-15)

The candidate reuses the actual direction-derived PDF texel in the existing environment candidate record for radiance, preserving zero-power fallback. Actual Slang CPU environment 262144 and full RIS 524288 cases are bitwise, including complete RNG state. Two active SPIR-V stages validate; the direct-load branch is present but binaries grow 1048B each. One 8-second A–B–A multi_light client yields Transport 3.476608 / 3.462496 / 3.504800ms and PT 5.418944 / 5.404192 / 5.447008ms. Changes of 0.81% / 0.53% fail the predeclared max(2%, baseline drift) threshold; P95 does not improve. Workload, geometry and controls match; median GPU clocks are 2520/2520/2505MHz. Two captures pass 26 finite float layers and normal exit 0. Candidate remains isolated, with all 44 source / 293 class / 110 default-stage identities unchanged from alpha.67. Details and evidence: [ALPHA-69-ENVIRONMENT-CELL-REUSE.md](performance/ALPHA-69-ENVIRONMENT-CELL-REUSE.md), workspace outputs/alpha.69. Next intervention targets the exact CDF dependent search chain, without removing environment proposals.

### alpha.70 — Exact two-step CDF search: acceleration not established (2026-10-15)

The isolated candidate executes two original upper-bound decisions from three independent CDF loads, preserving RNG/PDF and all other paths. SPIR-V confirms the three loads occur before the branch; for 128×256 search, logical rounds fall 15→8 while prefix loads rise 15→22. This is not a hardware transaction/latency measurement. Actual Slang CPU environment 2097152 cases (including prefix/adjacent floats, small/odd sizes, sparse/HDR/zero power) and full RIS 524288 cases are bitwise with complete RNG. Two active stages validate, growing 2952B each. One valid 8-second A–B–A yields Transport 3.474816 / 3.497792 / 3.520000ms and PT 5.385728 / 5.419264 / 5.446752ms. B is between the baselines and no acceleration is established. Geometry/controls/resources match; maximum workload mean difference is 0.001259%. GPU clock medians 2520/2505/2467.5MHz and late minimum2385MHz limit fine attribution. Two captures pass 26 finite layers; normal exit0. An earlier premature launch is archived separately and contributes no measurement. All accepted 44 sources, 293 classes and 110 default stages remain alpha.67. Stop stacking environment microchanges; next audit targets specific emitter alias/node/record dependency chains without assuming record stride equals measured bandwidth. Details: [ALPHA-70-EXACT-CDF-SEARCH.md](performance/ALPHA-70-EXACT-CDF-SEARCH.md), workspace outputs/alpha.70.

### alpha.71–72 — Emitter leaf alias targets: two minimal interventions rejected (2026-10-15)

Actual accepted Indirect SPIR-V retains threshold → alternate-slot → chosen-column ID → record. Caustica selectSectionLight uses section-contiguous records and derives firstLight+localIndex; VL records are globally triangle-sorted for hit-PDF search. The experiment stores exact alternate light ID in the existing leaf alias's unused duplicate-PMF fourth word; record conditional PMF and section/global/grid tables remain byte-identical. No allocation/upload/RNG/feature change. Baseline A and candidate B share the new Java writer, with old-shader compatibility verified separately.

Alpha.71 loads threshold/self/alternate words independently before selecting ID. It removes chosen-column indirection and shrinks both active stages 224B, but three unconditional loads add a word on self paths. One 8-second A–B–A gives Transport 3.488320 / 3.495744 / 3.472960ms and PT 5.417312 / 5.428640 / 5.398528ms; all 21 sampled GPU clocks are2520MHz. No acceleration. Alpha.72 controls for that tradeoff: select +8/+12 field address and read only the chosen ID, two alias word Loads on either path, no chosen-column lookup; active stages shrink384B vs baseline. Its one final 8-second A–B–A gives Transport 3.498880 / 3.519296 / 3.509136ms and PT 5.442240 / 5.452144 / 5.466432ms. Clock medians2520/2520/2505MHz, minimum2475MHz. No acceleration is established; neither version meets the predeclared max(2%,baseline drift) condition. This verifies compiler structure, not hardware stall attribution or a claim that all proposal access is cheap.

Actual Java-built tables across0/1/2/17/160/512 sections only differ in leaf fourth words; actual Slang24896 alias boundary/endpoint cases and524288 FULL RIS cases are bitwise including full RNG. Alpha.72 reuses already verified old/control snapshots and runs only its changed candidate. Each client has valid matching geometry/resources/control states, workload mean differences below0.0011%, two separate captures with26 finite layers and normal exit0. All44 accepted common/world sources,293 production classes and110 default stages remain alpha.67. No fresh Caustica client, no installed-game replacement, no commit/push. Stop this leaf-level intervention; section-span self/alternate range metadata bypassing node is a separate untested hypothesis requiring exact PMF/adaptive-bucket preservation. Details: [ALPHA-71-LEAF-ALIAS-CHAIN.md](performance/ALPHA-71-LEAF-ALIAS-CHAIN.md), [ALPHA-72-LEAF-SELECTED-WORD.md](performance/ALPHA-72-LEAF-SELECTED-WORD.md); workspace outputs/alpha.71 and outputs/alpha.72.


### alpha.73 — RR history during terrain updates and native sky color (2026-10-15)

Quality fix on the accepted alpha.67 shader baseline. RR previously globally reset and zeroed motion whenever terrainGeneration changed, including streaming/rebuilds. Caustica only initializes resetHistory on a fresh feature. VL now preserves RR history across ordinary terrain updates, retains existing lifecycle/light invalidation and adds large camera-cut reset. One valid isolated legacy/fixed diagnostic observes 29/29 terrain events resetting versus 0/23; fixed history reaches2248 frames and actual nonzero motion is retained. Camera cut resets once. Eight captures pass104 finite float layers; owned client exits0 with Windows-MCP focus verified. The first preliminary run lacked fixed-phase terrain changes and is not used for this comparison. No image noise percentage or FPS gain is claimed.

Main-world PT sky now uses linearized Minecraft skyColor with no procedural stars/clouds; sun/moon direct light remains. Four LightingEnvironment tests pass; night and camera-cut capture complete, daytime outdoor image matching remains unmeasured. Production alpha.73 includes only four changed classes and the sky resource; all110 alpha.67 SPIR-V stages and complete native payload are retained, and diagnostic-only overrides are excluded. Installed into the requested Fabric26.2 client after backing up/disabling alpha.67; other mods/settings preserved. Details: [ALPHA-73-RR-HISTORY-NATIVE-SKY.md](performance/ALPHA-73-RR-HISTORY-NATIVE-SKY.md); workspace outputs/alpha.73 and outputs/client-install-alpha73.


## alpha.74–75：两项热点干预均未通过性能验收（2026-10-15）

已验证Caustica式section span：精确self/alternate PMF和leaf range/bucket绕过node；230808组选择和524288组完整RIS/RNG逐位一致。一次A–B–A的Transport3.471488/3.481120/3.499904ms、PT5.407360/5.447056/5.424848ms，不采纳。新增36B span和最长256KiB footprint未获得净收益，不再追加alias布局小改。

随后只对有严格正PDF的无coat普通粗糙漫反射太阳连接延后完整BSDF，学习Caustica遮挡后才求BRDF的顺序。262144组实际连接循环的辐射、AOV、RNG、shadow次数逐位一致，但阶段代码各增加21476B；一次A–B–A的Transport3.441664/3.476608/3.456128ms、PT5.348192/5.374432/5.379872ms，仍不采纳。未测得硬件cache/register/stall，不能把未加速唯一归因于代码尺寸。

两客户端各有26层有限捕获和正常退出0，几何/资源匹配、主要射线工作量差低于0.001%，后段轻微降频已记录。生产仍保留alpha.73画质修复和alpha.67着色器，两个候选均隔离；没有重跑Caustica或替换安装游戏。详见docs/performance/ALPHA-74-SECTION-SPAN.md、ALPHA-75-SUN-BSDF-DEFER.md及工作区outputs/alpha.74、alpha.75。下一项单独检查radiance最近命中交接，先做GPU命中对照，不能从微秒差直接承诺帧收益。


## alpha.76：radiance Query 未证实加速（2026-10-15）

复用已接受的最近命中Query helper到radiance，保留全部payload/flags/mask、cutout/OMM和pre-decode SER；Caustica在此仍用TraceRay，此项依据VL自身剩余调用计时提出。一个客户端先做GPU命中replay，34048条实际radiance射线/27946hits零不匹配（透明3、cutout0），随后纯A–B–A的Transport3.452240/3.486336/3.552352ms、PT5.366592/5.409120/5.524416ms。没有快过两端A；最后段GPU中位时钟2430MHz，相对起始2520MHz超出3%一致性范围。无法验收加速，也不据此判定一定变慢；不追加测试。两份捕获26层有限并正常退出0。

已核对执行revision触发context重建、GGX_INLINE资源别名及包含实际binary的pipeline-cache键；没有发现A/B误用同一stage。alpha.74/.75/.76均隔离、不保留；用户验证的alpha.73画质、alpha.67生产着色器及安装JAR哈希不变。本轮未取得新的性能提升，Caustica约2.214ms PT目标仍未达到。下一轮应先取得完整候选准备/FULL BSDF的成本上界或可用硬件shader指标，停止以单次调用延迟推断整帧收益。详见 performance/ALPHA-76-RADIANCE-QUERY.md（同目录内为ALPHA-76-RADIANCE-QUERY.md），原始证据outputs/alpha.76。
# 2026-10-17：alpha.77–80 当前状态

本轮新增五场景当前基线、实际输入/冻结资源 GPU 回放、独立离线 Vulkan Compute 执行器；两项性能候选均不采纳。生产继续用户确认的 alpha.73 质量修复和 alpha.67 shader，未提交推送、未替换安装客户端。

- [alpha.77 当前五场景基线](performance/ALPHA-77-CURRENT-BASELINE.md)：PT 约 2.742/5.293/5.569/3.966/3.240ms，均未达到历史 Caustica×1.2 预算。后段降频单列。
- [alpha.78 精确 Owen 类别提前判定](performance/ALPHA-78-OWEN-PREFIX.md)：模块逐位对照通过，总 PT 改善仅 0.26%，不保留。
- [alpha.79 冻结工作块回放](performance/ALPHA-79-FROZEN-BLOCK-REPLAY.md)：捕获 386 个实际表面的三个完整函数参考输出，raygeneration 回放通过 1e-5；独立 Compute 入口有一个 PDF 分量差 1.27e-5，离散/RNG 一致。时间只作诊断排序，不能累加成帧成本。
- [alpha.80 首两层融合](performance/ALPHA-80-TWO-BOUNCE-FUSION.md)：同频率下 PT 退化 16.31%，不保留。

下一项只能基于完整候选/材质回放验证更具体的改法。不能继续把失败的 alias 微调、Owen 减算或两层循环融合描述为待实施的确定收益，也没有证据将剩余差距唯一归因于硬件寄存器或缓存。独立执行器入口为 `tools/replay_lighting.py --help`；纯生产当前 110-stage bank 与安装包身份已复核。

## alpha.81–85：候选/FULL BSDF 分拆与环境 CDF 干预

实际捕获展开为2217个候选，18个独立入口保存并消费几何、发光、f/PDF/MIS与RNG；新增完整RIS内的单项缓存替代用于排序，不能当作可兑现帧收益或严格成本上界。证据支持先处理候选准备、尤其环境CDF路径。Caustica emitter RIS没有同口径环境候选，普通材质/发光颜色也更轻，不能直接复制来宣称等价。

alpha.82共享已确认编译后重复的D、alpha.83合并精确发光UV/tint/flags到96B记录，都没有完整RIS收益，不进游戏。alpha.85精确CDF索引离线约改善4%，一个有效客户端的完整A–B–A在2520MHz下PT5.402336/5.290784/5.409280ms，改善2.13%；Primary改善5.57%，World包含GPU构建后改善1.09%。低于预设3%门槛，不保留，也不补跑有效实验。生产61项默认源、293个class、110个stage和alpha.73安装SHA均保持一致。

便携工具：`tools/build_lighting_blocks.py`、`tools/prepare_lighting_candidates.py`、扩展后的`tools/replay_lighting.py`和`tools/validate_lighting_blocks.py`。生成入口与18阶段已实际运行，生成表逐位一致；原golden跨入口最大归一化误差1.09704e-4，离散/RNG一致，严格1e-5未全通过，用户1e-2通过。验证器额外检查非有限组件，注入Infinity的负控制被正确拒绝。

详情见 [alpha.81工作块报告](performance/ALPHA-81-CANDIDATE-BLOCKS.md)、[alpha.85游戏实验](performance/ALPHA-85-CDF-GUIDE.md) 和 [最新性能差距报告](performance/VOXELLIGHT-VS-CAUSTICA-PROFILING.md)。未重新测试Caustica、未安装、提交或推送。仍未达到历史Caustica×1.2预算。

## alpha.86：原 RNG 定位 CDF，实际 PT 改善 3.89%，已保留

在完整RIS内拆分行/列搜索后，改用同一次LCG更新后的高8位定位精确CDF区间。单调性保证原upper-bound结果仍在区间内，消除alpha.85的value/total重定位、额外端点读取及回退分支。原RNG、方向、PDF、BSDF/MIS及候选预算不变；同帧GPU producer及复制开销计入World。

1809156组浮点契约和1048576个真实CDF GPU种子检查零差异；离线完整RIS0.077856/0.069952/0.077984ms，2535MHz同频，改善10.23%。一次multi_light游戏A–B–A为PT5.405552/5.202816/5.421216ms，改善3.89%；Primary7.60%、Transport2.50%、World2.45%，PT/World P95下降。频率中位数差0.60%、工作量最大差约0.00283%，通过既定门槛。

完整110-stage构建、Java编译和8项相关检查通过。画质失败/排除情况没有隐藏，匹配场景的6份运动/恢复捕获及独立数值证据支持有限范围保留；不是同随机帧clean-reference认证。生产源码与默认bank现包含此项，完整alpha.86包保留原native及alpha.73画质修复，未安装或推送。下一项调查完整发光候选的数据依赖，不继续无依据CDF或算术微调。仍需约48.94%的PT降低才能接近该场景预算，其他场景未重新测量。详见 [完整报告](performance/ALPHA-86-RNG-CDF-GUIDE.md)。
