# 0.37.6 — Reference progressive frame budget

## 0.39.0-alpha.6 — diagnose black Vulkan RT output

Alpha.5 still showed black despite status reporting 183 section BLAS builds and a completed debug display path. The cause remains unconfirmed. This diagnostic candidate makes ray misses a direction gradient, adds a known magenta raygen output marker and an independent cyan fullscreen border, and logs a one-time asynchronous readback of only two RGBA32F pixels (32 bytes per context, no wait). The marker verifies dispatch/output copying; center alpha distinguishes hit (1) from miss (0); red denotes nonfinite sampled output. This is POC-only diagnostic telemetry, not a reconstruction readback or performance acceptance. No denoising path is changed. Request screenshot plus `Vulkan RT POC GPU diagnostic` log before further conclusions.


## 0.39.0-alpha.5 — RT buffer descriptor writes

Alpha.4 created the Vulkan pipeline and ran the display path without the previous exception, but the user reported a black screen. The RT output SSBO, normals SSBO and camera UBO writes had descriptorCount zero: LWJGL pBufferInfo only sets the pointer, not the count. Alpha.5 explicitly writes the supplied buffer count; a native-struct regression invokes the actual production descriptor builder and checks each of the three bindings, descriptor counts, buffer handles and ranges. GPU output still needs confirmation; pipeline creation alone does not validate tracing.


## 0.39.0-alpha.4 — Vulkan normal display render area

The alpha.3 RTX 4060 log confirms mandatory Vulkan RT extensions enabled and POC pipeline creation in 39 ms, then 6 ms on retry. Display failed with `RenderPassDescriptor.renderArea must be provided`, causing raster fallback. Alpha.4 supplies the full destination viewport to the debug composite descriptor. These pipeline timings are not full renderer startup or PT performance acceptance. Terrain normal output and static/edit BLAS behavior still require GPU confirmation.


## 0.39.0-alpha.3 — preserve terrain on backend switch

The alpha.2 user log confirms Minecraft reset the graphics API after the previous crash and selected OpenGL; Vulkan RT never initialized. The Vulkan POC command now rejects OpenGL with an explicit switch-API-and-restart message before changing mode or geometry admission. Both RT backends stop calling LevelRenderer.invalidateCompiledGeometry (which releases native terrain buffers); independent RT warmup admits already loaded sections without discarding raster geometry. The diagnostic view runs once after world rendering and before camera projection reset, independently of foundation material/shadow readiness. Host regression/build checks cover call placement and absence of terrain invalidation; RTX rendering remains unverified on this host.



## 0.39.0-alpha.2

- Fix Windows NVIDIA startup MemoryStack overflow: allocate driver extension properties on native heap with explicit release.
- Move variable TLAS instance arrays off thread stack; scope temporary BLAS geometry per section.
- Add constrained-stack regression for 512-entry extension/instance arrays; retain existing Vulkan POC/legacy migration scope.

## 0.39.0-alpha.1

- Start direct Vulkan RT migration stage 1 with legacy production retained and explicit terrain normal POC.
- Enable actual native device RT/BDA features; section BLAS/TLAS, aligned SBT, GPU-only debug output, frame-safe resource retirement and pipeline cache.
- Gradle Slang/SPIR-V compilation, spirv-val and descriptor/camera reflection ABI validation.
- Separate Vulkan scene/BLAS/TLAS/primary/composite profiling and host contract tests. Full PT/reconstruction/performance migration remains pending.

## 0.38.0-alpha.8

Separate GameRenderer resize from full world/data reset, allowing alpha.7 viewport-only RTX reallocation to run. Guard callback ownership with a bytecode test. Reference and warm-cache GPU acceptance remain open. [Follow-up](REFERENCE-0.38-ALPHA8.md).

## 0.38.0-alpha.7

Retain compiled OptiX pipelines and world geometry on viewport resize; rebuild only frame imports/denoiser resources. Preserve reference sums on positive SPP target changes, report command semantics, expose reference reset count, and let measured reference windows grow to 8192 pixels. Alpha.6 user GPU cold startup ~16 s; cache persistence and rendering correctness remain open. See [follow-up](REFERENCE-0.38-ALPHA7.md).

## 0.38.0-alpha.6 — callable compiler boundaries (acceptance candidate)

Alpha.5 diffuse compiled to 62,994 instructions and canceled after 267.3 s. Move the existing integrator/visibility out of raygens into continuation callable modules; move BSDF evaluation/sampling to direct callable programs. Add callable groups/SBT, depth-aware stack sizing, strict lazy reference transport and NVRTC export validation. Log successful driver graph/cache feedback and explicit timeout/fallback/cleanup state. Windows/Linux builds and 253 host tests pass; real GPU startup/cache/correctness/runtime gates remain pending. [Architecture and limitations](OPTIX-COMPILATION-0.38-ALPHA6.md).

## 0.38.0-alpha.5 — realtime compiler/lifecycle follow-up (acceptance candidate)

User alpha.4 / 591.74 test stalled in realtime task 2 and exited immediately after watchdog cancellation. Split constant-signal realtime modules/probes; non-inline glossy BSDF boundaries; preserve compiler ownership across render resets and skip abandoned queued creation. Full reference no longer replays saved on state; RTX Quality exits reference. Native diagnostics reach latest.log and a flushed compiler file. Watchdog binds CUDA and cancels the published module with entry/return diagnostics. Exact prior native fault and GPU fix acceptance remain unconfirmed. [Evidence and next checks](OPTIX-COMPILATION-0.38-ALPHA5.md).

## 0.38.0-alpha.4 — OptiX Compilation Architecture (acceptance candidate)

Split hit/realtime/full-reference/caustic compiler ownership; full reference compiles lazily in an isolated background context while realtime continues. Non-tracing CDF/reduction/clear/resolve work moves to CUDA. Added per-task keys/timestamps/durations/threads, module/group/link timings, stable SDK disk-cache readback, 120 s realtime / 600 s reference creation watchdogs with cooperative cancellation and joined task lifetimes. Build strict/fast PTX/IR A/B artifacts; retain strict PTX baseline pending GPU numeric/visual checks. Windows/Linux native and Java builds pass; RTX 4060 / 591.74 cold/warm startup, cache and watchdog acceptance remain pending. Synchronous pipeline-link cancellation is unavailable in the current SDK. No P1–P7 quality work. See [architecture/gates](OPTIX-COMPILATION-0.38.md).

## 0.38.0-alpha.3 — OptiX startup compatibility

Alpha 2 failed OptiX-IR compilation on the user's NVIDIA 591.74 driver (error 7251), retaining raster fallback. Strict-math PTX is now the default; IR remains opt-in with a logged single PTX retry on compilation error 7251. Device errors do not retry. Native compiler callbacks retain errors/warnings, and compiler buffers grow from 8 KiB to heap-backed 1 MiB; module creation/program-group/link errors include diagnostics. Debug level is explicitly NONE for release modules. Default optimization remains optimized, not O0. GPU recovery is pending user verification.

## 0.38.0-alpha.2 — environment and asynchronous full reference

Shared GPU HDR sky/cloud environment and exact solid-angle importance CDF; OptiX primary-camera progressive reference; nonblocking reference completion/display ownership; stale-camera rejection; coordinate-aware ray origins and shading hemisphere checks; matched GGX reflection multiple-scattering sampling; water free-flight and full-reference medium events; build-time OptiX-IR/PTX and optimization A/B. See [0.38 ledger](RTX-QUALITY-0.38.md), [environment](RT-ENVIRONMENT.md), [reference](FULL-REFERENCE.md). This is still a checkpoint: adaptive full-resolution RTX rays, canonical calibration, RT texture mips/animation, primary diffuse ownership, hierarchical lights, layered reference, caustic clipmaps and signal-guide improvements remain unfinished. NVIDIA acceptance has not been run on this host.

## 0.38.0-alpha.1 — first integration increment

Primary opaque glossy NEE/MIS, RT coated-substrate semantics and UV tangent frames implemented. User confirmed 0.37.6 responsiveness; night iron/indirect quality remains unaccepted. The full 0.38 review is **not complete**. Implementation/acceptance ledger: [RTX Quality 0.38](RTX-QUALITY-0.38.md). No NVIDIA baseline available on this host.


- Replace three full-image reference signal dispatches with one shared 8–1024-pixel adaptive window per display frame, initially 64.
- Count spp only after complete image sweeps; stop transport at target; reset cursor/sums on camera/configuration/scene changes.
- Preserve guide snapshots and resolved dielectric interfaces; keep raster for untouched pixels.
- Skip full-image reference temporal raygen; bound counter reduction to the current window.
- Add sweep/budget progress and scheduler regression checks. NVIDIA frame-time acceptance remains pending.

# 0.37.5 — Parallel OptiX compilation / fast startup policy

- Use OptiX module task creation/execution with up to four background workers and safe dependency/error/lifetime handling.
- Use optimization level 0 to avoid the expensive optimizer pass; GPU execution may be slower and needs measurement.
- Log completed/discovered compiler tasks and elapsed time every 15 seconds.
- Native dependency/failure/empty-workload tests; keep asynchronous raster fallback and corrected RT texture transfers.

# 0.37.4 — RT output copies and compiler expansion

- Fix MC 26.2 buffer-to-texture source extents/mip/layer argument order for every RT guide/signal.
- Regression tests exercise the real Minecraft validation and reproduce the previous invalid-mip error.
- Keep heavy CUDA BSDF/transport routines out of line; use OptiX optimization level 2 to reduce compiler expansion.
- Background startup retained; NVIDIA startup timing/convergence/performance acceptance pending.

# 0.37.3 — Nonblocking OptiX startup

- Move CUDA/OptiX library, context, module compilation and pipeline linking to a daemon startup worker; retain raster frames while pending.
- Report native startup stages and elapsed time; distinguish Vulkan import/shader preparation in logs.
- Cancel ownership safely when disabled during startup; dispose late native handles off the render thread.
- Add startup concurrency/error/lifetime regression tests. NVIDIA responsiveness acceptance is pending.

# VoxelLight release history

## 0.37.2 — Reference crash hardening

- Fix rectangular depth pyramids producing native `1x0` mip views. End the chain when either axis reaches one; retain conservative coarse cells and test portrait/ultrawide sizes.
- Make JNI stats CPU-only and exception-safe, removing CUDA collection/process-termination risk from settings draws.
- Schedule reference at one spp/frame with at most 8,192 pixels per OptiX launch; preserve target spp/eight-bounce transport and global output/counter indexing. Disable reference reuse of probes and stale approximate caustics.
- Add native-startup/reference-dispatch markers and failure-stage reporting. Crash root cause remains unconfirmed because the supplied log stops before a fatal error; NVIDIA reproduction remains pending.

## 0.37.1 — Reference convergence & vanilla settings

- Fix reference accumulation resetting on every unchanged quality application; suppress projection jitter, RGB TAA and adaptive budget changes during reference. Auto-enable Foundation/OptiX on reference on and freeze RT entity animation after the first reference sample.
- Add searchable, paginated vanilla settings in Pause/Options and `/voxellight settings`: Rendering, RTX, Environment, Water and Diagnostics; validated choice/numeric controls and live reference progress. No MineUI dependency.
- Commands and UI share actions/override state. Atomically save normal preferences, restore on world join, keep debug/reference activation session-only. Add accumulation-state and preference persistence regressions. NVIDIA UI/convergence acceptance remains pending.

## 0.37.0 — Physically Based Materials & Light Transport

- Preserve single-raster primary visibility, incremental OptiX scene, GPU-only interop and raster fallback. Replace native material branches with production BSDF evaluation/sampling/PDF and separate material/light/path modules.
- Material 3 classes, explicit perceptual roughness/microfacet alpha, shared five-plane table, LabPBR 1.3 source priority, normal/AO/height decoding, published conductor eta/k, coats/foliage/rough dielectrics, 1,269 Vanilla texture presets and material override/report tooling.
- Every secondary bounce uses textured linear material BSDF. Add sun/environment/emissive/held-point NEE, power-CDF area sampling, solid-angle PDFs and power-heuristic MIS; retain explicit primary direct ownership.
- Match Minecraft terrain cutout alpha 0.5 instead of the previous RT 0.1; signal-specific material/normal/plane/footprint/hit-distance reconstruction and trust reject cross-object reuse. Translucent effects resolve at their actual native interface; rejected observations preserve raster lighting.
- Shared water medium parameters, extinction and HG single-scattering NEE; eight-entry identity-aware nested media; bounded sun-photon caustics driven by actual wave refraction. Preserve separate raster approximations for fallback.
- Configurable 4–4,096-spp, eight-bounce reference convergence; independent debug/A-B/clamp controls, delayed GPU stage times and operation counters; furnace/reciprocity/PDF/Monte Carlo/medium tests use the actual CUDA BSDF header on CPU.
- 233 tests, native Windows/Linux builds and GLSL/SPIR-V compilation verified. NVIDIA visual correctness/performance acceptance is pending; known finite coverage, thin-layer/microfacet/single-scattering/caustic/animated-map limits remain explicit in [RT-LIGHT-TRANSPORT](RT-LIGHT-TRANSPORT.md).

## 0.36.0 — Experimental raster-primary RTX lighting

- Actual OptiX raygen/miss/closest-hit/any-hit, exact native section triangle GAS and incremental world IAS. Bounded offscreen loaded-section admission, geometry hashes, stale snapshot rejection, shared/refittable captured model GAS and retained entity/block-entity instances. Isolated triangle/custom-AABB performance and asynchronous hit-correctness benchmark.
- UUID-matched Vulkan/CUDA external-memory and semaphore resources. Primary/atlas/signal image exchange stays on GPU; CPU staging remains only in the legacy CUDA reference and explicit benchmark diagnostics. Deferred/event retirement; no normal-frame device-idle wait.
- Three independent diffuse/specular/transmission signals and OptiX temporal AOVs, world guide flow/trust. Persistent 1,536 world-anchored SH probes replace new-view bootstrap as RTX GI representation. Local edits invalidate local probes, rotation does not clear cache.
- Textured linear Material2/LabPBR secondary hits, authored normal maps, matched conductor constants, GGX VNDF, Russian roulette; dielectric Fresnel/Snell/TIR/medium stacks, panes and RGB glass/water absorption. Colored directional transmittance, no duplicated local-light ownership or water absorption.
- Quarter-resolution stepped voxel/fuzzy clouds with history and matching raster/RT cloud shadows. Three dispersive macro-wave directions plus advected micro noise/rain. Aerial default0.00035, independent volume0.001/forward0.35.
- RTX Quality/Performance/Balanced/reference and Cinematic placeholder; debug views, independent A/B controls, memory caps, delayed CUDA/Vulkan profiling and adaptive quality integration. Keep opaque TAA when RT replaces SSR.
- Native Windows/Linux builds and 228 tests pass; headless mixin startup reaches missing-display graphics initialization. NVIDIA image/synchronization/performance acceptance remains pending. Full primary PT, focused RT caustics, full RT water scattering and complete independent offscreen dynamic extraction are not claimed. See [complete architecture and limits](RTX-PATH-TRACING-ARCHITECTURE.md).

## 0.35.2 — Rough material reflections and world-space GI reuse

- Roughness-aware environment Fresnel avoids mirror-like grazing sky reflections on diffuse blocks. Sharp screen reflections now require effective roughness below0.5, smoothly attenuated toward that threshold; rain and authored smooth/metal materials retain highlights. Direct GGX remains physically material-driven.
- Keep a bounded world-space irradiance cache for sampled static axis-aligned faces. Validate world cell, face and plane, apply the current surface albedo, and use it only where screen-space observations cannot reproject. World/resource/material edits and significant celestial/weather changes invalidate it. Freeze preserves its original screen-history semantics.
- Two128×128 RGBA32F images add0.5 MiB GPU storage and at most0.5 MiB upload per accepted batch; no additional samples per batch or full-resolution targets. The incoming batch retains its albedo guide (~3.5 MiB maximum) until consumed to separate irradiance from surface color. Moving/new views admit observations up to20 Hz (Fast10 Hz), settled refinement retains10/5 Hz. Increased submission rate can cost GPU/CPU time. Completely unknown faces still wait for tracing; this reduces rediscovery from zero, not all asynchronous acquisition latency.
- 223 tests pass, including actual shader/binding compilation, world-cache irradiance/color separation, collision tags, opposing faces, invalid data and reset. In-game gloss/motion/FPS acceptance pending.

## 0.35.1 — Water mask atlas lookup fix

- Resolve the block texture atlas through TextureManager, matching the existing water renderer; texture paths are not AtlasManager registry IDs. Fixes foundation disabling on entry.
- Missing water sprites or mask runtime failures retain a neutral mask and opaque lighting; report the local fallback in status. Resource reload resets the failure latch.
- Build/client kit and all 221 tests pass; in-game retry pending.

## 0.35.0 — Raster visual review bundle

- User confirmed PBR and single-raster MRT. Bundle the subsequent review milestones: procedural HDR Overworld sky/sun/moon/stars/weather, cheap world-space clouds and directional cloud shadows.
- Share compact half-res static camera velocity/depth/normal guides between opt-in opaque HDR TAA and quarter-res volume history. Temporal volume uses4/6/8 jittered steps with guide rejection/clamp; keep8/16/32 current-frame comparison. Dynamic/animated surfaces do not enter history.
- Add colored underwater absorption/scattering/shadowed shafts, native borrowed water depth mask for caustics, shallow-depth foam and filtered rain ripple slopes.
- Half-res solid PBR HZB SSR replaces the admitted sky-specular fraction; bilateral upscale and shared HZB allocation with opt-in water path. Misses/rough/unsupported materials preserve fallback; no extra local lights/PT samples.
- Optional GPU world-region budget controller with hysteresis/manual ceiling, separate world-region exports and performance/balanced/quality presets. TAA/adaptive/shadow epochs remain off by default. Existing CUDA GI/OptiX HDR denoiser preserved; no new RT-core/interop/full PT claim.
- 221 tests pass, including actual shaders/bindings, motion MRT order and adaptive hysteresis. GPU appearance/stability/FPS acceptance pending. [Review checklist and limits](REVIEW-COMPLETION.md).

## 0.31.1 — Single-raster native materials

- User confirmed0.31.0 working. Draw native opaque terrain once into native color/depth and all four material targets; share texture filtering, native alpha cutoff and retain native fog/color fallback. Copy native depth for existing consumers.
- Add `single_raster` on/off comparison, default on for eligible native Vulkan material modes. Preserve reference/local/wireframe paths; disable on integration failure. No new images, GI samples or larger vertices.
- 219 tests pass including actual native solid/cutout shaders, five ordered MRT outputs, native color/depth load and resized descriptors. GPU visuals and FPS pending.

## 0.31.0 — Material 2.0

- Add packed material ID/LUT and shading normal, vanilla texture profiles, GGX sun/moon and selected-lamp highlights, approximate sky specular and receiver-gated rain wetness. Geometry normal and GI tracing/history policies remain unchanged; composite suppresses diffuse GI on conductors and preserves material debug views.
- Support static LabPBR `_n`/`_s` normal, AO, roughness, reflectance/metal, porosity and emission channels. Animated maps, POM and SSS remain future work. Add PBR/wetness toggles and material debug views.
- One RGBA8 target (+4 B/pixel), bounded atlas/LUT storage up to32.25 MiB; terrain vertex stride remains36 bytes. 216 tests pass, including actual shader/binding compilation; in-game appearance and GPU timings pending.

## 0.30.7 — Faster new-view GI acquisition

- User confirms 0.30.6 no flicker on Vulkan. Remove new-only surfaces’ explicit fade from zero; retain continuous blending where old surface history exists.
- Bootstrap newly exposed surfaces with 32 samples; established surfaces keep eight. Compute the next observation during display blending, deferring replacement until the two-image transition finishes. No additional images.
- 212 Java tests and native normalization/established-surface regressions pass. First-exposure worker cost can increase; staged latency and stochastic refinement remain. GPU response and stability pending.

## 0.30.6 — Continuous display-time GI transitions

- Reproject and validate two observations in the Vulkan composite, blending over 150 ms rather than swapping GI in one frame. Keep valid older lighting where the incoming batch has no sample; fade in newly observed surfaces.
- Freeze holds/resume continues the blend. Trace admission waits for transition completion; one-job/10 Hz cap retained. Three additional low-res float4 textures (10.55 MiB maximum), no new full-res target or rays.
- 212 Java tests including freeze/resume and transition scheduling pass; actual shaders/bindings compile. Freeze is user-confirmed working in 0.30.5; resumed flicker still needed this follow-up. GPU stability/ghosting and cost pending.

## 0.30.5 — Display lifetime / post-denoiser stability

- Retain surface-valid displayed GI through material revisions; accumulation still invalidates independently. Explicit world/resource resets protect lifetime. Freeze accepts a first valid observation before holding it, instead of freezing an empty image. Add display-valid status.
- Guide-validate and temporally blend the actual OptiX HDR output before upload; spatial denoiser changes are now filtered too. Two low-res float4 images (~7.0 MiB maximum), no new rays or full-res targets.
- 210 Java tests and Windows/Linux native builds pass. GPU freeze, residual flicker and ghosting acceptance pending.

## 0.30.4 — Residual motion flicker

- Gather compatible projected history instead of choosing a single nearest texel; retain grazing-angle coplanar history without radial-distance rejection. Keep plane/normal/proximity checks and add sparse-surface composite fallback.
- Include observation innovation in variance clamping, preventing one noisy dark batch from erasing stable GI. Reject different-plane neighbors. No additional GPU images or rays.
- 209 Java tests and expanded native CPU checks pass, including grazing surfaces, sparse valid neighbors and dark batch retention. User reports 0.30.3 reduced flicker frequency; 0.30.4 GPU motion/ghosting checks pending.

## 0.30.3 — Persistent hybrid GI stability

- Replace camera-reset global accumulation with eight-sample observations and persistent per-pixel EMA, confidence and luminance moments. Reproject, reject disocclusions and clamp history before OptiX HDR denoising; sun/weather bins and proxy-origin changes preserve compatible histories.
- Replace PT 8-ULP depth gates with view-space validation and reconstruct primary positions from native depth. Decode primary/secondary albedo with piecewise sRGB; raster owns first-hit emitter direct light. Add proxy hysteresis and remove global eight-block camera admission.
- Add freeze, history A/B, colored rejection and bounded delayed-upload diagnostics. Actual Minecraft 26.2 staging-copy lifetime verified. Preserve existing raster defaults/density.
- 209 Java tests, actual shader contracts, native CPU traversal/history tests, CUDA PTX and Windows/Linux native builds pass. GPU flicker/ghosting and cost remain pending. See [stability notes](PT-STABILITY.md).

## 0.30.2 — Reproject indirect lighting during camera motion

- Remove stationary-only scheduling and sample-count brightness ramp. Reproject surface-valid traced lighting through the captured camera, with normal/depth/offscreen rejection; motion no longer clears all GI.
- Eight samples per reset batch before OptiX denoising; progressive follow-up remains one sample. One job in flight and 10 Hz submission cap retained. New surfaces/window changes can still lose history; in-game acceptance pending.

## 0.30.1 — Hybrid accumulation stability

- Replace global scene/task-version invalidation with order-independent local material fingerprints. LIGHT-only snapshot replacement and distant caster churn no longer discard GI. Known secondary snapshots remain until replacement.
- Tolerate sub-millimetre camera/float noise; require 250 ms stationary admission before tracing. Genuine movement, local material edits, world/resource changes and resize still reject stale surfaces. Celestial refresh resets accumulation while retaining the last valid surface result until replacement. Eight-sample strength ramp reduces initial noise/pop-in.
- User log confirms the native worker executes, but 0.30.0 repeatedly returned raster with zero accepted samples. In-game stability verification pending.

## 0.30.0 — Experimental hybrid diffuse path tracing / OptiX denoising

- Opt-in raster-primary CUDA voxel secondary paths, three diffuse segments, secondary emission/sun/sky, static-camera progressive accumulation and real OptiX 9.1 HDR guide denoising. Indirect-only and raw/denoised comparison commands.
- Asynchronous low-resolution Vulkan readback, single native worker, UUID-matched device, bounded resources, generation/scene/camera rejection and HDR composite. Native x64 Windows/Linux component packaged; missing driver/component retains raster.
- Bounded cube/map-color secondary proxy and staged transfer are experimental limitations; no full-primary PT, RT-core traversal, zero-copy interop or FPS improvement claim. 202 Java tests and native CPU traversal/sampling checks pass; NVIDIA in-game test pending.

## 0.29.0 — Conservative depth pyramid / hierarchical SSR

- Add opt-in water_hzb reflection path: half-resolutionR32 reversed-Z maximum-depth mip chain, odd-tail safe reduction and bounded perspective cell traversal with full-resolution hit validation. Keep linear trace as default/reference.
-16 MiB pyramid cap; added water_hzb profiler stage and status bytes/visit budgets. Release on disabled/resize/world/resource paths; preserve control and linear fallback on failure.
-199 tests pass including actual Vulkan shader/binding/UBO contracts, odd-sized thin-occluder coverage, traversal math and resource control checks. No in-game FPS or quality measurements yet.

## 0.28.0 — Surface shadow filter budgets

- Default surface PCF now16 taps per cascade; independent shadow_filter fast/balanced/high gives4/16/36 taps. High preserves the existing5-texel filter; reduced budgets use narrower footprints.
- Preserve continuous subtexel phase weights, receiver-plane depth correction, world-space bias, terrain/dynamic union, cascade blending and distance. Filter changes invalidate optional temporal history. No new targets/resources; volume/reflection quality unchanged.
- User reports temporal off improves15 FPS; it remains off.194 tests pass including real shader compilation, binding/UBO checks, phase normalization and scroll continuity. In-game shadow appearance and performance pending.

## 0.27.0 — Packed material buffers / temporal opt-in

- Pack normal and emission/light MRTs as RGBA8_UNORM; material targets drop24→16 bytes/pixel, preserving signed normals, supported-surface markers and native chunk visibility. HDR radiance staysRGBA16F.
- Temporal shadows now default off; opt-in remains for stability/performance comparison. Epochs remain off; static foliage cache remains active.
- Native Vulkan shader/binding tests and packing precision checks pass;191 tests. FPS/visual acceptance pending; no GPU available here.

## 0.26.1

- Default celestial shadow epoch blending off after user-reported FPS regression; preserve static cutout caching and opt-in comparison.
- Extra endpoint sampling is a suspected bottleneck, pending GPU pass profiling.


## 0.26.0 — Celestial terrain epochs / static cutout caching

Two fixed-angle terrain maps per cascade, visibility interpolation, recycled endpoint allocations with a third look-ahead set and8-page future initialization. Both published epochs repair edits/anchor changes; current-frame dynamic shadow taps are reprojected and combined before PCF. Static/animated classification comes from existing CPU vertex metadata, owned by each compiled native mesh; raw/unknown emitters remain conservative. ExtraD32 textures at most48 MiB, resolve UBO1344 bytes, reference toggle `shadow_epochs off`. Native shader/math/mixin checks pass; visual quality/FPS unmeasured. Density0.001 and water ripples0.09 preserved. See[acceptance](SHADOW-EPOCHS.md).


## 0.25.1 — Water motion / CPU hotspot / baseline follow-up

Direct36-byte BLOCK writer preserves vanilla reservation and packing; spatial native-shadow admission outside dispatcher lock with cached conservative candidates and fresh per-frame allocation borrowing. Smaller six-band filtered water normals, default strength0.09. Optional `/voxellight profile on/off`, exported per-pass CPU/GPU samples and summary utility. Density0.001 retained. No claim of measured speedup, displaced water, resolved continuous-sun cache or new GI. See TEST-0.25.1.md.

## 0.25.0 — Three-phase visual refinement bundle

- User confirmed0.24 and requested three phases before another in-game test; deliver one client kit with three connected milestones.
- Phase1: HDR water screen-space reflections reuse immutable opaque depth/pre-tone radiance, with bounded48-block trace,5 binary refinements, native projection, radial HDR guide checks and edge/distance/thickness fade to sky. No reflection target or RGB history.
- Phase2: depth-guided separable5-tap volumetric spatial filtering; one extra quarter-resolutionRGBA16F scratch,8MiB combined target cap, toggleable. Filter transmittance and scattering together, preserving constant fields and rejecting foreground/background depth discontinuities.
- Phase3: three animated normal-wave harmonics, default strength0.12/speed1, bounded monotonic phase, modulo64 spatial continuity, phase-preserving speed changes. Native water vertices remain unchanged.
- Add water_reflections, water_waves, water_wave_strength, water_wave_speed, volumetric_filter and quality controls. Fast/balanced/high use8/16,16/24,32/32 volume/reflection steps. Keep density0.001, existing exposure/light/shadow-distance budgets and native fallback.
- Validate actual native water and filter shader linkage, uniform sizes, sample bounds/medium energy, resize/budget admission, wave continuity and resource-close control preservation. In-game visual/performance acceptance pending.

## 0.24.0 — Quarter-resolution shadowed volumetric lighting

- User confirmed0.23 working. Keep preferred atmosphere density0.001 and128-block directional shadows.
- Add a16-step current-frame air-path integrator with fixed spatial jitter, height density, forward scattering, and blended terrain/dynamic cascade visibility. No volumetric history or moving-frame noise.
- One quarter-resolutionRGBA16F stores HDR in-scattering and transmittance;8MiB cap, neutral fallback, resize/reload cleanup and status counters. Full-resolution composition uses depth-guided2×2 upscale to reject silhouette-crossing fog.
- Replace analytic haze on supported opaque HDR pixels, before bloom/exposure/tone mapping and native fog; preserve the existing analytic water path and native sky/unsupported/underwater/non-Overworld behavior.
- `/voxellight volumetric on|off` compares shadowed medium to the previous analytic path. Ambient sky occlusion and uncompiled-caster coverage remain stated approximations; GPU/visual acceptance pending.

## 0.23.0 — Extended directional shadows without a larger duplicate cache

- User accepted0.22.1 native material coverage and prefers atmosphere density0.001; adopt that default.
- Extend directional receivers to128 blocks, with16-block near transition,40–48 middle/far transition and120–128 outer fade. Keep2048/1024/1024 map dimensions and original near projection; coarser distant density.
- Borrow compiled solid/cutout geometry from all loaded native sections intersecting a conservative receiver/light capsule, including offscreen sections. Keep independent near casters; do not enlarge the384-section scene bridge or32MiB duplicate caster budget.
- Refresh borrowed allocations every frame under dispatcher lock, preserve index offsets/types and36-byte base-vertex stride; invalidate changed/removed native caster bounds. No native allocation ownership/compilation/upload.
- Increase projection depth span for farther casters and derive comparison bias from each cascade matrix to retain the accepted world-space bias.
- Report native section/layer/pending/selection metrics. Uncompiled offscreen casters and large modded model overhangs remain limitations. Automated tests pass; GPU performance and visual acceptance pending.

## 0.22.1 — Capture Fabric Indigo terrain materials

- User log showed934 borrowed draws without working terrain lighting. Fabric API redirects SectionCompiler tessellation to AltModelBlockRenderer and ignores the decorated vanilla output. The vanilla-only startup writer check did not cover this route.
- Capture each Indigo quad’s unlit vertex color/tint, geometry normal, emission and flags before AO/face shading; attach metadata to the quad and consume it during its actual buffer call. Clear reusable quad state and restore nested per-vertex scopes on failure.
- Startup regression verifies transformed Indigo buffer emission/clear as well as vanilla packing. Tests pin the Fabric redirect and cover pre-lighting attributes and nested scope cleanup.
- Status reports actual coverage-blend admission and cumulative Indigo material emissions; draw count alone is not a validity claim. Native Vulkan visuals still require in-game retest.

## 0.22.0 — Native visible terrain material coverage

- Capture real normal, unlit tint, independent block/model emission and flags during the existing native SectionCompiler output; preserve native shaded color, position, UV and light offsets. Inline BLOCK stride28→36 adds8 bytes/vertex even with effects off.
- Borrow native visible solid/cutout draw lists and section transforms for an extra MRT pass; no material re-tessellation, duplicate material GPU meshes, scene-window admission or one-build/frame warm-up on the native path. Native light updates still rebuild native geometry.
- Disable the32-block full-lighting/water fade for the native source; retain raw-emitter/depth/medium fallback, native chunk visibility and independent near-field shadow/local-light limits. `/voxellight native_material off` retains the old local comparison path.
- Single-raster native MRT and longer shadow coverage remain future work; no FPS improvement claim. Automated shaders, packing and pinned mixin selectors are checked; native writer/startup and in-game validation are recorded in NATIVE-COVERAGE.


历史说明保留当时参数与验收状态；当前状态以[CURRENT.md](CURRENT.md)为准，操作与最新安装见[INSTALL.md](INSTALL.md)。

## 0.21.1 water seam fix

- Screenshot2026-10-03_11.44.01.png exposed blue grid/dash artifacts.
- Evaluate water surface derivatives before native alpha discard and divergent sprite/background fallback; replace absolute screen-scale normal cutoff with finite/zero validation.
- Bound UV-edge rounding tolerance; retain depth/background guards. Runtime screenshot retest pending.

## 0.21.0 Water Foundation

- Split opaque lighting/history from VisualComposite atmosphere/bloom/display ownership.
- Native-stream water shades from one pre-tone HDR background, with an immutable depth snapshot, Fresnel, absorption, sky reflection, animated normals and guarded refraction.
- Preserve native terrain shader fallback and BLOCK/depth/blend/draw contracts; no duplicate water geometry or LDR SceneColor input.
- Nearby Fast/Fancy supported backgrounds only; underwater/Fabulous/unsupported pixels retain native water.96MiB additional image cap.
- `/voxellight water on|off`; retain density0.002 and all prior effect controls. In-game acceptance pending, no SSR/GI/volumetric expansion.

## 0.20.1 atmosphere tuning

- Adopt user-preferred atmosphere density0.002 instead of0.018.
- User confirmed0.20 atmosphere working; existing density command/range retained.

## 0.20.0 analytic aerial perspective

- Bounded height-density extinction/scattering before tone mapping, rain density and analytic directional glow.
- Overworld dry-camera polished foundation only; native medium fog, unsupported/transparent/sky rendering retained.
- `atmosphere on|off`, `atmosphere_density 0..0.08`;16-byte settings, no added target/history/geometry.
- Not shadowed volumetric shafts.0.19 confirmed working;0.20 in-game acceptance pending.

## 0.19.0 local-light materials and held source

- Replace registry-name color heuristics with bounded exact-ID resource-pack JSON; transactional malformed-file fallback and resource-generation reload.
- One interpolated local player's held emissive BlockItem source, choosing the brighter hand;15 static +1 dynamic within the existing16-light cap.
- Held contribution is independent of vanilla block-light replacement, with the existing shape visibility/falloff and no RGB history.
- `/voxellight held_lights on|off`; source/profile/fallback counts in status.0.18 confirmed working;0.19 in-game acceptance pending.

## 0.18.0 lighting/color polish

- Manual EV and rational filmic tone curve, continuous hemisphere sky colors and weather/night policy.
- Emissive terrain bloom: quarter-resolution extraction + separable blur,16MiB target cap, no extra geometry or SceneColor copy.
- Supported foundation output fades24–32 blocks to native inside the guaranteed material window; main alpha preserved.
- `look polished|reference`, `exposure -2..2`, `bloom on|off`, `coverage_blend on|off`; reference restores0.17 color policy.
- AO confirmed operational by user but visually modest.0.18 visual acceptance remains pending; lava/transparent/sky bloom and atmosphere/water remain outside this phase.

## 0.17.0 basic terrain AO

- Half-resolution horizon AO, depth/normal bilateral spatial filter and full-resolution upsample.
- Ambient/sky and unshadowed block fill receive AO; direct sun/moon/local lamps and emission remain current.
- `/voxellight ao on|off|view`, neutral fallback,32 MiB extra target cap; no AO history.
- 0.16.1 stability accepted by user; AO awaits in-game acceptance.

## 0.16.1 stability follow-up

- Retain stale material meshes until a verified replacement; bounded1 MiB staging over16 MiB resident cap. No cross-world/resource/unload retention.
- Directional history ignores LIGHT-only dirty events, retaining geometry/lifetime invalidation.
- Actual projection inverse and three light normal matrices uploaded once per world frame; shared resolve UBO560 bytes.
- Review prioritizes coverage stability before AO; no temporal expansion or quality/performance claim without in-game evidence.

## Earlier release notes

0.5.0 的 tile 缓存继续推进 P1b：一张现有 map 划分为 8×8 个 256² tile，mesh 增删/失效按实际顶点 AABB 的光空间投影失效旧/新足迹，合并脏 tile 的矩形并重绘所有当前重叠 caster。不新增 map 内存。`updatedPages=N/64`、`pageUpdates/pageReuses/updateRegions` 在 status/export txt 中可见；cell 跨界与 reset 仍全更新，编辑 mesh 重建仍可能暂时漏影。未实现三层 clipmap、虚拟分页/预算调度或动态实体层，实际性能待测。

0.5.1 的编辑修复：shadow geometry 使用客户端当前 world/resource/version token，不再等待 CPU occupancy 编码。最多 8 个已驻留 section 的编辑在一次 prepare 内构建/检查/替换，再绘制脏 tile；新增驻留仍每帧一个。32 MiB steady geometry 外允许至多 8 MiB replacement staging，每 section 仍 4 MiB。大组编辑、预算不足或未加载邻居安全退回局部暖机，可能漏影；不跨帧保留陈旧 caster。单个编辑帧 CPU 耗时可能增加，status 的 peakBuildNs/replacementBatches/replacementFallbacks 可观察。network readSectionList 的 light-only rebuild 不再推进几何版本。

0.6.0 历史实现（已由下述 0.7.0 替代）：默认 `/voxellight sun world`：按 native SkyRenderState 选择当前主导太阳/月亮，方向按 0.025° 取整，变化立即使 64 个 tile 全更新。雨天减弱对比、月亮较弱且依月相、近地平线淡出；world 模式只额外调暗朝向光源的表面。`/voxellight sun fixed` 保留 0.5.1 固定光向/对比与比较路径。`shadow_cache off/on` 在两个 sun 模式中仍使用相同取整方向/强度/材质/投影。保持 24 格接收与 125 section caster 窗口、20 MiB map，不承诺真实太阳光分离或实机性能。

## 0.7.0 连续光源、月光与人工光源（历史）

修复路径：取消世界光方向取整和旋转时按全局坐标 texel resnap；shadow anchor 带 8 格滞回，避免小幅往返换 map。重建法线接近轴向时吸附为真实方块面，掠射面更宽淡出，连续 5×5 PCF（36 次 comparison）降低 raster shimmer。没有 temporal history，不承诺消除薄模型/深度边缘所有 aliasing。

月光不再只是调暗已有画面：满月最高 0.22 的遮影强度加冷色 fill，仍按月相、雨天和高度衰减；新月没有月光。`/voxellight local_lights on|off` 独立切换人工光源（默认 on，随 shadow 模式运行）。torch/lantern/soul 等由实际 emission 选取颜色，覆盖最多 16 个光源、每 section 64 个 4×4×4 cell 代表；GPU 80³ R8 atlas 对 full-block 做 DDA 遮挡，未知空间挡光。不处理手持/实体灯或透明/半砖精细遮挡，不是 GI；vanilla lightmap 保留，因此是额外的局部 fill。

资源与实机检查见 [安装说明](INSTALL.md) 与 [阴影/局部灯预算](docs/SHADOWS.md)。GPU 成本尚无实机数据；缓存关闭仍使用相同连续方向、PCF 与局部灯。

## 0.8.0 重叠阴影范围与视角稳定性

三张 map 为 near 2048²/±32 格、middle 1024²/±64 格、far 1024²/±96 格，depth span=255 格；共享当前 sun/moon、同一组独立 caster 和带滞回 anchor，各自拥有 tile cache。距离按重建世界位置的球面半径选取，12–16、26–32 格平滑 blend，40–48 格平滑退出；默认更远、更软的阴影不代表整个视距或所有远处 caster 完整覆盖。map+write-disabled attachments 共 30 MiB。

`/voxellight shadow_distance 24` 和 `48` 用于隔离距离范围影响（允许 12..48 格）；只改 receiver fade，不换 caster/projection/filter。`/voxellight mode shadow_ranges` 显示 near 绿/middle 橙/far 蓝和渐变，转头时同一个位置应保持范围颜色；`mode shadow` 恢复照明。`shadow_map` 改为 near/middle/far 三个横向 panel。

两步 depth extrapolation 选择属于当前 plane 的邻居，避免单纯选深度最近的一侧在某些视角切到别的方块；near-axis normal 用连续混合代替 >0.98 硬吸附。预算不足的 section 记录 token/请求字节，远处 casters 可为更近 geometry 腾出空间，不互相来回替换、不反复编译已知无法容纳的同版本数据；保留有效遮挡并报告 partial coverage。其视觉效果、薄模型边缘和实际 GPU 成本仍需实机验证。

0.9.0 默认使用半砖/楼梯/栅栏的原生遮挡形状。`/voxellight light_occlusion shapes|full` 可与此前 full-block 行为比较；详细预算与实机检查见安装说明。

0.10.0 增加独立太阳/月亮动态实体模型阴影：`/voxellight entity_shadows on|off`。最多 32 个附近实体、1 MiB/frame 模型顶点，使用原生动画/纹理 cutout；具体支持范围与 24 MiB 增量深度层预算见安装说明。

## 0.11.0 Material diagnostics（B1）

新增 `/voxellight mode albedo`、`surface_normal`、`emission`、`material_flags`、`material_coverage`。捕获原生 quad geometry normal、未照明 texture/tint 与 emission strength，使用三 target MRT 和独立 reversed-Z；仅局部 terrain，支持 coverage 可观测。现有 `shadow` 与 depth `normal` 保留；0.12.0 新增分离 lighting/HDR，见下文。详细范围、显存和实机门槛见 [安装说明](INSTALL.md)。

## 0.12.0 separated terrain lighting（B2）

`/voxellight mode foundation` 从 unlit material/真实 normal 计算 sun/moon、hemisphere sky、block/local light 和参考 emission，输出 linear HDR，再 tone map 与 native fog。太阳阴影只影响 direct term；unsupported geometry 保留 native，entities/transparency/UI 随后合成。`mode shadow` 是旧版比较，`mode off` 恢复 vanilla。无 SceneColor copy，现有 material/caster 窗口与预算仍有限，实机验收待完成。具体光照模型、显存与测试步骤见 [安装说明](INSTALL.md) 和 [Foundation contract](docs/VISUAL-FOUNDATION.md)。

## 0.13.0 B3a block-entity shadows

新增 chest/shulker/banner 等 native model submit 的动态投影，默认开启；`/voxellight block_entity_shadows off` 可比较。来自已加载 chunk 的独立最近32个选择，不依赖 camera visible list；与 mob 共用已有 dynamic depth，单独1 MiB/128 model budget。`foundation`/`shadow`/`shadow_map` 均复用此层。Native26.2 beds 已为普通模型，仍走 terrain。实机检查与边界见 [安装说明](INSTALL.md)。B3 实体材质迁移将随后推进；light-aware caster volume 见0.14.0。


## 0.14.0 B3b light-aware caster selection

默认从48格 receiver 球向 sun/moon 扩展16..48格，优先本地125 sections，只选择已加载地形，scene cap384与terrain32 MiB预算不变。`/voxellight caster_volume cube` / `light` 比较旧窗口；低太阳角度、较远建筑的附近阴影最容易看到差异。scene status显示搜索/已加载候选/限额排除与扩展距离；未加载或预算排除的caster仍可能缺失。完整实机检查见安装说明。实体材质第一步见0.15.0，未进入GI。


## 0.15.0 B3c opaque entity material lighting

实际native ModelFeatureRenderer顶点在原render调用中同步捕获ENTITY格式，保留pose、sprite UV、unlit tint、overlay、packed light与normal，不重新提取/运行动画。支持原生solid/cutout模型与普通armor，部分opaque block-entity模型也走同一路径；在solid feature结束后、translucency前使用同帧shadow/local-light数据完成分离HDR lighting。`/voxellight entity_materials off` / `on` 比较仅新增的实体材质光照，terrain与shadow仍启用。

最多128 model attempts/1 MiB frame/256 KiB model scratch，复用现有MRT/HDR，不新增全屏targets。玩家blended skin、eyes/glow、held item/custom submits仍native；armor trim以coverage exclusion保留native像素，glint/transparency随后native绘制。该阶段支持范围与实机验收见安装说明；用户已确认。

## 0.16.0 D1 temporal shadow stability

`foundation` 默认对静态terrain太阳/月亮阴影visibility做camera reprojection与depth/normal rejection，保留当前texture/emission/local lighting；动态阴影区域不进入history。`/voxellight temporal_shadows off` / `on`比较小幅shadow crawl。新增32 bytes/pixel、128 MiB cap，1440p支持，4K回退当前阴影。编辑/reload/camera cut重置history；不是full TAA。详细验收见[安装说明](INSTALL.md)。
