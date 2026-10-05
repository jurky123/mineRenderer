# Vulkan RT migration — staged implementation ledger

This release starts migration stage **1: legacy OptiX production + Vulkan POC**. It does not complete the renderer migration. Production RTX Quality remains legacy OptiX. The explicit Vulkan POC has no OptiX/CUDA renderer dependency, but only traces terrain camera rays and displays geometric normals. No PT/material/reconstruction parity or RTX 4060 Laptop performance gate has passed.

The implementation preserves Material 3, LabPBR, physical environment/clouds/water, emission collection, radiance cache and raster fallback in the existing production renderer. It deliberately does not replace their behavior with provisional Vulkan shading.

## Alpha.2 startup correction

The user Windows/NVIDIA alpha.1 startup failed at `VkExtensionProperties.calloc(count, stack)`. A sufficiently large extension list exceeded LWJGL native thread stack capacity before device creation completed. Extension lists and variable-sized TLAS instance arrays now use explicitly freed native heap allocations; each BLAS geometry temporary has its own stack scope. A 512-entry regression runs with only 1 KiB stack remaining. This is an allocation bug in the mod, not evidence of insufficient Java heap. Windows startup and RT execution still need user GPU validation.

## Implemented first milestone

- `rt/vulkan/VulkanRtCapabilities`: extension enumeration and required feature query; optional capabilities are advertised only, not enabled or claimed operational.
- Minecraft 26.2 `VulkanBackend.createDevice` hook: add acceleration structure, ray tracing pipeline and deferred host operations extensions only when all mandatory features exist; enable acceleration structure, ray tracing pipeline and Vulkan 1.2 buffer device address in the actual device creation chain. Preserve MC's existing Vulkan12 structure, avoiding duplicate feature structs. Unsupported devices continue native raster startup.
- `VulkanRtContext`: borrow MC device/graphics queue; no private Vulkan device, CUDA context, OptiX context or normal-frame CPU image staging. Keep pipeline/scene on viewport resize.
- `VulkanRtAccel` / `VulkanRtScene`: one triangle BLAS per admitted section; world TLAS with section-origin instance transforms. Reuse `RtGeometryStream`'s 40-byte native compilation snapshot and normals. Identical versions generate no allocations/builds. A changed section replaces only that BLAS; instance membership changes rebuild TLAS. Multiple BLAS builds share frame command recording, without per-section submission.
- `VulkanRtPipeline` / `VulkanSbt`: prebuilt independent raygen/miss/closest-hit stages, 16-byte payload, recursion depth 1; correctly separated handle/base alignment; fresh immutable descriptor sets for in-flight frames. Pipeline cache keyed by device/driver/cache UUID and binary hash. Missing/invalid shader binaries fail to raster instead of invoking a runtime source compiler.
- Resource retirement through MC's submission-index destruction queue; explicit transfer/build/trace/copy dependencies. The output stays on GPU, copied into an RGBA32F diagnostic texture. Phase A deliberately forces opaque terrain: cutout, refraction and Material 3 are not yet evaluated.
- Existing delayed timestamp/export machinery records `vulkan_rt_scene`, `vulkan_rt_blas`, `vulkan_rt_tlas`, `vulkan_rt_primary`, `vulkan_rt_debug_composite`. Scene is an aggregate, not an extra pass to add to BLAS/TLAS totals.

POC allocation scope: 512 resident sections, 64 MiB copied vertex inputs, 144-block admission window; normals add one float4 per triangle; AS storage/scratch are driver-sized. Debug signal is quarter resolution or lower, bounded to 640×360, RGBA32F buffer + texture (32 bytes/RT pixel, at most 7.04 MiB), 96-byte per-dispatch camera buffer. Pipeline, BLAS, TLAS, SBT, descriptor pools and temporary build allocations all have explicit owners. These are POC limits, not final PT performance budgets.

## Build-time Slang pipeline

Slang **2026.19** is the pinned development baseline. Linux x86_64 bootstrap verifies the published archive SHA-256:

```sh
python3 tools/bootstrap_vulkan_rt.py
# Install spirv-tools from your build host package manager, including spirv-val.
./gradlew build clientKit -PnativeKit
```

For another build host, install Slang/spirv-tools and set `SLANGC` / `SPIRV_VAL` to their executables. A legacy native kit still requires the existing native build; no OptiX source was changed by this release.

Gradle `compileVulkanRt` runs `tools/build_vulkan_rt.py`: Slang → SPIR-V 1.5, Vulkan 1.2 validation, reflection descriptor/96-byte camera ABI verification, source/binary digest manifest. Jar packaging consumes only generated `.spv`/reflection/manifest resources. This build fails if tools or shader validation are missing. Runtime uses `vkCreateShaderModule` and `vkCreateRayTracingPipelinesKHR`; no Slang subprocess or runtime PTX/OptiX-IR compiler runs in the Vulkan POC.

Shader ownership is currently `common/bringup.slang`, `world/primary.slang`, `world/closest_hit.slang`, `world/sky.slang`. There is no mode-multiplexed RT shader. PT modules are added when their implementations migrate, rather than creating empty files that suggest parity.

Host verification: 260 Java/native contract tests and three Python report tests pass; Gradle build/clientKit and all three Slang/SPIR-V validation/reflection checks pass. Host tests validate packaged stage execution models/nonrecursive raygen, SBT alignment/overflow, the actual MC device creation callsite, absence of OptiX/CUDA calls and per-section submit/device-idle calls in Vulkan owners, and the debug display GLSL. These checks cannot validate GPU execution or visual output.

## RTX 4060 Laptop bringup check

```text
/voxellight profile on
/voxellight rt_backend vulkan_poc
/voxellight status
/voxellight export
```

Expect terrain world normals as RGB, dark misses, a `Vulkan RT normal POC pipeline ready ... ms` log, `runtimePtCompiler=0`, `recursion=1`, `spp=1`. This is a debug view, not lighting. Capture startup log, a normal screenshot, status and pass CSV. Warm restart retains the disk pipeline cache. Repeat status while stationary after terrain admission completes: BLAS/TLAS build counters must stop. Edit one block: only its section BLAS counter should increase (TLAS changes accompany changed instance handles). Resize: no new pipeline-ready log; scene/pipeline survive. F3+T/world reset rebuilds the POC scene.

```text
/voxellight rt_backend raster
/voxellight rt_backend optix_rt
```

These explicitly restore raster or legacy RTX. Reference commands switch back to the legacy tracer during stage 1; **Vulkan full PT reference is not implemented**. POC selection is experimental command-only and does not change the saved RTX Quality default. Starting a world with a previously saved OptiX preset may already initialize legacy before selecting POC; a zero-OptiX startup comparison must begin with the raster preset.

Use `python3 tools/benchmark_summary.py <export>.passes.csv` for per-pass p50/p95 GPU ms. Do not interpret the debug-view primary timing as PT performance or compare it to full legacy lighting. This host exposes a virtual display adapter (1013:00b8), no NVIDIA device or usable RTX validation target; no hardware acceptance is claimed.

## Subsequent migration gates and preserved ownership

| Phase | Required implementation/evidence | State |
| --- | --- | --- |
| A/B | Terrain ray/normal bringup, enabled features, build-time SPIR-V, validation/layout/cache | Terrain normals and block edits accepted by user; static/resize/performance checks pending |
| C | Batched BLAS builds, async compaction, entity topology/refit and transform-only updates; static zero rebuild | Terrain subset implemented; compaction/entities pending |
| D | Primary guide/material/continuation writer + independent indirect wavefront passes, recursion 1, bounded queue ownership | Pending |
| E | Port `native/rt/bsdf.h` + material decode/hit tangent basis without changing Material 3 semantics; fixed Cornell/gold/copper/glass/water/roughness/foliage A/B | Slang numeric kernels ported and CPU parity verified; hit binding and rendered A/B pending |
| F | Existing sun/moon NEE and emissive metadata → section hierarchy/alias grid/single-frame RIS; independent primary/secondary/depth budgets | Pending |
| G | One path per pixel/frame; optional second samples admitted by signal variance/disocclusion, measured average ≤1.5 | POC one ray only; PT/adaptive pending |
| H | Reconstruction interface consuming GPU signals/guides with explicit availability/history/resize contracts | Selection IDs declared only; interface/implementations pending |
| I | Separate denoiser-only native owner; temporal AOV layers, guides/internal/history, GPU external memory/semaphore ownership | Pending; legacy integrated denoiser retained |
| J | DLSS RR native SDK integration, reflected/refracted motion and disocclusion/TIR acceptance | Pending |
| K/L | Optional OMM cutout A/B; SER enabled only at measured divergent reorder points | Pending |
| M | Existing low-res volumetric clouds feed sky map/sun attenuation/cloud shadows; water surface dielectric, volume medium, bounded caustic cache | Existing legacy/raster preserved; Vulkan port pending |
| N | Vulkan full PT progressive reference, no reconstruction, 16–4096 spp, 8–12 bounces | Pending |

Port responsibilities: `hit.cuh` and material lookup → closest-hit/any-hit and `common/material`; `bsdf.h` → `common/bsdf`; `transport.cuh`/`visibility.cuh` → indirect/medium/light sampling modules; `cache.cuh` → bounded world-radiance compute passes; `caustics.cuh` → separate bounded photon/cache compute; `environment.glsl` and existing map weighting → shared sky/environment sampling. CUDA utilities become ordinary Vulkan compute stages. Closest-hit reports intersections/materials, never recursively drives transport. Continuation records carry throughput, PDF/delta state, eta scale, medium identity stack and sample/path state; reconstruction does not mutate those transport records.

Profile GPU register pressure, occupancy, instruction-cache behavior, payload bytes and continuation bandwidth using Nsight. Delayed pass timers alone are not evidence for these metrics. Final phase timers must separately record BLAS, TLAS, primary, indirect, light sampling, medium, reconstruction and composite; missing/unobserved phases must remain missing, never zero.

## Reconstruction integration constraints

`RtReconstructionBackend` currently names `DLSS_RR`, `OPTIX_TEMPORAL_AOV`, `NONE`; it is not a claim that the first two are implemented for Vulkan. Reference must choose NONE. SDK absence or unsupported hardware must have a visible availability reason and a usable fallback.

For OptiX denoiser extraction, export dedicated signals/albedo/normal/flow/trust/history/internal-guide resources. OptiX `OptixImage2D` consumes a linear CUDA device pointer and byte strides; an imported CUDA mipmapped image array is not directly interchangeable with that pointer. Prefer compatible GPU-only linear views, or import the array then perform a GPU-only array/linear copy for denoising. No GPU→CPU→GPU image path is acceptable. Keep context, stream, denoiser and external synchronization, and remove GAS/IAS/module compilation/tracing only after Vulkan parity gates pass. [OptiX types](https://raytracing-docs.nvidia.com/optix8/api/group__optix__types.html).

DLSS RR needs separate diffuse/specular reflectance, normals/roughness, noisy color, depth and dense geometry motion, plus reflection motion or specular hit distance/matrices. Refractive guides and trust/TIR rejection remain VoxelLight responsibilities; do not label the geometry vector as reflection/refraction motion. Native device registration, feature support, resource tags/lifetimes and SDK redistribution must be verified with the actual integration. [NVIDIA RR guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md).

## Default switch and deletion gate

Progress: (1) legacy production + Vulkan POC → (2) OptiX/Vulkan A/B → (3) Vulkan experimental RTX → (4) Vulkan default RTX → (5) OptiX tracing deprecated → (6) tracing deleted → (7) denoiser retained.

Before step 4, require RTX 4060 Laptop cold startup <10 s, warm <2 s, zero runtime OptiX PT compilation on the Vulkan path, true 1-spp baseline/RTX Quality average ≤1.5, fixed-scene Material 3/reference acceptance and full Vulkan RT frame time ≤1.05× legacy. SDK backend availability and raster fallback must remain functional. **Normal bringup alone does not satisfy these gates.**

## 0.39.0-alpha.3 — preserve terrain on backend switch

The alpha.2 user log confirms Minecraft reset the graphics API after the previous crash and selected OpenGL; Vulkan RT never initialized. The Vulkan POC command now rejects OpenGL with an explicit switch-API-and-restart message before changing mode or geometry admission. Both RT backends stop calling LevelRenderer.invalidateCompiledGeometry (which releases native terrain buffers); independent RT warmup admits already loaded sections without discarding raster geometry. The diagnostic view runs once after world rendering and before camera projection reset, independently of foundation material/shadow readiness. Host regression/build checks cover call placement and absence of terrain invalidation; RTX rendering remains unverified on this host.


After a startup crash, explicitly select Vulkan in Minecraft video settings and restart. Confirm `backend=Vulkan` in `/voxellight status` before testing `/voxellight rt_backend vulkan_poc`. F3+T is not a graphics API switch.

## 0.39.0-alpha.4 — Vulkan normal display render area

The alpha.3 RTX 4060 log confirms mandatory Vulkan RT extensions enabled and POC pipeline creation in 39 ms, then 6 ms on retry. Display failed with `RenderPassDescriptor.renderArea must be provided`, causing raster fallback. Alpha.4 supplies the full destination viewport to the debug composite descriptor. These pipeline timings are not full renderer startup or PT performance acceptance. Terrain normal output and static/edit BLAS behavior still require GPU confirmation.

## 0.39.0-alpha.5 — RT buffer descriptor writes

Alpha.4 created the Vulkan pipeline and ran the display path without the previous exception, but the user reported a black screen. The RT output SSBO, normals SSBO and camera UBO writes had descriptorCount zero: LWJGL pBufferInfo only sets the pointer, not the count. Alpha.5 explicitly writes the supplied buffer count; a native-struct regression invokes the actual production descriptor builder and checks each of the three bindings, descriptor counts, buffer handles and ranges. GPU output still needs confirmation; pipeline creation alone does not validate tracing.

## 0.39.0-alpha.6 — diagnose black Vulkan RT output

Alpha.5 still showed black despite status reporting 183 section BLAS builds and a completed debug display path. The cause remains unconfirmed. This diagnostic candidate makes ray misses a direction gradient, adds a known magenta raygen output marker and an independent cyan fullscreen border, and logs a one-time asynchronous readback of only two RGBA32F pixels (32 bytes per context, no wait). The marker verifies dispatch/output copying; center alpha distinguishes hit (1) from miss (0); red denotes nonfinite sampled output. This is POC-only diagnostic telemetry, not a reconstruction readback or performance acceptance. No denoising path is changed. Request screenshot plus `Vulkan RT POC GPU diagnostic` log before further conclusions.

## 0.39.0-alpha.7 — nonempty AS geometry builds

Alpha.6 GPU telemetry verified the raygen marker (1, .2, .8, 2), finite direction colors, and center alpha 0; the user saw only miss background. BLAS/TLAS build geometryCount was zero because LWJGL pGeometries sets only its pointer (it shares the count with alternative ppGeometries). Alpha.7 explicitly sets the geometry count before both size queries and build recording. A regression invokes the production build-info builder for triangle BLAS and instance TLAS and verifies count, type, geometry pointer/type and BUILD mode. The GPU diagnostics remain until actual terrain hits and static/edit behavior are accepted.

## 0.39.0-alpha.8 — retain edited sections under the scene budget

User confirmed alpha.7 terrain normals and center hit alpha=1. The scene had reached 67,108,800 bytes of its 64 MiB vertex budget. Previously replacing an edited section removed its old BLAS before rejecting a slightly larger replacement, leaving a permanent miss hole. Alpha.8 prioritizes existing-section changes, accounts their net size, evicts distant unaffected resident sections when necessary to fit an edit, and replaces/releases the old BLAS only after building its successor. Oversized/unadmitted updates retain the prior geometry. New admissions do not consume edited-section reservation; unchanged versions still skip builds. The budget counts vertex storage, not total AS/scratch allocations. Tests cover full byte/section limits and replacement net-size behavior; User subsequently confirmed alpha.8 placement and destruction are normal; the supplied records include both edits. Static zero-rebuild is not inferred from those editing records.

## Portable transport library — unreleased

`common/math`, `material`, `bsdf`, `ggx_energy`, `medium` and `surface` retain the native formulas and the exact 1,024-entry energy table. Slang has explicit initialization, mutating medium-stack methods and guarded vector normalization. Small-argument log1p/expm1 use series to preserve the native stable medium proposal in binary32. No native source, production integrator or reconstruction path is changed.

`./gradlew verifyVulkanTransport` builds/validates the exercised functions as SPIR-V and compiles the same Slang into its CPU target, then compares against the existing C++ implementation. The 57,600 cases include 10 material classes, all eight LabPBR conductor presets, generic conductor, nine roughness levels, five angles and 64 seed sweeps. Evaluation/PDF, sampled direction/throughput/eta/flags, Fresnel, HG sampling, medium sampling and stack mutation, palette decoding, cutout boundaries and ray-origin offsets cover 3,225,600 scalar components. Maximum normalized error is 0.000381917, below the 0.003 gate. The test also requires finite output. Native source provenance hashes are checked before compilation, so upstream semantics cannot silently leave a stale Vulkan port. A C++17 compiler is now required for `check`; runtime requires no compiler.

These are kernel tests, not Cornell images or GPU BSDF execution. The runtime Vulkan POC remains a normal view. Material hit/atlas binding, primary/indirect queues and guide generation are the next integration work; DLSS RR and the isolated denoiser are not implemented by this library.
