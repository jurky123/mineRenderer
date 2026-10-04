# Physically based RT light transport — 0.37

The 0.36 RTX architecture remains: native single-raster primary visibility → exportable guide buffers → OptiX triangle GAS/IAS → separated diffuse/specular/transmission → OptiX temporal AOV denoising → HDR composite → environment/display. Material 3 changes scattering and transport rather than adding another generic reflection/GI overlay.

## Ownership and integrator

`bsdf.h` evaluates/samples BSDFs. `transport.cuh` implements light sampling and path transport. `caustics.cuh` implements bounded directional light tracing. `program.cu` owns OptiX programs and dispatches signals. This separation replaces the previous material branch chain in `incoming()`.

The path tracks throughput, radiance, depth, previous BSDF PDF/delta flag, eta scale and an eight-entry identity-aware medium stack. Every secondary/tertiary hit loads its full textured Material 3. The next ray carries **f × |cos| / PDF**, not just albedo. Russian roulette starts after two bounces and compensates surviving throughput. Linear RGB is used throughout.

Primary direct sun/moon and selected local diffuse lighting remain raster-owned. Primary emission is raster-owned. RT diffuse excludes the first primary-to-emitter hit; RT specular owns reflected emitters/environment, with raster selected local specular disabled. A player-held virtual point light remains raster-owned at primary surfaces and is sampled by RT at secondary surfaces. Secondary direct lighting, including sun on a red/gold/copper wall, is RT-owned. This is how colored reflected energy reaches later surfaces without duplicating primary direct light.

Primary dielectric paths are split into reflection and transmission branches, each retaining Fresnel energy. Transmission replaces the admitted dielectric/native fragment; it is not added to an opaque HDR background. Underwater camera transport owns the water segment and its scattering; the RTX output skips the raster underwater color overlay. Native display fog remains part of compatibility composition.

## NEE and MIS

At nonsingular diffuse/glossy/rough-dielectric hits, the integrator samples one light:

- Sun/moon: a finite angular cone, irradiance divided by its solid angle for radiance/PDF consistency.
- Environment: full-sphere direction sampling and the same miss radiance.
- Emissive terrain: area triangle sampling, area-to-solid-angle Jacobian, RGB textured emission, a power CDF.
- Held light: virtual point intensity/inverse-square attenuation, a discrete sampling probability.

Native emissive triangles and LabPBR-only emissive texels enter the same light list. Compilation snapshots retain a CPU atlas emission admission map; only geometry/light metadata crosses JNI, never normal-frame image staging. Candidate hints may over-admit zero-emission texels; actual radiance comes from the GPU material. The list is capped at 4,096 triangles. Emitters outside the cap still contribute when reached by BSDF sampling.

Power-heuristic MIS combines light/BSDF PDFs for NEE, emissive hits and environment/sun misses. Delta events use unit MIS weight; point lights use their discrete probability. RGB visibility transports absorption/extinction across interfaces. Straight shadow transmittance is distinguished from refracted focusing.

The permissive 10,000-radiance safety clamp can be disabled with `rt_firefly_clamp off`; reference always disables it. Correct PDFs and MIS are the variance controls; no GI ×2, gold tint boost or dark-glass brightness patch is used.

The algorithm follows the responsibilities and MIS principles described in [PBRT's better path tracer](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer); it is an original implementation, not a PBRT port.

## Signals, cache and reconstruction

Diffuse cache probes retain low-frequency L1 radiance at 4/8/16-block spacing. They do not store metal reflections or transmission. Coated/rough glossy transport stays in the per-pixel specular signal. Thin foliage queries front and back irradiance with the material's transmission mixture. Reference diffuse bypasses probes and evaluates the current BSDF explicitly.

The pinned native terrain alpha cutout threshold is 0.5; OptiX now uses the same threshold (captured entity cutouts retain their separate 0.1 contract), fixing the prior raster/RT silhouette mismatch. Texture filtering still differs at subpixel footprints, so conservative reconstruction rejection remains necessary.

Each current observation carries material ID, IAS object identity, first-hit distance and IOR, with actual world position/geometric normal. Previous guides also retain specular hit distance, material alpha and interface identity.

| Signal | Full-resolution admission/reconstruction |
| --- | --- |
| Diffuse | 3×3 bilateral: exact material, world-plane/depth, geometric normal >0.95, bounded world footprint |
| Specular | Same first-surface tests plus alpha agreement and reflected hit distance; sharp surfaces use one observation, rough surfaces allow admitted neighbors |
| Transmission | Actual native full-resolution interface, matching material/normal/world plane and first-hit distance; nearest admitted observation; no glass/water painting over unrelated opaque pixels |

Failed admission retains raster/current HDR. Raster RT ownership uses matching guide/material/plane/normal/footprint validation so rejected edges retain a lighting baseline. The RT denoiser's flow trust additionally rejects material/object/hit-distance changes. Dynamic captured transform/model changes disable previous AOV reuse conservatively and reset reference sums; there is no invented offscreen animation velocity.

Mirrors may lose RT coverage at a subpixel edge rather than leak reflection onto background. The RT grid remains bounded at ≤640×360, so this is conservative reconstruction, not full-resolution ray tracing. `rt_debug edge_confidence` shows accepted diffuse reconstruction in green and rejected pixels in red.

## Media and water

Solid dielectrics push object/class/IOR medium identity (independent of per-texel roughness IDs) and exit by identity; nested identical materials have counted entries rather than one boolean. The stack supports eight entries. Thin panes apply parallel-interface Fresnel/Beer without a volume push. TIR reflects and never incorrectly enters the next medium. Stained glass absorption is RGB; zero explicit absorption derives the tint attenuation from linear texture reflectance.

Water defines shared `sigma_a`, `sigma_s`, `sigma_t=sigma_a+sigma_s`, IOR and HG g. Camera-underwater initialization reads the same quantized water material as surface hits. Propagation multiplies Beer extinction; a sampled distance on each water segment estimates single-scattering sun/environment/emissive/point NEE with HG phase and colored visibility. Default authored values are absorption (0.16,0.06,0.035), scattering (0.018,0.035,0.045) inverse blocks, g=0.7 and IOR=1.333.

This is **single scattering**, not multiple volumetric path tracing. Miss segments use a bounded 128-block medium distance. Native scene coverage and native culled internal interfaces limit complicated touching/nested transmissive geometry; medium unit tests cannot establish correctness for every meshed block arrangement. Compare stacked glass/water against reference in game.

## Directional caustic cache

A 64×64, quarter-block world-XZ cache covers 16×16 blocks around the camera. Each frame traces 4,096 stratified sun photons through the actual glass/water surfaces, using current macro/micro/rain normal, Snell refraction, Fresnel transmission and RGB extinction. Photons deposit irradiance at an admitted receiver; a separate launch resolves compatible history before surface lighting reads it. Focusing comes from ray density at the receiver, not an independent caustic noise texture.

The cache supports one near-horizontal receiver height per column, at most 12 interfaces and bounded ray distance. It is not a general bidirectional light tracer and does not cover all vertical/overlapping receivers. Valid deposits replace the straight transmitted-sun estimate on those diffuse receivers to avoid summing both. `rt_caustics off` restores straight transmittance. Raster fake caustics remain only for the fallback. Reference disables the photon approximation.

## Reference and diagnostics

```text
/voxellight preset rtx_quality
/voxellight rt_reference on
/voxellight rt_reference spp 256
/voxellight rt_reference reset
```

Hold the camera still. Four new samples per pixel/frame accumulate in separate GPU sums to 4–4,096 target spp, with eight bounces, NEE/MIS, no probe GI, no temporal denoising, no photon cache and no radiance clamp. Camera/projection, scene/material/settings changes reset sums. Sun/environment/waves/weather are held at the first accumulation frame until reset. `rtReferenceSamples` reports convergence. `rt_reference off` returns to realtime. This is a **raster-primary secondary-transport reference**, not Full PT primary visibility or a replacement for GPU acceptance.

Material debug names: `material_class`, `base_color_linear`, `microfacet_alpha`, `F0`, `eta`, `k`, `coat_weight`, `coat_roughness`, `transmission`, `absorption`, `sigma_s`, `sigma_a`, `medium_id`, `porosity`, `sss`, `emission`, plus existing albedo/normal/IOR views. Transport: `path_throughput`, `bounce_count`, `light_sample_type`, `bsdf_lobe`, `mis_weight`, `direct_secondary`, `indirect_diffuse`, `indirect_specular`, `emissive_nee`, `sun_nee`, `bsdf_pdf`, `light_pdf`. PDFs are visualized as PDF/(1+PDF), not raw HDR values.

A/B: `rt_gi`, `rt_reflections`, `rt_transmission`, `radiance_cache`, `rt_denoiser`, `rt_caustics`, `rt_reference`, `rt_firefly_clamp`. Performance/Balanced/raster and CUDA voxel reference remain unchanged as independent fallback paths.

## Profiling, synchronization and memory

Existing delayed CUDA events report AS build/update, radiance cache, diffuse, specular, transmission, denoiser, caustic and geometry benchmark GPU times; Vulkan profiles guides/dielectric merge/composite. `profile on` and `profile export` collect p50/p95. BSDF/material/light sampling and medium work are integrated into these ray kernels, not fictitious separate fullscreen passes. Asynchronous operation telemetry reports ray/visibility, bounce, medium, light, BSDF, material and photon counts; ratios are available per RT pixel. These operation counts are not isolated wall-clock milliseconds.

All image exchange remains UUID-matched external GPU memory and binary semaphores. No normal-frame CPU image readback/upload or device-idle wait was introduced. Only a delayed 36-byte statistics readback is added every 60 frames; geometry/light compilation metadata remains CPU-produced. Deferred retirement retains resources until CUDA events complete.

New bounded allocations: table +1 MiB over the old table; surface key 16 bytes/RT pixel in the exchange and texture; previous key and signal guides 32 bytes/RT pixel; reference sums 48 bytes/RT pixel; operation counters 36 bytes/RT pixel; two caustic banks total 256 KiB. Reference sums currently reserve memory even when reference is off. Existing denoiser memory/scene/export caps remain. Actual driver allocation usage comes from status, not a guessed FPS result.

## Acceptance sequence

1. Leaves/fence/slab/stairs/panes/shoreline/entity/metal/sky silhouettes: inspect diffuse/specular/transmission and edge confidence while moving. No cross-object bleeding.
2. Cornell white room with red and blue/green walls and one emitter: compare realtime versus 256-spp reference, then turn 180°; low-frequency cache must not clear on rotation.
3. Gold/copper/iron/silver under the same light: compare tint at the metal and on a white receiving wall; include offscreen geometry.
4. Roughness 0.02/0.05/0.1/0.2/0.4/0.7/1: check continuous width/energy and LabPBR conversion.
5. Clear/pane/stained/stacked/frosted glass and air→glass→water, water→glass→air, nested glass: refraction, TIR, RGB attenuation, edge history.
6. One-block/four-block/deep water, underwater, sunset/night/rain: extinction/scattering, shafts, shared wave caustics; A/B caustic and denoiser independently.
7. Dry/rain stone/wood/copper and front/back/grazing leaves: water film without making every surface a mirror.

For each fixed camera/scene/quality, warm the scene, record at least 60 seconds, export profile, save realtime/reference screenshots and status. This host has no NVIDIA GPU/display; these image/performance gates remain pending. No ReSTIR, spectral renderer, full layered multiple scattering, full primary PT or unmeasured quality claim is added.

## 0.37.1 convergence follow-up

Reference quality is change-sensitive; unchanged per-frame budget updates cannot clear accumulation. Projection jitter and adaptive budget application are suppressed during reference. RT animated instances freeze after the first sample, resume on exit, and refresh after reset; use static primary scenes for screenshots. Enabling reference also enables OptiX/Foundation. Settings and progress are described in [SETTINGS.md](SETTINGS.md).


## 0.38 alpha integration

See [0.38 implementation ledger](RTX-QUALITY-0.38.md), [environment status](RT-ENVIRONMENT.md) and [full-reference status](FULL-REFERENCE.md). This alpha adds opaque primary glossy NEE with independent `/voxellight rt_primary_glossy_nee on|off`; the sun remains raster-owned, while RT owns local/emissive/environment glossy transport when enabled. Coated RT diffuse substrates no longer add base plastic specular under the coat. UV tangent directions are retained for RT anisotropy. Full primary reference, environment importance sampling, async reference banks and the other ledger items remain pending; no GPU acceptance claimed.

### 0.38 alpha 2 checkpoint

Shared environment importance sampling, full-primary asynchronous reference, GGX reflection energy compensation and medium free-flight are now implemented. Details and limitations are recorded in [0.38 ledger](RTX-QUALITY-0.38.md), [RT environment](RT-ENVIRONMENT.md) and [full reference](FULL-REFERENCE.md). Native modules include build-time OptiX-IR plus PTX; `-Dvoxellight.rt.module=ptx` selects the comparison, `VOXELLIGHT_RT_OPTIMIZATION=0|1|2` selects compilation optimization; default uses OptiX default optimization. Native startup/runtime GPU benchmarks remain pending.
