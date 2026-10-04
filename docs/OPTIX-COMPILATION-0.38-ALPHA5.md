# 0.38.0-alpha.5 — failed alpha.4 GPU gate and compiler follow-up

Alpha.4 **failed** RTX 4060 Laptop / Windows / NVIDIA 591.74 acceptance. The user's raw launcher output identifies the unresolved compiler task:

```text
rt_realtime task 1: 930 ms, returned 2 children
rt_realtime task 3: 404 ms, completed
rt_realtime task 2: started, no finish before 120 s deadline
compile timeout module=rt_realtime; requesting cancellation
process exited; launcher found no Java exception stack
```

This establishes that the first hit module completed and realtime compilation is the current blocker. The crash follows cancellation closely; **the exact native fault is not established** without an exit code/native crash report or a cancellation return record. Cache-hit/runtime/reference gates did not run. Resource reloads/resizes/preset replay abandoned startup owners and reset displayed elapsed time, while the original native compile continued. `latest.log` omitted native stderr task records; the launcher raw output contained them.

## Follow-up changes

- Realtime no longer compiles diffuse/specular/transmission/probes in one runtime-mode raygen. `program.cu` is compiled with a constant signal (diffuse by default); `specular.cu` and `transmission.cu` select their constant signal. `probes.cu` is independent. Artifacts are `rt_realtime` (diffuse), `rt_specular`, `rt_transmission`, `rt_probes`. All four raygen program groups share a realtime pipeline and the existing hit groups. Native `params.mode` selects the **host SBT record**, not a combined device graph. Geometry benchmark raygen belongs to the hit module and has no integrator. Reference and caustic compilation remain separate.
- Reflection multiple-scattering evaluation, glossy evaluation and glossy sampling use ordinary non-inlined device function boundaries, matching the existing heavy BSDF functions. CPU formulas/sampling/PDF remain unchanged. Runtime call overhead and GPU numerical results require measurement.
- Transport bounce/interface loops, photon steps and the reflection-average loop explicitly retain iterative execution (`#pragma unroll 1`); their bounds and estimators are unchanged. This prevents expansion of nested transport bodies during compilation. GPU performance still needs measurement.
- Pending compiler ownership survives window/resource/world reset because it owns no imported scene or display resources yet. Repeated enabling of an already enabled backend is idempotent. Explicit disable/failure/mismatched pipeline type abandons startup; queued abandoned work skips native creation, running abandoned work still drains and disposes its late result. Existing GPU scene/display resources still close/retire normally.
- Full-reference on/off is session-only, matching raster-primary reference. Legacy saved `rt_reference_full on` is ignored on load. Scale remains persistable. `preset rtx_quality` explicitly exits reference. The alpha.4 user's `rtReferenceFullRequested=true` without a new reference command was a saved preference, not evidence that reference was compiled during the stuck realtime task.
- Native task/cache/module/group/link messages are still emitted to stderr, and also go through a bounded queue drained into the normal Java logger. A separate flushed `${gameDirectory}/logs/voxellight-optix.log` preserves records even if the process dies before Java drains them. The session record includes whether opening the file succeeded. No compiler thread calls Java. On a standalone host test without Minecraft, the file uses the system temporary directory.
- Watchdog binds its owning CUDA context before any cancellation API call. A module handle is atomically published after initial task creation; once available, cancellation targets that **module**, rather than all creations in its OptiX context. Context cancellation remains only for a blocked initial creation before the handle is published. Logs distinguish bind result, cancellation entry/scope and cancellation return/result. Worker joins/module lifetime rules remain. This is a corrective lifecycle change, **not proof of the crash cause or proof that 591.74 cancellation is safe**. The SDK documents cancellation as thread-safe; binding explicitly supplies the CUDA thread context as in the compilation workers. [OptiX 9.1 module API](https://raytracing-docs.nvidia.com/optix9/api/group__optix__host__api__modules.html).

Strict PTX/default optimization remains the comparison baseline. All four strict/fast × PTX/IR experiments are built for hit/diffuse/specular/transmission/probes/caustics; full reference is strict PTX/IR; utility is ordinary CUDA PTX. Total RT artifacts: 27. Normal disk-cache policy and task serialization priority are unchanged.

Measured host artifact sizes: strict diffuse PTX 916,777 bytes (alpha.4 combined realtime 1,263,124); strict specular 822,796; transmission 623,306; probes 656,630. Fast diffuse PTX 524,376; fast diffuse IR 177,660. These sizes **do not demonstrate a driver compile-time improvement**. The sum of all split inputs increases because transport functions are compiled in each module; per-module optimization graph complexity decreases. Driver timings/cache/runtime determine whether this tradeoff succeeds.

## Host validation

Windows/Linux native builds and all 27 RT artifacts compiled successfully. `./gradlew build clientKit -PnativeKit` passed with 253 tests (zero failures/errors); the 3 Python report tests also passed. The versioned kit contains this mod only, including the new split artifacts. This host has no NVIDIA GPU: driver compilation, cancellation, cache-hit timing and rendering correctness remain unverified.

## Next GPU check

Replace alpha.4 with the alpha.5 candidate and keep 591.74 for the first comparison. Run only `/voxellight preset rtx_quality`; reference must show requested=false. Preserve both `latest.log` and `voxellight-optix.log`. Check module-by-module times, that resize/resource reload does not reset the compile owner/start time, and that no full-reference module starts. If the watchdog fires, preserve the last diagnostic records (bind/enter/return/task finishes/cleanup) and launcher exit code. No user is asked to wait beyond the watchdog or repeat alpha.4.

If realtime starts, restart the identical build/config to measure cache behavior, then test explicit background full reference. Cold target <30 s / ideal <10 s; warm 2–5 s. Next compare strict/fast PTX/IR and a newer available notebook driver. Full GPU acceptance, cancellation response, synchronous link-time bounds and numerical/runtime A/B remain **pending**, and P1–P7 stay deferred. Alpha.5 must not be described as an accepted startup/crash fix from host builds alone.
