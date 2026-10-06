## alpha.28 自动 A/B 验收

新增 `rt_benchmark start/status/stop`，按设备能力执行两轮 ABBA，隔离采样帧并接收延迟 GPU 数据，导出原始 CSV、状态、汇总与 ZIP；工作负载变化或波动范围内不宣称收益。完整说明见 [自动验收](performance/RT-AUTOMATIC-BENCHMARK.md)。执行层算法保持 alpha.27，RTX 实测待用户运行。

# 0.39.0-alpha.27 — Vulkan RT execution round 1

Material section BLAS now contains OPAQUE/CUTOUT/TRANSMISSION ranges with matching geometry-index SBT records. Opaque traversal bypasses any-hit; a separate opaque visibility AS supports a 4-byte first-hit TraceRay payload and an optional Ray Query variant. PathHot is 64B (was 144B), radiance/AOV accumulate outside continuation, and one scene scratch arena replaces per-AS scratch. Queue AUTO joins delayed GPU timings with real alive curves, with forced fixed/compact A/B controls. OMM uses conservative triangle special indices and exact unknown any-hit fallback; mixed-alpha subtriangle baking is not implemented. SER is an explicit, feature-gated final A/B, default off.

Profiling replays a bounded set of real visibility rays with alternating TraceRay/Query order and mismatch counters. Driver compiler statistics export when available; L1/L2/runtime spills require external GPU profiling. Numerical/unit/shader validation does not replace RTX visual/performance acceptance; 1.3–2× and execution-layer freeze are not claimed. See [implementation and acceptance protocol](performance/RT-EXECUTION-ROUND-1.md).

# 0.39.0-alpha.26 — recover fragmented scene geometry ranges

Alpha.25 cleared output history successfully in the reported run, then fell back to raster when allocating shader geometry ranges. Allocation now releases all deleted and resized ranges before allocating any incoming/replacement range, preventing a growing mesh from exhausting capacity while later shrinking meshes still occupy their old ranges. If final geometry fits the admitted budget but holes cannot fit an incoming range, the attribute arena is repacked under the existing GPU read-to-write dependency. This exceptional path recopies attributes/normals, updates TLAS custom indices, rebuilds emitter proposals and invalidates reconstruction/previous-pose history. Ordinary updates retain stable offsets; surviving surface identities are preserved. Stats exposes geometryCompactions.

Regressions exercise growth/shrink at full capacity, fragmentation with sufficient total space and rejected oversized layouts without mutation. This addresses the reported allocator exception; NVIDIA driver/visual/performance acceptance remains pending.

# 0.39.0-alpha.25 — allow clearing temporal upscale history

Alpha.24 successfully initialized Vulkan RT on the reported RTX 4060 Laptop GPU, then failed at the first output-history clear: Minecraft 26.2 requires both RENDER_ATTACHMENT and COPY_DST for clearColorTexture. Output HDR history textures now include COPY_DST, preserving sampling/rendering flags and RGBA32F history. This fixes the reported validation exception that forced raster fallback. A regression executes the actual Minecraft CommandEncoder validation with a stub backend, and verifies the old flags fail before reaching the backend. Driver rendering and visual acceptance remain pending.

# 0.39.0-alpha.24 — persistent scene, GPU continuation queues and motion reconstruction

Terrain and dynamic changes are collected before one scene commit. Shader geometry uses stable free-list ranges; dynamic object identities and local mesh translations replace texture-only grouping. Same-layout BLAS/TLAS updates reuse AS storage, scratch and vertex/instance buffers. Native Vulkan texture-write versions avoid unchanged crop/albedo uploads; terrain-generation caching avoids rebuilding static emitter proposals for dynamic motion. Persistent fenced descriptor slots replace per-frame pools.

Continuation storage separates 144-byte hot state from 288-byte cold media, retaining the 432-byte/path capacity. On devices supporting indirect tracing, workloads of at least 65,536 paths use two bounded GPU active queues with original path indices; smaller workloads keep fixed dispatches. This threshold is provisional. Primary guides use the actual jittered hit, eliminating a second visibility ray. Compatible dynamic surfaces map barycentrics through previous geometry/translation; stable identities reject replaced topology. View models appear in primary visibility and are excluded from world continuation/shadow rays.

Realtime beauty denoising now feeds a separate output-resolution RGBA32F temporal upscaler. Pass exports contain real frame/scope/parent IDs, dimensions, spp and scene generation; sampled ray counters export separately. Buffer allocation/retirement counters cover owned Vulkan RT buffers only. See [implementation and remaining design work](architecture/IMPLEMENTATION.md) for exact ABI/memory contracts and limits.

Build, shader-link, SPIR-V and CPU transport validation passed. No NVIDIA GPU is available on this host: driver synchronization, visual motion/upscale quality and matched-workload performance remain pending. NRD/DLSS RR, ReSTIR, complete separated-signal reconstruction, render-origin rebasing, advanced coverage/LOD/cache and caustics are not implemented by this release.

# 0.39.0-alpha.23 — decouple cropped textures from dynamic BLAS groups

Alpha.22 fixed sprite detail and terrain relocation but incorrectly used each cropped texture tile as a geometry group identity. The new profile showed 86 dynamic groups versus 7 previously, with BLAS CPU median rising from 1.296 to 8.874 ms. Dynamic groups now use the original native texture view plus hand/world category; each triangle retains its independent crop tile in its material flags. Animated source views keep stable group IDs, inactive IDs are reclaimed, and accepted triangle texture slots are tracked separately from geometry groups for uploads. Cropped sprite detail, HUD projection and terrain-first packing remain intact.

The submitted alpha.22 profile confirms geometry copy GPU median 0.034 ms versus alpha.21 2.017 ms; last copied bytes 369,360 versus 63,688,080. It also shows regressions: whole-world GPU median 33.117 ms versus 16.710 ms; RT batch 23.718 versus 10.717 ms; OptiX exchange 3.958 versus 2.135 ms; cropped texture upload 1.593 versus 0.178 ms. These are observed timings, not controlled causal comparisons: both final statuses report 214x120 and 1 spp, but alpha.21 retains an earlier 8-spp diagnostic and per-pass exports lack per-dispatch spp/clock metadata. More groups directly explain increased BLAS work; the full GPU slowdown needs matched scene/sampling and GPU clock measurements after regrouping.

The selected fix reduces BLAS grouping without reverting the visual correction. It does not yet eliminate per-frame crop tile uploads, add transform-only instances, integrate NRD/DLSS or replace scene packing with an allocator. New status `rtDynamicTextureTiles` is independent from `rtDynamicGroups` so future profiles can distinguish texture and geometry workloads.

Validation includes two distinct crop/material slots sharing a world BLAS identity while hand geometry remains separate, existing sprite/FOV tests, native GLSL binding checks and transport regressions. RTX quality/performance acceptance remains pending; this build host has no NVIDIA GPU.

# 0.39.0-alpha.22 — held texture and projection corrections

Dynamic atlas textures now crop the captured model UV region before resampling and remap triangle UVs to that region. A 16-pixel item sprite in a 4096-wide atlas retains its own 128-pixel tile instead of collapsing below one pixel. Small entity textures retain whole-texture slots. First-person native geometry compensates for HUD versus world FOV before native item transforms, preserving its screen footprint when the world FOV changes.

Packed geometry orders static terrain before animated groups, with the same order used for normals, TLAS custom indices and emitter references. Dynamic size changes consequently relocate dynamic groups rather than the terrain suffix. Terrain eviction/edit size changes can still relocate terrain; a free-list allocator and per-object instancing remain future work.

The uploaded alpha.21 profile contains 611 GPU batch samples: median 10.717 ms, P95 13.986 ms; OptiX exchange median 2.135 ms, P95 2.607 ms. These are pass timings from the uploaded run, not isolated fixed-spp comparisons: the exported status says 214x120, requested 1 spp, while its retained diagnostic reports an earlier 8-spp frame. Scene CPU median 7.260 ms includes two scene records per rendered frame; it must not be interpreted as full-frame time. Last geometry copy was 63,688,080 bytes. Further performance comparisons require matched resolution and spp.

Validation adds atlas sprite footprint, HUD/world projection equivalence and actual native GLSL pipeline binding checks. No NVIDIA GPU is available on the build host; held appearance and reduced geometry transfer need RTX acceptance. Glint, full dynamic LabPBR and first-person hurt/view bob parity remain pending.

# 0.39.0-alpha.21 — remove redundant work and stabilize reconstruction

PT bypasses raster material/entity captures, cascade shadow preparation, AO, water reflection, volumetric and raster composites; world light parameters, native PBR assets and HDR sky remain prepared. Vanilla rendering remains available during RT warmup/failure. OptiX and Vulkan reconstruction are mutually exclusive per frame; the temporal AOV model now denoises only the beauty layer that is actually displayed. External staging is six float4 planes rather than twelve, and only one previous output plane is retained.

Dynamic native quads are grouped by stable texture slot and hand/world category. Same-count dynamic geometry uses out-of-place BLAS UPDATE from an ALLOW_UPDATE build; topology/count changes build a replacement. Packed shader geometry/normal buffers persist, copying only changed or relocated sections. Queue barriers preserve previous-frame shader reads before buffer overwrites. Texture uploads copy only active 128² tiles directly into packed asset cells, avoiding a full 2048² atlas copy. Stats expose `rtDynamicGroups`, `blasRefits`, `geometryCopyBytes` and `rtDynamicTextureCopyBytes`. This is grouping/refit, not per-object transform-only instancing; animated CPU extraction and TLAS rebuilding remain.

Realtime guides use one stable pixel-center visibility ray independent of noisy sample jitter. That extra ray is disabled in reference mode. Camera-space normal, previous-to-current pixel flow and confidence are produced in one three-target pass. Reprojection validates surface-plane distance and pixel footprint, materials and albedo; sky history follows rotation. Dynamic objects conservatively reject history until true object motion is available. Specular/transmission motion, temporal upscaling and NRD/DLSS integration remain pending; low internal resolution is still visible after denoising.

`profile on` now records primary, bounces 1–5 and sample resolve directly inside the same Vulkan command buffer, plus geometry copying, dynamic capture, guide conversion and OptiX exchange. No added RT dispatch/submission is needed for timestamps. Export `.passes.csv` after a fixed-resolution run; the OptiX exchange timing includes the external wait, not a pure CUDA kernel-only measurement.

Validation includes actual GLSL-to-Slang CPU fixtures for pixel flow direction, surface-footprint acceptance, plane/disocclusion rejection, dynamic rejection, normal/material/albedo validation and sky rotation, plus actual Minecraft MRT output-order validation and existing transport tests. Driver-level BLAS refit, native synchronization, visual quality and performance remain RTX acceptance gates; the build host has no NVIDIA GPU.

References: [Vulkan AS update constraints](https://docs.vulkan.org/spec/latest/chapters/accelstructures.html), [OptiX denoiser layer contract](https://raytracing-docs.nvidia.com/optix8/api/optix__host_8h.html). No driver performance gain is claimed from CPU tests.

# 0.39.0-alpha.20 — realtime reconstruction and batched Vulkan transport

Vulkan retains all tracing. The independent OptiX Temporal AOV helper consumes GPU beauty/albedo/camera-normal/flow/trust and diffuse/reflection/refraction inputs through external memory and binary semaphores; failure retains Vulkan temporal/spatial filtering. Moving-camera history validates RT positions, normals, materials and light changes. Reference progressive accumulation is separate (`rt_mode reference`), with explicit freeze.

Multi-spp uses one camera upload, batched primary and five continuation passes, one sample resolve and one radiance copy. Separate path banks obey the device storage-buffer range; stats report actual internal resolution and memory caps. Flame connections retain nearest two plus one visibility-tested RIS selection, independently of held lighting. A compressed CPU page cache and asynchronous miss requests prioritize loaded terrain within the bounded GPU working set.

Native entity/block entity, first-person hands/items, custom quad and cutout quad particle geometry now enters the Vulkan scene using native texture/tint diffuse materials. Native hand overlay is suppressed only after successful PT display and hand capture. Transparent/additive particles, glint and full dynamic LabPBR parity remain pending. Responsibilities are split into VulkanPathTracer, RtDynamicScene, RtReconstruction, RtDiagnostics and scene/cache owners.

Validation: 254 Java tests, 14 SPIR-V stages, Minecraft GLSL linking, actual Slang numerical parity and 200,000 flame RIS samples with occlusion passed. Both host-only denoiser bridges build. This host has no NVIDIA GPU: interop, visual quality, dynamic coverage and frame-time acceptance still need RTX testing. DLSS RR and optional OMM/SER remain deferred until reconstruction profiling, as requested by the review.

# 0.39.0-alpha.15 — independent held-light connection

Alpha.14 held lighting still failed user visual acceptance. Its delta point light shared stochastic selection with sun/sky, allowing high environment power to starve held connections. It now has a separate deterministic inverse-square connection at eligible surfaces and medium events; sky/sun distribution and complementary miss MIS exclude the delta source. Visibility and material shading remain intact; no light leaks or unconditional full-screen brightness are substituted. Status adds main/offhand item IDs and virtual source position to distinguish recognition from transport failures. Visual acceptance remains pending; no RTX GPU is available on the build host.

Regression executes actual Slang with held on/off, black and million-unit sky maps and verifies discrete PDF=1 and analytic Lambertian held irradiance independent of sky power. Existing Material 3, terrain, environment MIS, invalid accumulation and emitter sampling checks remain required.

# 0.39.0-alpha.14 — live accumulation, numerical rejection and emissive NEE

Default history now continues tracing at its sample target with bounded EMA and live sky/held/water/albedo assets. Lighting signatures invalidate large changes; explicit `rt_accumulate freeze on|off` preserves opt-in frozen snapshots. Held native BlockItem lights are independent of raster local-light enablement and use level/15 × 20 scene-linear point intensity.

Equal-IOR dielectric transmission is a straight-through delta; grazing Fresnel and large-PDF MIS avoid 0/0 and overflow. Nonfinite path contributions flag invalid alpha; the HDR mean rejects bad samples per pixel and clears corrupt history. Filmic mapping uses stable large-HDR arithmetic; invalid display fallback no longer paints red.

Native emissive triangle metadata is cached per section and rebuilt in the TLAS ordering, with nearest-first 8192 table admission, global triangle/instance IDs, world transforms and area×emission CDF. GPU Material 3 emission and cutout are evaluated at the sampled point, visibility handles transmissive interfaces, and emitter hit PDFs provide complementary surface MIS. Table uploads follow scene generation; no full geometry CPU duplicate or image readback is added. LabPBR-only emitters without native emission levels, dynamic geometry, full medium free-flight and AOV reconstruction remain pending. RTX visual acceptance is still required.

# 0.39.0-alpha.13 — Vulkan-only tracing and stationary accumulation

User authorized deletion of old tracing after alpha.12 acceptance. The current runtime and kit contain no OptiX/CUDA tracer, JNI tracing bridge or runtime PTX compiler. Historical legacy-removal gates below are superseded by this authorization. Canonical BSDF/material/environment mathematics remain parity fixtures; unchanged water math is extracted into `native/rt/water_surface.h`. Independent `native/denoiser/temporal_aov.h` preserves temporal diffuse/reflection/refraction AOV invocation (SDK syntax-checked), but no denoiser is connected to Vulkan output yet.

Stationary linear HDR averaging defaults to 64 spp, configurable 4–4096 with `rt_accumulate spp`; `on/off/reset` control history. Moving/rotating, projection/size changes, scene generation/block invalidations and resource/world/backend resets reject history. Increasing target retains samples. Two RGBA32F ping-pong attachments keep history entirely on GPU; sample zero never reads stale history. Sky/light/animated albedo/water assets freeze for each snapshot. Scene updates/warmup continue after convergence, and accepted changes restart tracing. Normal view bypasses accumulation; no motion reprojection/denoising is claimed. Old preferences migrate, UI progress and kit instructions reflect the new path.

Check stationary convergence, raising target, movement, edits, F3+T, resize, disable/re-enable on RTX. Remaining emitter NEE, dynamic geometry, reconstruction and performance gates still apply.

# VoxelLight current state

## 0.39.0-alpha.12 — shared environment and direct-light transport

[Download alpha.12 client kit](https://temp.sh/porVb/voxellight-client-kit-26.2-0.39.0-alpha.12.zip) (temporary link; select `rt_backend vulkan_pt` on Vulkan).

Alpha.11 terrain admission passed user acceptance, including previously missing coverage. Alpha.12 advances the explicit `rt_backend vulkan_pt` experiment; production RTX Quality still uses the accepted legacy path.

- Shared 256×128 RGBA32F HDR sky: the existing `rt_environment` shader and `LightingEnvironment.polished` palette supply world time, sky/horizon/sunset, weather, stars and bounded cloud radiance. The fixed test sky/sun is removed from the material path. Geometry-only `vulkan_transport_test` retains its intentional test environment.
- GPU luminance × exact lat-long solid-angle CDFs, with conditional row/column binary sampling and uniform-cos(theta) cell sampling. Black maps fall back to uniform sphere. All map/CDF generation and transfer remain on GPU; no new frame-image readback or OptiX/CUDA call. Two bounded fragment reduction passes rebuild the distribution each frame; profile `vulkan_rt_environment_map` and `vulkan_rt_environment_distribution` separately.
- One power-weighted direct-light choice per nonsingular surface: current sun/moon finite cone (6.793e-5 sr), importance-sampled HDR environment, or enabled held virtual point light with inverse-square intensity. RGB visibility retains cutout/transmissive interfaces and now stops at finite point-light distance. Power-heuristic MIS pairs environment/sun NEE with BSDF misses; delta events and discrete points retain unit weights. The terminal sixth vertex uses unit NEE weight because there is no competing BSDF continuation. Emissive terrain is still BSDF-hit-only and is not counted by this light distribution.
- Camera-underwater transport initializes the same quantized water absorption/scattering/IOR/phase coefficients and section-independent identity as water surfaces. Finite-segment first-order scattering samples the same sun/environment/held distribution; it uses no phase MIS because this estimator has no competing phase continuation. An unbounded medium miss is limited to the existing reference policy of 128 blocks. Eight-medium stack and six-bounce/1 spp/recursion-1 contracts remain.

The CPU asset header grows from 96 to 192 bytes; camera and continuation ABIs stay 96/368 bytes. Environment map/cell/row data occupy 1,050,624 bytes in the existing descriptor-6 storage buffer, plus 1,050,624 bytes of GPU textures and a 64-byte palette uniform. No new RT descriptor binding. The overall packed asset cap remains 256 MiB/device storage-buffer range. Scene budgeting/admission is unchanged from accepted alpha.11.

Build/clientKit passed with 267 tests and no failures. Numerical validation runs the actual Slang CPU target against canonical native `environment.h`: 300,000 samples (max normalized native/Slang error 2.31713e-06) cover PDF normalization/poles, conditional histograms, black fallback, finite sun cone, held-light discrete PDF/inverse-square falloff, complementary miss MIS, paired white furnace, terminal-depth furnace and camera-water initialization. The existing Material 3/terrain parity suites remain required. Shipped GLSL environment/CDF pipelines are compiled and linked against Minecraft's actual bind-group contract; twelve SPIR-V stages and descriptor/continuation reflection are validated. RTX visual and performance acceptance for this version is pending.

Test `/voxellight rt_backend vulkan_pt` and `/voxellight stats`: day/night/rain transitions, indoor environment shadowing, metal/glass sky reflections, held torch moving near surfaces, entering/exiting water, terrain edits, F3+T and window resize. Reconstruction remains NONE and 1 spp is noisy. Remaining: emissive-triangle NEE/MIS, exact local cloud shadow transmittance, radiance/caustic caches, dynamic entities, temporal AOV/reconstruction/OptiX-denoiser/DLSS RR, full reference and RTX 4060 timing gates. Shared sky is the existing approximate model, not full atmospheric multiple scattering. No default switch or legacy tracing removal is authorized by this milestone.


## 0.39.0-alpha.11 — camera-prioritized terrain admission

Build/clientKit validation passed: 267 tests, zero failures. [Download alpha.11 client kit](https://temp.sh/UyKdk/voxellight-client-kit-26.2-0.39.0-alpha.11.zip). User RTX coverage acceptance passed (alpha.11).

Alpha.10 user GPU logs confirm material transport produces finite radiance (cold pipeline startup 4,377 ms), but resident input geometry reaches 67,108,080 bytes and some areas never appear. New sections previously could not evict residents at the 64 MiB limit; warmup retries therefore remained rejected. Alpha.11 sorts edits first and new arrivals by camera distance, and admits nearer sections by evicting strictly farther unprotected residents. Eviction is planned atomically; infeasible admission preserves the existing scene. Existing edited BLAS remains until its replacement is built. Camera movement changes admission priority; the existing two-second warmup retry discovers nonresident loaded sections again.

The 64 MiB / 512-section experiment still has finite coverage: this fixes first-arrival starvation, not unlimited world residency. Full scene paging, production environment/light sampling, reconstruction and performance gates remain pending. Validate missing nearby areas, walking/flying into new terrain, placement/destruction, and resource reload with `rt_backend vulkan_pt`.



## 0.39.0-alpha.10 — native terrain Material 3 binding

Alpha.9 primary/indirect geometry transport passed user GPU testing. Alpha.10 adds `/voxellight rt_backend vulkan_pt`, an explicit material transport experiment. `vulkan_poc` (accepted normals) and `vulkan_transport_test` (accepted grey geometry) remain available; production RTX Quality stays legacy OptiX.

- Reuses the 40-byte compiled terrain vertex snapshot: positions, barycentric UV, interpolated normal, tint and flags. Instance custom indices and packed shader geometry share the exact resident-section order. A separate device-local shader-geometry buffer is concatenated with GPU copies on scene changes; static sections still do no AS builds. Edit replacement admission remains the alpha.8 policy.
- GPU albedo, existing PbrAtlas IDs/normals and the exact five-plane Material 3 palette feed the canonical Slang decoder. IDs/palette/normals copy once per resource generation; native albedo animations are copied each frame entirely on GPU. No image is downloaded or mapped. The 96-byte atlas/weather header is CPU control data. Both resource reload and world change release old owners through MC submission retirement.
- Independent nonrecursive closest-hit and cutout any-hit. Closest-hit only reports distance, barycentrics, triangle and instance identity. Any-hit matches native cutout/tint-alpha thresholds and preserves glass/water transmission alpha. GGX/conductor/coating/dielectric/thin-sheet/diffuse-transmission sampling, tangent-space normals, linear albedo/emission, wet coating and original animated water normals run in raygen transport.
- Eight nested media persist across primary/indirect dispatches. Continuous Minecraft water uses a section-independent medium ID so crossing a BLAS boundary does not invalidate an exit. RGB extinction, eta-aware entry/exit, TIR, Russian roulette eta scaling, finite-segment first-order HG sun scattering and bounded 24-interface shadow transmission are active. Environment and emissive surfaces remain BSDF-sampled only, so they are not double counted. Fixed test sky/sun are still explicit; their production distributions/NEE/MIS are the next stage.
- Linear HDR radiance uses the existing reference filmic/sRGB curve at fixed 0.75 EV. One path per pixel/frame, six-bounce limit, at most 640×360; no temporal accumulation or reconstruction. Noise at 1 spp is expected. This is experimental transport, not completed PT parity.

Budgets: shader geometry adds at most 64 MiB to the existing 64 MiB AS inputs; geometry is copied across resident sections on an edit, not rebuilt as extra BLAS. The 368-byte continuation costs at most 80.86 MiB; radiance buffer/texture add 7.04 MiB. Packed atlas limit is 256 MiB and also checked against the device's maxStorageBufferRange, with a native-resolution RGBA8 albedo-copy texture. Profile `vulkan_rt_material_assets`, primary and indirect separately; existing scene/BLAS/TLAS timings remain. These are acceptance-stage budgets; memory/bandwidth optimization and RTX 4060 p50/p95 gates are pending.

Host validation executes the actual Slang CPU target against canonical native Material 3 formulas: existing 57,600 BSDF/medium cases and 32 new raw terrain/atlas cases (1,408 components), including high palette IDs, tint, normal mapping, cutout/transmission exemptions, emission, conductor data and original native water-wave functions. Shader build validates twelve SPIR-V stages, descriptor bindings, the 96-byte camera and both 64/368-byte continuation layouts. GLSL tests link the actual albedo-copy and material-display pipelines. Gradle build/clientKit passed; 265 Java/native contract tests passed. Terrain/water binding comparison max normalized error is 2.98023e-08; BSDF/medium max remains 0.000381917. Actual Vulkan material rendering requires user RTX validation.

[Download alpha.10 kit](https://temp.sh/vKVnP/voxellight-client-kit-26.2-0.39.0-alpha.10.zip). Test after installing on Vulkan:

```text
/voxellight rt_backend vulkan_pt
/voxellight stats
```

Check textures/tints, leaf holes, glass blocks, metal profiles, water reflections/transmission and emissive blocks; place/break terrain and try F3+T/resource reload and window resize. Return to `vulkan_poc` for geometry comparison or `rt_backend raster` for raster. Remaining migration: actual sky/sun/cloud environment distributions, emissive/point/held-light NEE/MIS/RIS, cross-section volume identity validation, radiance/caustic cache, entities, motion/AOV guides, denoising/RR, full reference, optional acceleration features and performance gates. Default switch and legacy trace removal remain gated by parity and RTX 4060 performance acceptance.


## 0.39.0-alpha.9 — geometry transport dispatch acceptance

The alpha.8 edit fix passed user RTX 4060 validation for placement and destruction. Alpha.9 adds `/voxellight rt_backend vulkan_transport_test` without changing that scene update policy or production RTX Quality. Separate primary and indirect RT pipelines advance one bounce per dispatch through a 64-byte, GPU-only continuation record. One jittered path per pixel per frame, six-bounce limit, grey diffuse BSDF, sun visibility rays and Russian roulette exercise the runtime transport/synchronization path. Recursion remains 1; closest-hit/miss never trace rays. Normal view remains available through `vulkan_poc`.

This is explicitly a geometry acceptance test, not Material 3 PT parity. It uses a fixed test sky/sun and grey material; transparent/cutout terrain is forced opaque. No reconstruction, temporal history, material/environment parity, RR/OptiX denoiser or performance gate has passed. At most 640×360 pixels: continuation costs 14.06 MiB, plus existing output buffer/texture; retired on resize/close through MC submission ownership. Profiles separate `vulkan_rt_primary` and aggregate `vulkan_rt_indirect` (five dispatches); do not add those to aggregate scene timings. Runtime performs no CPU image transfer except the existing one-time 32-byte diagnostic.

Validation: Gradle build/clientKit passed, 264 tests passed, seven packaged SPIR-V execution models and reflection ABIs validated. Canonical Slang/native transport parity still passes 57,600 cases / 3,225,600 scalar components (max normalized error 0.000381917). [Alpha.9 test kit](https://temp.sh/MrRxn/voxellight-client-kit-26.2-0.39.0-alpha.9.zip).

Next: bind native vertex UV/tint/flags and GPU albedo/Material 3 palette/normal/environment assets; add cutout any-hit and correct media/transmission, emission NEE/MIS, guides and independent reconstruction. This host has no RTX device; the new transport view needs GPU validation. Default switch and legacy tracing removal remain gated by visual parity and the requested RTX 4060 p50/p95 budgets.


## Unreleased — portable Material 3 transport

The Slang library now ports native BSDF, GGX energy compensation, all eight authored conductor eta/k presets, Material 3 five-plane decoding, dielectric/thin-sheet/water semantics, HG and finite-segment medium sampling, medium identity stacks, cutout thresholds and representable ray-origin offsets. `check` executes the actual Slang CPU target against canonical native code for 57,600 cases / 3,225,600 scalar components; max normalized error is 0.000381917 (gate 0.003). SPIR-V validation passes for the exercised transport functions. Provenance hashes reject stale ports after native semantics change. This is numeric kernel parity, not GPU/image parity: runtime Vulkan still displays terrain normals, primary/indirect integration and reconstruction remain unfinished. This evidence does not satisfy the default backend switch or OptiX tracing deletion gates.

## 0.39.0-alpha.8 — retain edited sections under the scene budget

User confirmed alpha.7 terrain normals and center hit alpha=1. The scene had reached 67,108,800 bytes of its 64 MiB vertex budget. Previously replacing an edited section removed its old BLAS before rejecting a slightly larger replacement, leaving a permanent miss hole. Alpha.8 prioritizes existing-section changes, accounts their net size, evicts distant unaffected resident sections when necessary to fit an edit, and replaces/releases the old BLAS only after building its successor. Oversized/unadmitted updates retain the prior geometry. New admissions do not consume edited-section reservation; unchanged versions still skip builds. The budget counts vertex storage, not total AS/scratch allocations. Tests cover full byte/section limits and replacement net-size behavior; User confirmed alpha.8 placement and destruction remain visible without section-wide misses. Static zero-rebuild and performance gates still need separate acceptance.


## 0.39.0-alpha.7 — nonempty AS geometry builds

Alpha.6 GPU telemetry verified the raygen marker (1, .2, .8, 2), finite direction colors, and center alpha 0; the user saw only miss background. BLAS/TLAS build geometryCount was zero because LWJGL pGeometries sets only its pointer (it shares the count with alternative ppGeometries). Alpha.7 explicitly sets the geometry count before both size queries and build recording. A regression invokes the production build-info builder for triangle BLAS and instance TLAS and verifies count, type, geometry pointer/type and BUILD mode. The GPU diagnostics remain until actual terrain hits and static/edit behavior are accepted.


## 0.39.0-alpha.6 — diagnose black Vulkan RT output

Alpha.5 still showed black despite status reporting 183 section BLAS builds and a completed debug display path. The cause remains unconfirmed. This diagnostic candidate makes ray misses a direction gradient, adds a known magenta raygen output marker and an independent cyan fullscreen border, and logs a one-time asynchronous readback of only two RGBA32F pixels (32 bytes per context, no wait). The marker verifies dispatch/output copying; center alpha distinguishes hit (1) from miss (0); red denotes nonfinite sampled output. This is POC-only diagnostic telemetry, not a reconstruction readback or performance acceptance. No denoising path is changed. Request screenshot plus `Vulkan RT POC GPU diagnostic` log before further conclusions.


## 0.39.0-alpha.5 — RT buffer descriptor writes

Alpha.4 created the Vulkan pipeline and ran the display path without the previous exception, but the user reported a black screen. The RT output SSBO, normals SSBO and camera UBO writes had descriptorCount zero: LWJGL pBufferInfo only sets the pointer, not the count. Alpha.5 explicitly writes the supplied buffer count; a native-struct regression invokes the actual production descriptor builder and checks each of the three bindings, descriptor counts, buffer handles and ranges. GPU output still needs confirmation; pipeline creation alone does not validate tracing.


## 0.39.0-alpha.4 — Vulkan normal display render area

The alpha.3 RTX 4060 log confirms mandatory Vulkan RT extensions enabled and POC pipeline creation in 39 ms, then 6 ms on retry. Display failed with `RenderPassDescriptor.renderArea must be provided`, causing raster fallback. Alpha.4 supplies the full destination viewport to the debug composite descriptor. These pipeline timings are not full renderer startup or PT performance acceptance. Terrain normal output and static/edit BLAS behavior still require GPU confirmation.


## 0.39.0-alpha.3 — preserve terrain on backend switch

The alpha.2 user log confirms Minecraft reset the graphics API after the previous crash and selected OpenGL; Vulkan RT never initialized. The Vulkan POC command now rejects OpenGL with an explicit switch-API-and-restart message before changing mode or geometry admission. Both RT backends stop calling LevelRenderer.invalidateCompiledGeometry (which releases native terrain buffers); independent RT warmup admits already loaded sections without discarding raster geometry. The diagnostic view runs once after world rendering and before camera projection reset, independently of foundation material/shadow readiness. Host regression/build checks cover call placement and absence of terrain invalidation; RTX rendering remains unverified on this host.


## 0.39.0-alpha.2 — native-stack startup allocation fix

Alpha.1 crashed at extension enumeration on the Windows RTX 4060 driver: the driver-sized VkExtensionProperties array overflowed LWJGL MemoryStack during device creation. Alpha.2 uses explicitly freed native heap allocations for extension arrays and up to 512 TLAS instances, and resets temporary BLAS geometry stack allocation per section. A regression allocates both 512-entry arrays with only 1 KiB of thread stack left and verifies that neither consumes stack space. Windows startup/RT execution remain GPU acceptance items.

## 0.39.0-alpha.1 — Vulkan RT migration stage 1

Legacy OptiX remains production. An explicit Vulkan terrain normal POC borrows MC device/queue, enables RT features, builds section BLAS/world TLAS, consumes independent build-time Slang/SPIR-V stages, caches pipelines and records delayed GPU timings. Static section versions skip rebuild; viewport resize preserves pipeline/scene. Build/layout/stage/lifecycle contracts are host-verified; this host has no RTX GPU. Phase A GPU acceptance is pending; full PT, Material 3 parity, compaction/entity refit, RIS, reconstruction, DLSS RR, OMM/SER and Vulkan reference are not complete. Do not resume OptiX compiler tuning as the migration objective. [Authoritative migration ledger and checks](VULKAN-RT-MIGRATION.md).

## 0.38.0-alpha.8 — resize callback correction (GPU candidate)

Alpha.7 user correctly enabled reference, but 214×120 → 640×338 still destroyed the native context and recompiled. The common GameRenderer resize/reset hook bypassed alpha.7 viewport reuse. Alpha.8 splits resize from world/data/shutdown reset and adds a bytecode lifecycle regression test. Alpha.7 initialization took ~13 s; cache remained zero/missing. Visual correctness, reference convergence and warm cache remain unaccepted. [Evidence and checks](REFERENCE-0.38-ALPHA8.md).

## 0.38.0-alpha.7 — convergence / viewport follow-up (GPU candidate)

Alpha.6 user GPU cold startup completed in ~16 s, but resizing recompiled and identical keys missed a zero-sized disk cache. Alpha.7 retains compiled pipelines/world geometry on viewport resize, preserves reference accumulation when changing positive target SPP, explains reference versus realtime SPP in command feedback/status, exposes reset count and raises the adaptive reference ceiling to 8192 pixels. Native/Java host verification does not establish visual correctness. SDK disk-cache persistence, warm startup and the reported noisy/incorrect image remain GPU acceptance items. P0 stays open. [Changes and GPU checks](REFERENCE-0.38-ALPHA7.md).

## 0.38.0-alpha.6 — callable compiler boundaries (GPU acceptance candidate)

Alpha.5 failed: diffuse expanded to 62,994 driver instructions and canceled only after 267.3 s. Alpha.6 moves iterative transport and visibility into separate continuation callable modules and BSDF evaluation/sampling into direct callable programs, with explicit SBT and stack ownership. Strict PTX/default remains the baseline; full-reference transport stays separately compiled and lazy. Successful driver graph/cache-hit feedback is logged. Timeout now reports fallback and pending safe cleanup; cooperative driver cancellation is still not a proven hard deadline. Windows/Linux build and host tests pass; cold/warm startup, callable GPU correctness/performance and reference isolation still require the user GPU gate. P0 is open; P1–P7 remain deferred. [Evidence, architecture and acceptance](OPTIX-COMPILATION-0.38-ALPHA6.md).

## 0.38.0-alpha.5 — failed GPU compilation gate

**Alpha.5 also failed:** hit compiled in 1.143 s; diffuse task 3 stayed active beyond 240 s despite successful cancellation at 120 s. Cooperative cancellation does not enforce a hard deadline. Later modules/link/runtime were not reached. **Alpha.4 failed user GPU acceptance:** 591.74 realtime task 2 did not finish within 120 seconds; the process exited immediately after the cancellation request. Exact native fault remains unconfirmed. Alpha.5 splits realtime signal/probe raygens into separate constant-signal modules, adds non-inline glossy BSDF boundaries, retains pending compiler ownership across render resets, skips abandoned queued work, makes full reference session-only, bridges native diagnostics to latest.log plus a flushed dedicated file, and binds CUDA on the watchdog before targeting a published module. Windows/Linux and host regression verification do not establish crash recovery or startup targets. See [GPU evidence and follow-up](OPTIX-COMPILATION-0.38-ALPHA5.md). P0 remains open; P1–P7 remain deferred.

## 0.38.0-alpha.4 — OptiX Compilation Architecture (GPU acceptance candidate)

P0 compiler ownership split: hit/realtime/full-reference/caustic modules, independent caustic pipeline, non-tracing CUDA utilities, lazy isolated full-reference creation retaining realtime during compilation, task-level telemetry and per-module/group/link times, stable SDK disk cache with status readback, and cooperative OptiX 9.1 creation cancellation watchdog. Strict/fast PTX/IR artifacts support four-way A/B; strict PTX remains the baseline until numerical/visual acceptance. **Startup improvement, cache cold/warm behavior and cancellation are not yet validated on RTX 4060 Laptop.** The alpha.3 591.74 baseline remains 1185+ seconds at tasks=2/3. Synchronous link cancellation remains a stated limitation. P1–P7 are deferred. See [architecture and GPU acceptance](OPTIX-COMPILATION-0.38.md).

## 0.38.0-alpha.3 — OptiX startup compatibility

Alpha 2 failed OptiX-IR compilation on the user's NVIDIA 591.74 driver (error 7251), retaining raster fallback. Strict-math PTX is now the default; IR remains opt-in with a logged single PTX retry on compilation error 7251. Device errors do not retry. Native compiler callbacks retain errors/warnings, and compiler buffers grow from 8 KiB to heap-backed 1 MiB; module creation/program-group/link errors include diagnostics. Debug level is explicitly NONE for release modules. Default optimization remains optimized, not O0. GPU recovery is pending user verification.

## 0.38.0-alpha.2 — environment and asynchronous full reference

Shared GPU HDR sky/cloud environment and exact solid-angle importance CDF; OptiX primary-camera progressive reference; nonblocking reference completion/display ownership; stale-camera rejection; coordinate-aware ray origins and shading hemisphere checks; matched GGX reflection multiple-scattering sampling; water free-flight and full-reference medium events; build-time OptiX-IR/PTX and optimization A/B. See [0.38 ledger](RTX-QUALITY-0.38.md), [environment](RT-ENVIRONMENT.md), [reference](FULL-REFERENCE.md). This is still a checkpoint: adaptive full-resolution RTX rays, canonical calibration, RT texture mips/animation, primary diffuse ownership, hierarchical lights, layered reference, caustic clipmaps and signal-guide improvements remain unfinished. NVIDIA acceptance has not been run on this host.

## 0.38.0-alpha.1 — first integration increment

Primary opaque glossy NEE/MIS, RT coated-substrate semantics and UV tangent frames implemented. User confirmed 0.37.6 responsiveness; night iron/indirect quality remains unaccepted. The full 0.38 review is **not complete**. Implementation/acceptance ledger: [RTX Quality 0.38](RTX-QUALITY-0.38.md). No NVIDIA baseline available on this host.


Previous stable release: **0.37.6 — Reference progressive frame budget**. The 0.36 raster-primary/OptiX GAS/IAS/zero-copy/separate-AOV/world-cache architecture is retained; the user confirmed those paths run in game. This release changes material scattering and transport rather than rebuilding RTX architecture.

User measured 0.37.5 compilation/linking at ~134s, then reported unusable reference frame rate. 0.37.6 replaces full-image per-frame reference work with adaptive 8–1024-pixel windows (initially 64), complete-sweep spp accounting and persistent guide snapshots. Untouched surfaces retain raster. Normal RTX Quality scheduling is unchanged. See [reference frame scheduling](REFERENCE-0.37.6.md). NVIDIA FPS/convergence acceptance is pending.

0.37.4 still compiled for at least 3m51s in the supplied log. 0.37.5 uses task-based compilation on up to four workers, optimization level 0 and 15-second task/elapsed heartbeat logs. GPU execution may be slower; NVIDIA startup and runtime measurements are pending. See [compiler follow-up](REFERENCE-0.37.5.md).

User confirmed 0.37.3 stays responsive, but compilation took ~37m28s and the first output transfer failed with invalid mip. 0.37.4 corrects every RT buffer/texture copy and limits heavy CUDA inlining/compiler optimization. See [follow-up](REFERENCE-0.37.4.md). New packaged transport PTX is 1168476 bytes (previous 4,077,162); driver-time improvement remains unmeasured.

0.37.3 addresses the supplied initialization freeze boundary: native context/module/pipeline startup runs on a daemon worker while raster continues, with live phase/elapsed status and safe late-result disposal. See [startup diagnostics](REFERENCE-0.37.3.md). In-game NVIDIA validation is pending.

0.37.2 fixes rectangular depth-pyramid zero-height mip views, removes CUDA collection from GUI telemetry and tiles one-spp reference launches. The supplied crash log has no fatal tail, so the original termination cause remains unconfirmed. See [reference crash hardening](REFERENCE-0.37.2.md).

0.37.1 fixes per-frame reference resets, suppresses reference projection jitter/adaptive budget changes and freezes RT dynamic animation while converging. A searchable vanilla settings screen in Pause/Options and `/voxellight settings` shares validated command actions and atomically persists normal preferences. See [Settings](SETTINGS.md). In-game UI/reference acceptance remains pending.

Implemented: Material 3 class/LUT and 1,269 Vanilla block texture presets, LabPBR 1.3 channel priority, exact RGB conductor Fresnel, matched GGX VNDF/eval/PDF, coating/wetness and thin foliage, full textured secondary BSDFs, power-CDF emissive/environment/sun/held-light NEE with MIS, rough/thin dielectric identity-aware media, shared participating-water single scattering, refracted-sun photon caustic cache, signal-specific edge rejection and conservative raster fallback, 256-spp configurable reference accumulation, expanded debug and operation telemetry. See [Material 3](MATERIAL-3.md) and [RT light transport](RT-LIGHT-TRANSPORT.md).

**0.37 visual acceptance is pending.** This Linux host has no NVIDIA GPU/display. **246 tests pass**, including production CPU BSDF Monte Carlo/regressions and actual GLSL/SPIR-V/pipeline bindings; Windows/Linux native builds pass. These are verification; they do not establish edge quality, transport convergence or FPS. Remaining model/coverage approximations and all acceptance scenes are listed in the transport document. The earlier 0.36 user acceptance does not imply 0.37 acceptance.

Minecraft26.2 / Java25 / Fabric Loader0.19.5 / Fabric API0.160.0+26.2. Native Vulkan only, client only; effects off on a fresh installation, saved settings restored on world join. Build with `./gradlew build clientKit`; install the resulting mod from the kit and run `/voxellight mode foundation`. Detailed commands and checks: [INSTALL.md](INSTALL.md).

| Stage | State |
| --- | --- |
| B1 material diagnostics; B2 separated terrain HDR lighting | User confirmed corrected inputs and the foliage fix. |
| B3a block-entity shadows; B3b light-aware volume; B3c opaque model material lighting | User confirmed0.13/0.14/0.15; bounded supported streams, unsupported/blended/custom models remain native. |
| D1 directional visibility temporal history | Implemented0.16; user cannot distinguish the improvement, so no claim of full temporal acceptance. Ghosting/cascade/resize checks remain pending. |
| 0.16.1 stability follow-up | Retain surface until verified replacement; LIGHT-only history resets removed; per-frame CPU transform inverses. User confirmed the0.16.1 stability release. |
| D2 basic terrain AO | Implemented0.17: half-resolution horizon AO, spatial bilateral filter/upsample, ambient-only composition. User confirmed it works, with modest visual benefit; no AO history. |
| 0.18 lighting/color polish | Implemented: filmic/manual exposure, hemisphere sky palette, emissive terrain bloom, bounded material-distance blend. User confirmed working. |
| 0.19 local-light polish | Implemented: exact-ID resource-pack colors and one player held-emissive-block source within16 combined lights. User confirmed working. |
| 0.20 atmosphere foundation | Implemented analytic height/distance aerial perspective and directional glow; local supported receivers only; user confirmed working and prefers density0.002.0.20.1 adopts that default. |
| 0.21 Water Foundation | VisualComposite ownership split; native-stream HDR water with Fresnel/absorption/sky reflection/refraction. Screenshot showed blue water grid/dashes;0.21.1 fixes derivative evaluation order and UV rounding. User confirmed seam fix. |
| 0.22 native material coverage | Implemented native-compile inline attributes and a borrowed visible-terrain material pass. Default foundation/material diagnostics no longer use the local mesh store or 24–32-block fade. 0.22.0 user log exposed Fabric Indigo bypass of the vanilla output callback;0.22.1 captures pre-lighting Indigo quads and transfers metadata at actual buffer emission. User confirmed0.22.1 working. |
| 0.23 extended directional shadows | 128-block receiver default; unchanged2048 near map, coarser middle/far maps, borrowed compiled terrain outside the near bridge. No duplicate distant geometry or new image memory. User confirmed0.23 working; detailed offscreen/low-sun/performance checks remain pending. |
| 0.24 shadowed volumetric lighting | Quarter-resolution16-step current-frame sun/moon scattering through terrain/dynamic shadow maps, HDR extinction/composition and depth-guided upsampling. Preferred density0.001 retained; user confirmed0.24 working. Detailed visual/performance checks remain pending. |
| 0.25 phase1 — water reflections | Bounded HDR screen-space trace over existing pre-tone scene/depth; binary refinement and edge/distance/validity fade to sky. No new reflection image. Pending combined in-game test. |
| 0.25 phase2 — volumetric filtering | Two depth-aware quarter-resolution5-tap filter passes; no volume history or RGB ghosting. Pending combined in-game test. |
| 0.25 phase3 — animated water / quality controls | Three moving normal-wave harmonics, bounded phase and modulo64 continuity; waves/reflections/filter toggles and fixed fast/balanced/high sampling budgets. Pending combined in-game test. |
| 0.25.1 review follow-up | Direct36-byte writer; cached caster admission outside allocation lock; smaller filtered water ripples; optional delayed per-pass profiler. 178 tests passed; actual native shader compilation and direct vanilla/Indigo startup writer checks passed (graphics startup stops at missing DISPLAY); in-game motion and measured performance pending. |
| 0.26 celestial cache | Two fixed-angle terrain epochs with visibility interpolation,8-page future construction budget and current dynamic shadow reprojection. Static cutout meshes now cache; animated/unknown emitters remain conservative.189 tests pass; native shader contracts and actual startup classification/writer checks pass (graphics stops at missing DISPLAY). In-game quality/performance pending. |
| 0.26.1 epoch default | User reports higher FPS with epochs disabled. Epoch blending is now opt-in; static cutout caching remains enabled independently. No measured pass-level diagnosis yet. |
| 0.27 performance foundation | Three RGBA8 material targets + D32 (16 bytes/pixel). Explicit signed-normal and validity/native-fade encoding across terrain/entity capture, lighting, AO, bloom, temporal and diagnostics. Temporal shadow defaults off; opt-in history remains available.191 tests pass; GPU acceptance and timings pending. |
| 0.27 acceptance | User reports temporal off is15 FPS faster; keep it off. Packed-material precision/distant-fade checks are not separately confirmed. |
| 0.28 surface shadow sampling | Independent shadow_filter fast/balanced/high budgets4/16/36 taps per cascade; default balanced. Continuous texel-phase weighting, existing world bias, cascades and dynamic blocker union retained.194 tests pass; visual/FPS acceptance pending. |
| 0.29 HZB water tracing | Half-res R32 max-depth pyramid with conservative odd-tail reductions and hierarchical screen-space reflection traversal. Opt-in water_hzb,16 MiB cap, separate profiler stage;199 tests pass. GPU quality/performance acceptance pending. |
| 0.30 experimental hybrid PT | Runnable CUDA voxel diffuse secondary paths + real OptiX HDR denoiser, optional stationary-camera preview. Native Windows/Linux binaries bundled; 205 Java tests and native CPU traversal/sampling checks pass. GPU acceptance pending. [Scope/test instructions](PATH-TRACING.md). |
| 0.30.3 hybrid GI stability | Persistent per-pixel radiance/confidence/moments with reprojection/clamping before OptiX HDR; eight fresh samples per batch, view-depth validation, linear albedo, emissive ownership, proxy hysteresis and freeze/rejection/upload diagnostics. 209 Java tests and native CPU checks pass; GPU motion/ghosting acceptance pending. |
| 0.31 Material 2.0 | Packed ID/LUT, GGX direct/specular sky, static LabPBR normal/spec maps and rain wetness. 216 tests pass, including actual shader/binding compilation; GPU appearance/performance acceptance pending. [Contract](MATERIAL-2.md). |
| 0.31.0 acceptance | User confirmed Material 2.0 working; detailed map/wetness/FPS comparisons remain unmeasured. |
| 0.31.1 single-raster MRT | Native color/depth + four material outputs in one terrain submission; shared filtering and copied depth, comparison/fallback retained. 219 tests pass; GPU acceptance pending. [Contract](SINGLE-RASTER.md). |
| 0.31.1 acceptance | User confirmed single-raster native MRT working; measured FPS remains pending. |
| 0.35 raster review bundle | Sky/cloud/weather, cloud shadows; shared compact static motion/guides, optional opaque HDR TAA; temporal4/6/8-step volume; underwater/caustics/rain/foam; half-res solid PBR HZB SSR; optional measured adaptive world-region budget. 221 tests pass; joint GPU acceptance pending. [Full scope, budgets, checks and later RTX track](REVIEW-COMPLETION.md). |
| Next acceptance | Test the raster bundle in sky/weather, reflective materials and water; capture fixed-quality A/B world/pass timings. Later RT-core reflections/interop/full PT stay planned, not implemented. |

New 0.35 allocations and per-effect fallbacks are authoritative in [REVIEW-COMPLETION](REVIEW-COMPLETION.md); the historical atmosphere/volume description below is superseded there. Actual GPU timings remain unmeasured on this host.

Current budgets: material targets use20 bytes/pixel (1440p70.31 MiB,4K158.20 MiB), including the new packed PBR attachment. PBR atlases/LUT add at most33.25 MiB; HDR lighting remains8 bytes/pixel. Temporal shadows and celestial epochs default off. Optional dual-angle terrain shadows add at most48 MiBD32 (102 MiB total shadow textures); shared resolve transforms1344 bytes. Native material mode borrows visible native terrain geometry, adding8 bytes/vertex (BLOCK stride28→36) and, by default in0.31.1, one native color/material MRT raster with a private depth copy; `single_raster off` restores the extra material raster. No duplicate material mesh store. `/voxellight native_material off` selects the old125-section/16 MiB/one-build-per-frame local reference. Native compiler light updates still rebuild native section buffers. Light-aware scene cap384 loaded sections; independent near shadow terrain32 MiB; distant shadows borrow native allocations under a bounded128-block receiver +96-block light extrusion, with pending native compilation reported; local-light reference16 combined sources (one slot reserved while a held source exists). When explicitly enabled, D1 adds32 bytes/pixel with128 MiB cap (1440p112.5 MiB;4K falls back to current shadows).

AO uses two half-resolutionRGBA16F targets (32 MiB cap,1440p14.1 MiB,4K31.6 MiB), plus a16-byte neutral texture and32-byte settings. It is terrain-only and bounded by existing material coverage.

Polish adds two quarter-resolutionRGBA16F bloom targets (16MiB cap,1440p3.52MiB,4K7.91MiB). `look reference` restores0.17 lighting/output; polished is the foundation default. Native material mode disables the global24–32-block fade; local reference coverage blending fades24–32 blocks within the guaranteed material window; `coverage_blend off` restores the sharp window for comparison. See[polish contract](POLISH.md).

Atmosphere defaults to density0.001.0.25 uses one quarter-resolutionRGBA16F scattering/transmittance target and an optional equal-size spatial-filter scratch (8MiB combined cap;filtered1440p3.52MiB,4K7.91MiB), one8-byte neutral texel,32-byte controls and16-byte filter settings. 0.35 opaque medium uses4/6/8 jittered steps with quarter-res shared-guide history (four quarter targets including filter/history,32MiB cap); temporal off restores8/16/32 current-frame steps; ambient scattering retains receiver-skylight approximation. Water retains analytic atmosphere. 0.35 adds underwater direct scattering and shared volume history; non-overworld/reference look retain the previous bypass. See[volumetric scope](VOLUMETRIC.md).

Opt-in HZB adds a half-resR32 mip chain (16 MiB cap,1440p4.69 MiB,4K10.55 MiB) and grows water controls to80 bytes. Water adds oneRGBA16F HDR background + oneD32 immutable depth image (96MiB cap;1440p42.19MiB,4K94.92MiB), no duplicate water meshes or reflection image. Screen reflections reuse these two resources with16/24/32 trace steps. Supported Fast/Fancy water follows valid native HDR background coverage; Fabulous/underwater/uncaptured backgrounds stay native. See[water contract](WATER.md).

0.35 supplies opt-in opaque HDR TAA and static camera motion guides; no dynamic model velocity, complete transparent HDR rendering or full-scene transparent volumetrics, production GPU voxel DB or full-scene GI yet. 0.30 adds bounded experimental hybrid diffuse GI; it is not full primary-ray PT. Performance results are unmeasured. Continuous world-sun invalidation remains in the default path; experimental dual-angle epochs reduce redraws but regress FPS in the user scene and are opt-in; static cutouts cache while animated/unknown cutouts refresh. Duplicate shadow meshes, DDA cost, and history compression remain future work; do not increase local-light count or extend temporal scope before evidence warrants it.

Review decision: preserve the accepted material/lighting architecture; material refresh is confirmed and basic AO is confirmed operational with modest benefit. Lighting/color polish is confirmed working; local-light materials and held source are confirmed working; analytic atmosphere is confirmed working, with preferred density0.001 adopted as the0.23 default. Water, native material coverage and0.23 extended shadows are confirmed;0.24 is user-confirmed working;0.25 was reported working with modest visual benefit; detailed combined acceptance remains pending.0.26 epoch cache/static cutout changes require the new A/B checks. See[local-light scope](LOCAL-LIGHTS.md). See[AO contract](AO.md). See [0.16 review decision](REVIEW-0.16.0.md) and [release history](CHANGELOG.md). This environment has no graphics device/display; automated native shader checks do not replace in-game acceptance.

0.30.2: local material content replaces global/LIGHT task-version admission. Tiny camera noise is tolerated; celestial reseeds retain surface-valid images during warm-up. 205 tests pass. User GPU log confirms native work executes; corrected in-game accumulation/flicker acceptance pending.

0.30.2 removes stationary-only rendering and the sample-count intensity ramp. Valid surface lighting is camera-reprojected while motion batches refresh it; resets trace eight samples before denoising. 206 tests pass; in-game motion/flicker and worker cost pending.

0.30.3 supersedes the 0.30.1/0.30.2 accumulation policy above. The display receives a persistent worker-filtered GI field, not independent reset batches. Native history adds six float4 images (~21.1 MiB maximum); sun/weather bins and proxy-origin shifts no longer invalidate unchanged world surfaces. Actual 26.2 upload staging copies were verified. See [stability contract and diagnostics](PT-STABILITY.md).

0.30.4: user reports reduced but remaining flicker in 0.30.3. Validated bilinear native history, removal of grazing radial rejection, sparse compatible-surface composite fallback and observation-aware clamping address remaining discontinuities. Same resource/sample budgets; 209 Java tests and expanded native CPU regression checks pass. GPU verification pending.

0.30.5: user reports remaining flicker and freeze resembling raster-only. Display lifetime is separated from accumulation/material revision; freeze waits for one valid observation. Explicit world/resource resets prevent cross-world display reuse. A post-OptiX guide-validated EMA stabilizes the actual denoised field (+two low-res float4 images, ~7.0 MiB maximum). 210 Java tests pass; GPU acceptance pending.

0.30.6: freeze user-confirmed working, continued updates still flicker. Two separately surface-validated observations interpolate in the Vulkan composite over 150 ms, replacing abrupt batch swaps. Freeze holds/resume continues the blend. +three low-res float4 images (10.55 MiB maximum), no new full-res target/rays. Admission waits for transition completion. 212 Java tests pass; GPU stability/performance pending.

0.30.7: user confirms 0.30.6 no flicker on Vulkan, but new-view buildup remains. Remove new-only zero-to-full fade; bootstrap missing-history surfaces with 32 samples, keep established surfaces at eight; overlap next-job compute with display transition while deferring slot replacement until safe. No new images. 212 Java tests and native bootstrap regressions pass; GPU latency/quality acceptance pending.

0.31.0: prioritize Material 2.0 per the latest review. Keep current diffuse GI behavior; the user still sees new-view refinement after 0.30.7. Implement static-map LabPBR/PBR/wetness with bounded lookup storage; future environment/temporal/RTX tracks remain planned. See [material contract](MATERIAL-2.md).

Alpha.13 validation: build/clientKit passed, 241 tests with zero failures; actual accumulation GLSL links against Minecraft bindings, packaged-artifact regression rejects legacy tracer/native compiler payloads. Material/terrain/environment Slang-native parity remains unchanged (57,600 cases / 32 cases / 300,000 samples). Independent temporal AOV header syntax-check passed against the installed OptiX/CUDA SDK. RTX visual acceptance remains pending.
