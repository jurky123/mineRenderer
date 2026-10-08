# alpha.48 — Reconstruction guides and transport controls

This revision implements the highest-priority reconstruction comparison and independent transport experiments from the VoxelLight/Caustica review. It is an implementation and numerical validation, not RTX quality/performance acceptance or a claim of parity with Caustica. Caustica reference inspected: `330acd2d743bb6b2e27b53a4adb4a3c852b9e141`; no Caustica code is copied.

## Reconstruction input contract

- Scene-linear sRGB/Rec.709 radiance and reflectance, HDR, preExposure=1. Tone mapping remains after RR. This explicitly fixes the working-space contract; it does not switch transport or its display transform to ACEScg.
- Packed normalized world-space shading normals and linear roughness `sqrt(microfacetAlpha)` by default. NVIDIA's [RR integration guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md), sections 4.1.4 and 4.2.1, calls this linear roughness and squares it before the GGX BRDF helper. Thus the review's alpha-vs-sqrt-alpha difference alone is not evidence of an input-contract violation. `rt_roughness alpha` is an explicit alternative for image A/B, not a default correction.
- Reverse-Z HW depth, previous-minus-current motion in **input pixels**, unjittered clip matrices, zero invalid/sky depth, and projection jitter opposite the actual ray-sample displacement. Actual dimensions continue to come from NGX, subject to reported storage limits.
- Specular albedo now uses a bounded split-sum directional-hemispherical GGX approximation instead of F0 alone. It is an approximation for Material 3's advanced lobes, not an exact integral of coatings, anisotropy and multiple scattering.
- The SDK field is `pInMotionVectorsReflections` (mapped to `GBuffer.SpecularMvec`), not `pInSpecularMotionVectors`. JNI now binds a separate RG32F image. Native argument lengths are checked.

`material_guides` is a dedicated deterministic raygen after radiance resolve. It traces one jitter-aligned camera sample, computes a smooth reflected endpoint, and mirrors current/previous endpoints through the reflector plane. This division-free construction stays finite at grazing angles and gives zero motion for an unchanged camera and scene. Dynamic endpoint translation/deformation uses the existing previous geometry bank. The reflector's previous normal is approximated with its current normal; rotating/deforming mirrors and animated water still require image validation.

For smooth dielectric interfaces (alpha <= .04), up to six deterministic Snell steps seek the visible opaque endpoint. Thin interfaces continue straight; TIR, sky, unresolved chains and rough glass retain the foreground tuple. Ordinary depth remains on the original camera ray at the accumulated virtual distance. Endpoint displacement transports ordinary motion. This is a local approximation through bent/multiple interfaces, not an exact refractive motion Jacobian. Foreground specular reflectance remains separately owned by the first interface. Simultaneous reflection/transmission cannot be represented as two independent full guide layers in the current RR input set.

The guide pass is used only for active realtime DLSS ray jitter, not Reference/OptiX fallback. `rt_guides first_hit` disables it; `rt_guides endpoint` enables it. A guide/roughness switch invalidates RR history. Both controls are included in benchmark configuration/restoration and actual-state checks. The additional pass is explicitly timed as `vulkan_rt_deterministic_guides`; it is additional GPU work, not assumed free.

## Image acceptance

Use a local world, the same resource pack, resolution, camera, weather, light sources and 1 spp. Disable FG and compare actual input dimensions in stats, not only the Performance label.

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight rt_transport full
/voxellight rt_guides first_hit
/voxellight rt_capture_guides
/voxellight rt_guides endpoint
/voxellight rt_capture_guides
```

Capture after history settles, repeat at the same stationary camera, then during the same controlled camera movement and moving-entity sequence. Run `rt_roughness linear` / `rt_roughness alpha` separately, keeping the guide mode fixed. Test oblique terrain edges, foliage, rough metal, a smooth reflector with moving entities, water, glass with moving objects behind it, block/light edits and dimension changes. Do not interpret one still image as dynamic-history acceptance.

An explicit capture asynchronously exports `benchmark-results/voxellight/rr-guides-*.zip`. It contains input HDR, depth, normal/roughness, diffuse/specular albedo, ordinary/specular motion, reflected endpoint/previous endpoint (w encodes validity), diffuse/reflection/transmission AOVs and RR output. Every `.f32` is little-endian float32; dimensions/channel counts and camera/jitter/history/input-contract metadata are in `capture.txt`. PNGs are diagnostic previews: signed vectors are mapped around .5, endpoints show validity, and HDR previews use a simple display transform. Raw HDR and AOVs, not those preview PNGs, are the numerical reference. Capture readbacks occur only on explicit requests and should not be requested while measuring performance. RR export requires an active successful DLSS frame.

## Independent transport experiments

```text
/voxellight rt_benchmark transport
```

16 blocks: two ABBA rounds for each comparison, same terrain/light controls, actual input size, 1 spp, six surface advances maximum, exact shadows, RIS, fixed queues and FULL policy. Experimental paths force TraceRay and disable OMM/SER; the paired baseline uses the same settings. No new cache strategy is enabled.

1. `FULL`: existing Material 3 Wavefront reference.
2. `SIMPLE`: ordinary nonemissive, uncoated ROUGH_DIFFUSE uses Fresnel + GGX specular and its Fresnel-weighted diffuse term, without Oren–Nayar or multiple-scattering compensation. Complex materials keep Material 3. Texture decoding and normal mapping remain identical. This is a **biased material simplification** to isolate BSDF work; neither identical quality nor a production replacement is claimed.
3. `TWO_PASS`: Visibility stores the first hit and a compact continuation; the active Primary Shade raygen consumes that hit and evaluates B0–B5 locally, without per-bounce dispatch. It is a new two-radiance-dispatch experiment, not the previous B1–B5-only Iterative switch. Guide resolve and final radiance/AOV resolve remain separate. Hot continuation is 48 bytes; eight-media cold storage and AOV accumulation remain intact. Direction uses two 16-bit octahedral coordinates, while PDF, throughput, etaScale, RNG and proposal identity retain their original precision. The local integrator still carries Material 3's full state; 48B storage does not prove lower register pressure.

Manual controls: `rt_transport full|simple|two_pass`. Defaults remain FULL Material 3 / Wavefront. Switching transport explicitly selects FULL policy; experiments are not compatible with CACHE/SPARSE. A SIMPLE + old Iterative combination is not an additional experiment.

Results report transport GPU times and raw per-frame CPU/GPU scopes, radiance/shadow/any-hit and RIS counters, alive paths and actual controls. For Wavefront inspect B1/B2 separately; TWO_PASS cannot expose individual dispatch timings for those bounces. To attribute register spills, ALU and texture throughput, capture the named shaders in Nsight on RTX; these hardware counters cannot be inferred from ray counts or the Linux build. Compare image/AOV captures as well as CPU/GPU P50/P95 before promoting any variant.

## Separate scene CPU experiment

```text
/voxellight rt_benchmark scene
```

Eight ABBA blocks compare OPTIMIZED with ASYNC_PREP using **CPU Scene commit P95**. The latter runs immutable range classification/reordering on one bounded daemon worker (32 tickets, 8 MiB pending inputs). Publication checks source identity, section version and atlas epoch; stale, oversized or rejected work uses synchronous preparation. Upload, BLAS/TLAS construction, barriers and GPU resource publication remain ordered on the render thread. Existing optimized BLAS refit behavior is retained. The render thread may wait for an accepted preparation ticket; submitted/published/stale/wait/bytes are reported, so this can be slower and is not enabled by default.

This is CPU build/publish preparation, **not** Caustica's independent GPU queue/timeline executor. Complete background GPU AS publication, GPU texture/ray-cone mip migration, ACEScg transport and exact multiple-interface/deforming-reflector guides are still separate work. The current in-place geometry/AS/scratch lifecycle cannot safely be moved onto another queue by changing only the submit thread. No shader/CPU experiment here is presented as implementing that change.

## Numerical/build gates

Production SPIR-V descriptor/camera/storage ABIs are checked, including the 48B packed layout. Native tests execute production encode/load/store with second sample bank, all eight nested media, RNG, previous PDF, depth, validity, AOV and proposal identity. 1024 direction samples must have vector error < 1e-4. Reflection/normal-incidence refraction/TIR/stationary tests and 256 bounded GGX guide-albedo cases run through Slang's actual CPU target. GLSL motion/depth/roughness helpers are also executed natively. These tests are not an image-equivalence or GPU speed certificate.
