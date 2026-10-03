# Visual review implementation and acceptance — 0.35.0

The 0.30.7 review prioritizes Material 2.0 → sky/cloud/weather → shared temporal → underwater → reflections, interleaving measured performance work. 0.31.0 PBR/LabPBR/wetness and 0.31.1 single-raster native MRT are user-confirmed working. This release bundles the remaining **raster visual milestones**. NVIDIA traversal/interop and full PT are the later RTX track, not claimed by this release.

| Review item | Implementation | Remaining boundary |
| --- | --- | --- |
| Material ID/LUT, GGX, LabPBR, rain wetness | 0.31.0; native single-raster MRT 0.31.1 | Static maps only; POM/SSS/animated maps deferred as reviewed. |
| Sky, sun/moon, weather | Procedural HDR sky, sunrise/horizon tint, sun/moon disks, phase brightness, sparse stars, rain palette | Overworld only; artistic model, not a spectral atmosphere solver. |
| Cheap clouds and cloud shadows | World-anchored analytic cloud plane, four-octave density, sun shading and projected direct-light visibility | Honors native clouds-off; replaces native clouds only after successful custom composition. High-quality volumetric clouds remain a later optional effect. |
| Shared motion/temporal foundation | Half-resolution camera velocity + double-buffered depth/normal guides; shared projection, world/resource, cut and surface rejection | Static terrain camera motion. Entity/animated surfaces reject history; no entity skeletal velocity, DLSS or FSR. |
| Reference TAA | Opt-in jittered opaque HDR history, neighborhood clamp, guide validation and motion-weighted reuse | Transparent water, particles, hands/UI and procedural sky are outside opaque TAA. At 4K its budget fallback retains current HDR without jitter. |
| Temporal volumetrics | Quarter-resolution guide-validated history; fast/balanced/high use 4/6/8 jittered steps | `volumetric_temporal off` restores fixed current-frame 8/16/32. Spatial filtering retained. No froxel cache or blue-noise texture asset. |
| Underwater | Colored absorption/scattering before exposure; shadowed underwater direct scattering; native water environmental fog suppressed for admitted surfaces | Unsupported surfaces/transparency keep native rendering. Caustics are projected analytic patterns, not photons. |
| Caustics, foam, rain ripples | Water-only half-resolution depth mask borrows visible native translucent geometry; sky/shadow/depth-gated caustics; shallow-background foam and antialiased rain ripple slopes | Mask is view-dependent and half-resolution; immersion uses camera-column water-height admission. Foam is shallow-depth based, not simulated fluid. |
| Solid reflections | Half-resolution HDR HZB/linear fallback, PBR Fresnel/roughness/conductor weighting; bilateral delta upsample | Screen-visible supported opaque hits only, 48-block ray reach; misses keep sky approximation. No offscreen RT reflection yet. |
| Adaptive quality and measurements | Optional delayed GPU world-region budget, 30-observation down / 120-observation up hysteresis, manual quality ceiling; profiler/export | Default off; no fabricated GPU baseline. It controls volume/reflection budgets and PT submission cadence, not spp/bounces/light count. |
| Presets | `performance`, `balanced`, `quality` | Quality uses existing optional CUDA diffuse GI + OptiX HDR denoising. There is no Cinematic/full-PT preset. |

## Ownership and budgets

Opaque native MRT → separated HDR + diffuse GI → material reflection delta / optional opaque TAA → atmosphere/underwater + clouds + emissive bloom → exposure/tone/native remaining fog. Native translucent water uses a pre-tone HDR background. UI remains native. Dynamic entity material resolve does not advance terrain temporal history a second time.

Cloud shadows affect directional visibility, not emission or local-light direct terms. Reflection composition replaces the admitted fraction of approximate sky specular rather than adding the same specular energy twice. Existing diffuse GI workers, scene window, denoiser and surface history are retained; no larger bootstrap or bounce count.

New allocations at 2560×1440:

| Owner | Storage | Budget |
| --- | --- | --- |
| Shared motion | 2 × half-res R32 depth + 2 × half-res RGBA8 guide + half-res RG16 velocity; 17.58 MiB | 48 MiB; no full-resolution velocity image |
| Volume | Four quarter-res RGBA16F images with spatial filtering/history; 7.03 MiB total | 32 MiB; histories released when temporal is off |
| Solid reflection delta | Half-res RGBA16F; 7.03 MiB | 32 MiB |
| HDR surface resolve | One full-res RGBA16F; 28.13 MiB. TAA adds an equal image | TAA pair ≤96 MiB; no jitter when over budget |
| Water mask | Half-res R32 + private half-res D32; 7.03 MiB | 24 MiB; disabled caustics release mask targets |
| HZB | Existing half-res R32 mip chain; 4.69 MiB | 16 MiB; water can borrow the same allocation, refreshed for final opaque depth |
| Sky/cloud | Shared 64-byte settings, procedural functions | No cloud/sky image allocation |

At 4K shared motion is 39.55 MiB, volume 15.82 MiB, reflection delta 15.82 MiB, water mask 15.82 MiB and HZB 10.55 MiB. Full-res TAA pair would exceed its 96 MiB budget and is bypassed. Independent owners have caps; these numbers are additional to the existing GBuffer, AO, water background, shadows and diffuse GI. Actual GPU timings remain unmeasured on this host.

## In-game checks

Start with the same world, resolution and render distance:

```text
/voxellight mode foundation
/voxellight preset balanced
/voxellight atmosphere_density 0.001
```

1. Day → sunset → night and rain: inspect sky, sun/moon, clouds, projected cloud shadows, and the clouds-off video setting. Use `sky`, `clouds`, `cloud_shadows` on/off for comparison. Nether/end/roof interiors must retain suitable rendering.
2. Iron/gold or a smooth opaque LabPBR floor facing a nearby wall/building: `material_reflections` on/off; offscreen misses must fade smoothly to sky. Move/rotate and cross screen edges. Rough stone should stay diffuse.
3. `volumetric_temporal` off/on at equal density: forest sunrise, cave mouth, low sun and moving animals. Compare shafts, noise, lag/ghosting and FPS. `taa on` is separate and opt-in: slowly pan fences/foliage, edit blocks, move entities, teleport, resize, F3+T and change dimensions.
4. Shallow water in sunlight: compare `caustics`, `rain_ripples`, `underwater` on/off; look at the bottom from above and below water, dive/emerge, shoreline foam, waterfalls and rain. Check glass/lava do not receive water admission.
5. `pathtrace on`: verify existing moving-camera GI still behaves normally, including new-view refinement. `preset quality` enables opaque TAA and optional diffuse GI; `preset performance` disables them plus volume/solid SSR/bloom.

Measurement baseline (keep `adaptive_quality off` for the first comparisons):

```text
/voxellight profile on
/voxellight adaptive_quality off
# Warm up, then repeat fixed-view/moving-view A/Bs for 30–60 s each.
/voxellight export
```

Exports include `.passes.csv`, `.world.csv`, scene samples and settings. `.world.csv` covers the native world render region including terrain/material, entity resolve, volume and water; **not** GUI/hand/presentation or total frame time. It labels OFF and FOUNDATION separately. Summarize each with `python3 tools/benchmark_summary.py file.csv` for GPU/CPU p50/p95. Keep real FPS/frametime measurements alongside these pass/world-region data.

For optional budgeting:

```text
/voxellight gpu_world_target 16.67
/voxellight adaptive_quality on
/voxellight quality high
```

GPU timestamps are polled later without waiting. Missing timestamps retain manual quality; no CPU-time substitution. A single slow frame cannot immediately lower quality; recovery is slower than reduction. This is a world-region target, not a promised 60 FPS controller.

## Later RTX track from the review

The current optional native backend remains CUDA voxel DDA + OptiX HDR denoising with staged readback/upload. This release does **not** claim OptiX GAS/IAS traversal, offscreen RT specular reflections, Vulkan/CUDA external-memory/semaphore interop, rolling textured multi-level clipmaps, dynamic RT geometry, temporal OptiX flow denoising or Full PT Cinematic.

Those require exportable owned Vulkan allocations and external semaphore/device capability integration, then an incremental RT scene and reflection/material contract; they cannot be made zero-copy by importing existing ordinary Minecraft textures. Build and GPU correctness/ownership checks must precede promotion. Full PT, POM, extra GI bounces/spp, more local lights, higher AO/volume sample counts and volumetric clouds stay deferred as the review recommends.

Validation: `./gradlew build clientKit -PnativeKit --offline` passed with221 tests. Actual packaged native/material/water/volume/motion/SSR/TAA shaders link to their declared Vulkan resources and MRT outputs in headless compilation tests. Client startup loaded0.35.0 and verified the native writer, then stopped at GLFW because this host lacks DISPLAY/graphics hardware. This does not establish GPU rendering correctness or performance.
