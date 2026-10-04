# 0.38.0-alpha.4 — OptiX Compilation Architecture

This is a GPU acceptance candidate, **not a demonstrated startup fix**. The user baseline is RTX 4060 Laptop / Windows / NVIDIA 591.74 / alpha.3 / strict PTX / default optimization: `tasks=2/3` was still running after 1185 seconds. No NVIDIA device/display is available on the build host. P1–P7 remain deferred until the P0 gates below pass.

## Compiler graph and ownership

The previous single `OptixModule` exported one multiplexed raygen including hit/material, realtime signals, probes, full camera reference with multiple medium events, photons and ordinary compute utilities. This is a strong structural suspect for compiler complexity growth; the old aggregate task count cannot identify the responsible compiler pass or prove a driver regression. The new task records and driver A/B are required to establish that distinction.

| Artifact | Responsibility / compilation ownership |
| --- | --- |
| `rt_hit` / `hit.cu` | miss, surface any/closest-hit, cube benchmark intersection/closest-hit; `material.cuh` decodes materials without transport |
| `rt_realtime` / `program.cu` | diffuse, specular, transmission, world probes, existing raster-primary comparison and transport diagnostics |
| `rt_reference` / `reference.cu` | full-primary camera reference and explicit multiple medium events; strict math; never loaded during ordinary RTX startup |
| `rt_caustics` / `caustic.cu` | photon tracing, independent pipeline sharing hit program groups |
| `rt_utility.ptx` / `utility.cu` | ordinary CUDA kernels: environment row CDF/reduction, counters, probe invalidation, guide-history copy, caustic clear/resolve; no `optixTrace` or OptiX raygen |

Realtime and caustics use separate pipelines with common hit groups. Full reference compiles a separate OptiX context/pipeline on the startup worker only after `rt_reference_full on`. It first ensures realtime startup is available, continues realtime frames while compiling, and switches ownership after successful creation. Compile failure keeps the existing realtime context; abandoned background results are disposed. Full-reference scene/resources are created on adoption, using the existing asynchronous window/display ownership. Exiting reference rebuilds realtime from normal cache. This trades a temporary additional **compiler context**, without duplicating the live GPU scene during compilation, for isolated failure/lifetime ownership.

All previous shared HDR environment, NEE/MIS, UV frames, robust origins, reflection compensation, clustered emitters, water free-flight and asynchronous reference algorithms remain. Realtime no longer compiles the full-reference medium-event branch. Raster-primary `rt_reference` still belongs to the realtime graph; use `REFERENCE_STRICT` for a strict comparison if realtime fast math is selected.

## Profiles and four-way experiment

`VOXELLIGHT_RT_PROFILE=REALTIME_RELEASE|REFERENCE_STRICT|DEVELOPMENT` (environment variable). Full reference always uses strict artifacts. DEVELOPMENT selects OptiX O0 for development only; the architecture split applies to every profile. Release and strict use default optimization. Existing `VOXELLIGHT_RT_OPTIMIZATION=0|1|2` remains an explicit diagnostic override, including reference.

Strict PTX remains the default **pending numerical/visual GPU A/B**. Select the experiment using JVM arguments:

| Experiment | JVM arguments |
| --- | --- |
| strict PTX | `-Dvoxellight.rt.module=ptx -Dvoxellight.rt.math=strict` |
| fast PTX | `-Dvoxellight.rt.module=ptx -Dvoxellight.rt.math=fast` |
| strict IR | `-Dvoxellight.rt.module=ir -Dvoxellight.rt.math=strict` |
| fast IR | `-Dvoxellight.rt.module=ir -Dvoxellight.rt.math=fast` |

Fast artifacts use NVRTC `--use_fast_math`; hit/realtime/caustics are built in all four combinations. Reference builds strict PTX and IR only, utility builds ordinary strict CUDA PTX. On IR error 7251, the isolated creation retries PTX once with the same math selection; no retry on device errors or timeout. Keep retry logs separate from successful original-IR results. NVIDIA reports fast math as a workaround for a reproduced 591.44 IR issue; applicability to this scene/591.74 is unproven. [NVIDIA reproduction and workaround](https://forums.developer.nvidia.com/t/optix-ir-seems-to-fail-for-me-with-vector-types-updated-with-reproduction/353662).

Build-time NVRTC timings/bytes are bundled in `voxellight/native/compile-build.json`. Initial host measurements for realtime: strict PTX 3.38 s / 1,263,124 bytes; fast PTX 1.92 s / 783,280 bytes; strict IR 2.10 s / 390,860 bytes; fast IR 1.25 s / 258,824 bytes. These are **NVRTC host results**, not NVIDIA driver module compile, startup or GPU runtime. Strict realtime PTX remains large; no compile-time improvement is inferred from splitting or smaller artifacts alone.

## Telemetry, cache and watchdog

Native stderr records module name, code bytes, profile, optimization, NVIDIA driver version (optional dynamic NVML; `unavailable` if absent), GPU name and CUDA driver API version separately. Each task logs a per-module ID/handle, serialization key if serializable, start/finish epoch milliseconds, worker thread ID, returned child count, active milliseconds and result. Every module reports total compile time, and every pipeline reports program-group/link milliseconds. Existing delayed GPU events/profile exports measure actual runtime signal/probe/photon/utility passes.

A stable `${user.home}/.cache/voxellight/optix` disk cache is configured after context creation. A write probe and SDK setup check writability; enabled/location/low/high watermarks are read back and reported in logs and `/voxellight status` as `rtCacheEnabled`, `rtCachePath`, `rtCacheLowWater`, `rtCacheHighWater`. SDK `OPTIX_CACHE_PATH` / `OPTIX_CACHE_MAXSIZE` overrides take precedence; disabled cache is reported honestly. Compiler warning/error callbacks now print as well as retaining diagnostic text, including cache-entry-size warnings. The SDK watermarks are retained until real entry sizes justify an increase. `VOXELLIGHT_RT_CACHE_HIGH_BYTES` can raise the high watermark from measured cache requirements; low is then half, with SDK readback. No guessed PTX-to-cache-size multiplier is used.

Realtime modules have a 120-second watchdog each; reference-context modules allow 600 seconds. It covers initial task creation and task execution. Deadline expiration requests OptiX 9.1 context creation cancellation; tasks returned by OptiX are drained, workers joined, then failed module/context destroyed. There is no killed/detached compiler thread. This is cooperative driver cancellation: a defective driver ignoring cancellation can still delay cleanup. Synchronous program-group/pipeline-link calls are timed but **not covered by a cancellable hard watchdog** in this SDK API. Both cancellation response and link behavior require GPU verification before accepting the “no unbounded startup” gate.

Task Serialization storage is deliberately deferred until normal disk cache is validated. Serialization keys are now observable. Afterwards evaluate `optixTaskSerializeOutput` / `optixTaskDeserializeOutput` for DEVELOPMENT only, measuring incremental benefit and respecting SDK task/output lifetimes, rather than introducing another cache now. [OptiX 9.1 programming guide](https://raytracing-docs.nvidia.com/optix9/guide/index.html).

## Required RTX 4060 Laptop acceptance

1. Keep the alpha.3 / 591.74 1185-second result as baseline. Install alpha.4, same scene/settings/power mode, keep raw startup logs and status. First run measures existing-cache startup; to measure a true cold run, move the **VoxelLight cache only** aside while the game is closed and restart. Then restart the identical build/config for warm comparison. Do not clear other applications' NVIDIA caches.
2. Summarize logs: `python3 tools/rt_compile_report.py cold.log warm.log --build compile-build.json > compilation.json`. Missing completions stay missing; task start without finish is listed explicitly. Preserve the raw logs. Verify actual cache entries persist between starts and no oversized-entry warning occurs; a fast second start alone is not proof of a cache hit.
3. Gate: cold realtime target <30 s, ideal <10 s; warm target 2–5 s. Report hit/realtime/caustics module time, program-group time, each pipeline link time, cache state/path/sizes and total startup separately. GPU driver timings and cache-hit evidence are currently **unmeasured**.
4. Enable full reference after normal RTX is active. Confirm realtime frames continue throughout compile, reference switch happens only when ready, off/close/world change disposes late results safely, and reference compilation failure leaves realtime usable. Confirm the realtime startup log has **no `rt_reference` module creation**.
5. Run all four format/math combinations at the same camera, settings and completed sweeps. Export at least 60 s runtime profiles and unmodified screenshots/status. Compare linear output/energy, finite values, MIS/BSDF PDFs, water attenuation, metal/glass highlights and edges against strict mode. No numerical or visual A/B is claimed from host tests.
6. Repeat on a newer NVIDIA notebook-compatible driver available for this GPU (including 616.x if actually offered). Record the exact installed version; keep 591.74 data. Driver upgrade is an A/B diagnostic, never a substitute for the module split.
7. Exercise cancellation (a slow cold strict build if it exceeds the watchdog); verify timeout diagnostics, returned-task draining, raster fallback and subsequent clean startup. Do not label the lifecycle accepted merely because the API compiles.

Host validation covers NVRTC PTX/IR, Windows/Linux native bridge compilation, Java/native CPU regression and log-parser tests. GPU compile/link/runtime, cache persistence, watchdog response, numerical A/B and in-game reference adoption remain the blocking acceptance gates. No P1–P7 quality feature is part of this candidate.

Host candidate validation (2026-10-04): `tools/build_optix.py --deps /tmp/voxellight-rt-deps` passed for all 15 RT artifacts and both native libraries; `./gradlew build clientKit -PnativeKit` passed with 251 tests / zero failures; 3 Python report tests passed. Packaged JAR contains the 15 split RT artifacts and `compile-build.json`, and no old `rt_program` monolithic artifact. These checks do not close the GPU acceptance gates.
