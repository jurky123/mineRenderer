# 0.38 Physical Scene Integration — work and acceptance ledger

**0.38.0-alpha.1 is an incomplete first implementation increment, not completion of the 0.38 review.** No NVIDIA device or display is available on the build host. No new GPU baseline, screenshot, VRAM measurement or visual acceptance is claimed.

## Implemented in this increment

Opaque primary glossy transport now samples the existing RT directional/environment/emissive/held-light distribution. The sun candidate is rejected because raster still owns primary sun/moon direct. Local/emissive/environment candidates evaluate `evalGlossy`, trace RGB visibility and use power-heuristic MIS. The complementary primary BSDF path starts with its actual sampling PDF rather than being classified as a delta camera ray. Raster held specular is disabled only when this RT estimator owns it. `/voxellight rt_primary_glossy_nee on|off` provides an independent A/B, resets reference accumulation, and appears in the command-derived vanilla settings UI. The old off path is a comparison, not a correctness reference.

Coated diffuse RT substrates now contain diffuse plus one authored coat interface, without an additional base plastic lobe. Full and glossy sampling/evaluation/PDF were changed together. This remains a bounded thin-layer approximation, not internal-layer random walk or multiple-scattering GGX.

Triangle hit payloads retain UV-derived tangent/bitangent. Transport uses the projected tangent frame for anisotropic GGX; degenerate UVs use a stable fallback. Geometric normals come from triangle geometry, separately from interpolated/normal-mapped shading normals. Back-facing mapped normals fall back to the geometric normal. This is not the complete requested shading-normal energy correction.

## Reproducible GPU baseline (not yet captured)

Keep a copy of 0.37.6 and its native kit. Capture 2560×1440, the same world/coordinates/camera/FOV, fixed render distance and simulation distance, identical resource packs, weather/time, GPU power mode and driver. Record both realtime RTX and stationary reference after startup/scene preparation, with warm-up separated from steady-state. Keep untouched screenshots and `/voxellight status` output for each run. Repeat after every phase; do not compare different cameras or SPP progress.

`/voxellight profile on`, then `/voxellight export`, produces probe, world-budget, pass and scene CSVs plus status. Run `python3 tools/rt_baseline_report.py --metadata baseline.json benchmark-results/voxellight/<probe>.csv benchmark-results/voxellight/<probe>.csv.passes.csv` to summarize measured p50/p95. Metadata must record the pinned settings, version, scene, GPU, mode and screenshot filenames. Keep raw CSVs. Missing GPU results remain missing. Native external GPU records do not contain CPU submission measurements. Submission time is not CPU frame time and pass sums are not GPU frame time. Capture actual CPU/GPU frame durations and VRAM with GPU tooling separately, alongside rays, visibility rays, average bounce and reference progress from status.

## Remaining implementation and experimental gates

| Review work | State |
| --- | --- |
| NVIDIA 0.37.6 baseline and repeated phase acceptance | Not captured; GPU required |
| Shared HDR environment, weather/cloud refresh, matched importance sampling | Pending; current RT gradient/uniform sampling retained |
| Adaptive full-resolution classification, compaction/indexed rays, signal resolves | Pending; current bounded low-res signals/strict rejection retained |
| Robust origins, complete shading-normal correction | Pending; fixed offsets remain |
| UV anisotropic tangent frame | Implemented, CPU/native verification; brushed scene pending |
| Coated class semantics | RT duplicate base highlight removed; raster parity and layered reference pending |
| Multi-scattering GGX, canonical manual calibration | Pending; single-scattering loss remains |
| RT mip budget/ray cones/animated companion synchronization | Pending |
| Primary light ownership | Opaque glossy NEE implemented; full primary diffuse/direct and analytic block lights pending |
| Hierarchical lights and ReSTIR DI | Pending; no benchmark conclusion claimed |
| L1/L2/SG cache A/B and per-pixel refinement | Pending; current L1 diffuse cache retained |
| Water free-flight/multiple scattering and caustic clipmaps/glass | Pending; existing single-scattering/cache retained |
| Full-primary reference and asynchronous double bank | Pending; current reference still raster-primary and display waits on each bounded CUDA window |
| OptiX-IR/O0/optimized experiments and module split | Pending; current O0 remains, no runtime/compile benchmark claim |
| Signal-specific denoiser trust and optional NRD experiment | Pending |
| Extended debug and acceptance scenes | Pending beyond existing debug/status/A-B |

Alpha acceptance focuses on iron/gold/copper under local emitters at night, with light positions unchanged, `rt_primary_glossy_nee` on/off and sufficient completed reference sweeps. CPU Monte Carlo tests check glossy environment MIS against independent quadrature, coated substrate semantics, VNDF PDFs and UV frame handedness. They cannot establish GPU lighting or edge quality.
