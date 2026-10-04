# 0.38.0-alpha.6 — OptiX callable compiler boundaries

This is a GPU acceptance candidate. P0 is still open until the RTX 4060 Laptop cold/warm startup and runtime tests pass. No quality feature work is included.

## Measured failed baseline

Alpha.5 on Windows / RTX 4060 Laptop / driver 591.74 / strict PTX / default optimization:

- `rt_hit`: 1,143 ms.
- `rt_realtime`: 916,777-byte input; module finished after 267,300 ms, with timeout set. The long task finished after 266,762 ms with result 7290 (`OPTIX_ERROR_CREATION_CANCELED` in the 9.1 SDK).
- Cancellation was requested at 120 s; binding and the cancellation API returned success immediately. Task draining then took about 147 more seconds. The later log establishes eventual cancellation, rather than permanent nonreturn.
- Driver statistics for diffuse: 14 trace sites, 4,382 basic blocks, 62,994 instructions, zero continuation callable calls and zero non-entry functions. Source-level non-inline boundaries did not produce a sufficiently small final raygen graph.
- Specular/transmission/probes/caustics/groups/link/runtime/warm-cache startup were not reached. No full-reference module started. Cache was enabled, but that alone does not demonstrate a cache hit.

## Compilation/runtime change

The transport implementation is removed from realtime raygen translation units. Ordinary C++ helpers have been replaced at the heavy boundaries by explicit OptiX programs:

| SBT index | Module | Entry | Responsibility |
|---|---|---|---|
| 0 | `rt_transport` or lazy `rt_reference_transport` | continuation callable transport | existing iterative bounce integrator, NEE/MIS/media/debug result |
| 1 | `rt_visibility` | continuation callable visibility | existing iterative colored visibility/interface traversal |
| 2 | `rt_bsdf` | direct callable evaluate | existing complete BSDF evaluation |
| 3 | `rt_bsdf` | direct callable sample | existing complete BSDF sampling |
| 4 | `rt_bsdf` | direct callable evaluate_glossy | existing glossy evaluation |
| 5 | `rt_bsdf` | direct callable sample_glossy | existing glossy sampling |

Raygens use the same synchronous request/result ABI for every incoming call, including realtime refinement, raster-primary comparison and debug. Requests carry the RNG state into/out of the callable. BSDF input references remain live across the synchronous call. The formulas, loop bounds, light PDFs, MIS weights, medium handling and accumulation remain unchanged; GPU numerical A/B is still required. BSDF callables never trace. Transport and visibility use continuation callables because they trace.

Full reference selects a separately compiled strict-math transport implementation with its original medium-event behavior and no caustic injection. Ordinary RTX Quality loads neither `rt_reference` nor `rt_reference_transport`. Reference remains lazy and background-owned. Realtime and caustic pipelines link the relevant callable program groups and share the six-record callable SBT. Program-group/module lifetimes extend through pipeline destruction.

Stack sizing accumulates every linked group and uses trace depth 1, continuation depth 2 (raygen → transport → visibility), direct-call depth 1 and traversable depth 2. Callables are `extern "C" __device__`; NVRTC uses relocatable device code for the callable-only modules so it retains exported device functions. These follow [NVIDIA's callable guidance](https://forums.developer.nvidia.com/t/invalidaddressspace-when-using-pointer-from-continuation-callable-parameters/184951) and [OptiX stack-sizing API](https://raytracing-docs.nvidia.com/optix9/api/optix__stack__size_8h_source.html).

Successful compile/cache feedback is now retained at OptiX log level 4, with acceleration-build verbosity filtered out. This exposes actual cache-hit messages and driver graph statistics in both diagnostic logs. Error diagnostics remain bounded. The native build report records exported PTX entry names and frontend trace/callable site counts; the build rejects missing callable exports and tracing BSDF modules. Those frontend counts are not driver instruction counts.

## Host evidence

Windows/Linux native libraries and all 41 strict/fast PTX/IR artifacts build. Reference and reference transport are strict PTX/IR only; utility remains CUDA PTX. The versioned client kit contains only this mod. Java/native regression tests: 253 passed, zero failures/errors. Python report tests: 3 passed.

Strict PTX frontend inputs:

| Module | Bytes | Frontend trace sites | Continuation calls | Direct calls |
|---|---:|---:|---:|---:|
| diffuse | 273,879 | 4 | 4 | 3 |
| transport | 453,616 | 1 | 2 | 2 |
| visibility | 52,665 | 1 | 0 | 0 |
| BSDF | 205,564 | 0 | 0 | 0 |

Specular input is 177,173 bytes; transmission 31,262; probes 69,855; reference raygen 25,036; reference transport 459,776. These measurements establish that transport is outside the raygen input and callable exports survive NVRTC. They do not establish fast driver compilation or rendering correctness. The GPU host is unavailable here. Explicit callable dispatch and stack storage may cost GPU runtime; per-signal timing and numerical/visual comparison are required before acceptance.

## Timeout limitation and next GPU gate

At the existing 120 s realtime / 600 s reference module deadline, the UI now explicitly reports timeout, active raster/realtime fallback and pending safe compiler cleanup. It stops reporting normal initialization heartbeats. The creating worker still joins all tasks and destroys its modules/context safely when they return. **This is cooperative cancellation, not a proven hard driver deadline.** No thread is killed/detached and no executing context is destroyed. If the smaller graphs still fail to drain promptly, in-process cancellation remains inadequate; the gate stays failed. Synchronous program-group creation/link also remain without hard cancellation.

Replace alpha.5 with alpha.6; keep strict PTX/default optimization and 591.74 for the first comparison. Run `/voxellight preset rtx_quality`. Preserve `latest.log` and `voxellight-optix.log`, including per-module finishes, successful compiler statistics, group/link durations and cache-hit messages. Require no full-reference module during startup. Cold target <30 s (ideal <10 s); identical-build warm target 2–5 s. Then measure GPU pass times and compare rendering before explicitly testing lazy full reference. Full reference failure must retain realtime.

After the baseline, the installed artifacts support strict PTX, fast PTX, strict IR and fast IR through JVM arguments `-Dvoxellight.rt.math=strict|fast` and `-Dvoxellight.rt.module=ptx|ir`. Compare the same scene/exposure/seed conditions, runtime and output; a 7251 IR failure retries the same math in PTX and must be reported as such. A newer available notebook driver is a separate A/B, not the code fix. P1–P7 remain deferred.
