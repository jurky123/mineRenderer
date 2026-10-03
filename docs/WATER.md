# 0.21 Water Foundation and visual composition

The0.20 review is accepted in scope: preserve current lighting/material/shadow architecture, keep AO and16-source local-light scope fixed, separate composition ownership, and implement water from HDR inputs before adding SSR/volumetrics/GI. Review cites0.20.0; current atmosphere tuning0.20.1 and user-preferred density0.002 are retained.

## Compare

```
/voxellight mode foundation
/voxellight water on
/voxellight water off
```

Polished foundation defaults wateron. Start above shallow water close to shore, with a visible captured stone/sand bottom within24 blocks. Use Fast/Fancy graphics for this first native-stream prototype. `look reference`, AO view, underwater camera, non-overworld skybox, separate Fabulous translucency target, missing background or oversized targets retain native water. Wateroff restores the native water fragments without remeshing chunks or disabling the rest of foundation.

Expect animated normal ripples, increasing reflection at grazing angles, sky/sun highlights, blue-green absorption that strengthens with depth, and subtle screen refraction of the HDR bottom. These are shading ripples, not displaced fluid geometry. First-person hand/HUD, lava, glass and other translucent terrain remain on their native paths. This is local water coverage, not a replacement of the whole ocean horizon.

## Ownership and render contract

`LightingResolvePass` now owns opaque material lighting, environment input, AO and directional visibility history. `VisualComposite` owns atmosphere/bloom/display controls, shared HDR display functions and the WaterPass. Existing material, shadow and world-stream ownership are unchanged.

After terrain lighting/history, WaterPass stores the pre-atmosphere/pre-bloom/pre-tone HDR scene in oneRGBA16F target. It clears this image once per terrain frame; late supported entity lighting merges only its valid HDR pixels. No native LDR SceneColor is captured or used as water's shading background. The alpha channel stores radial camera distance as a half-float guide. Immediately before native translucent terrain, oneD32 copy freezes native opaque depth so it is never sampled while also bound as the water render attachment.

The native translucent terrain pipeline retains its BLOCK format, index buffers, chunk uniforms, draw order, culling, depth and blend state. WaterPass extends the active, processed Minecraft terrain shaders rather than copying their sampler/fog code. The original native fragment main executes as the fallback. Only fragments whose UV lies within the exact `water_still`/`water_flow` atlas sprites can use the HDR water path; other sprites retain original output. Resource packs that reuse water sprites for non-water geometry are outside this initial identification contract. Unsupported shader variants fail closed to native water while leaving foundation usable. Wireframe retains its native pipeline.

Water position/normals come from the actual projected fluid surface, with guarded derivative normals and small periodic world-anchored perturbations. A Fresnel term withF0=0.02 mixes absorbed/refracted HDR background and environment reflection. RGB absorption coefficients are(0.18,0.065,0.028), with thickness capped16 blocks. The sky reflection is analytic, not SSR, cubemap capture or reflected terrain/entity rendering. Skylight interpolated from native UV2 gates sky/direct reflection; local/held lighting reaches water through the lit background, with no new local-light loop.

Screen refraction stays within6 pixels, requires an in-bounds captured background behind the water, and rejects current opaque depths inconsistent with the stored HDR radial guide. Invalid shifted samples use the unshifted background; invalid base samples retain native water. Half-float guide rejection uses max(0.04 blocks,0.002×distance), so it is conservative but not exact8-ULP geometry matching; very close unsupported occluders can remain an approximation. Sky/uncaptured backgrounds, block-entity materials and unsupported native entities can cause native fallback rather than inventing an HDR background.

Fully admitted water composes transmission/reflection in linear HDR, then uses the same atmosphere/bloom/exposure/tone/display-fog functions as opaque output. It replaces that native water fragment once, instead of painting an effect over tone-mapped SceneColor. This is a native-translucent-stage HDR island, not yet a single unified HDR target for every transparent material. Other transparency keeps its native order/composition. Water fades24–32 blocks independently of the far legacy shadow receiver control. Native medium fog remains untouched when the camera is submerged.

## Budgets and lifecycle

One full-resolutionRGBA16F background + oneD32 snapshot =12 bytes/pixel (1440p42.19MiB;4K94.92MiB), with a96MiB cap. No duplicate water geometry and no second full-resolution HDR target, water history or SSR buffer. An additional64-byte WaterSettings UBO holds sprite bounds and bounded camera coordinates. Snapshot images and frame admission are owned by WaterPass and closed/reset for resize, off, resource/world changes and failures. Each frame must capture a fresh terrain HDR scene before water admission; stale prior-frame backgrounds are never used.

Separate Fabulous translucency targets are deferred because they need an explicit HDR/OIT composition contract; a translucent LDR side target must not be treated as final water output. State reports `water`, `waterBytes` and native fallback causes; active means the replacement pipeline was admitted, not that every water pixel had usable material coverage.

## Validation and next step

Automated checks cover Fresnel/absorption, memory bounds, radial-guide rejection, native shader extension + actual uniforms/samplers/stage linkage, unchanged native vertex/depth/blend contracts, explicit snapshot render areas and clear-vs-merge behavior, plus packaged mixin target signatures. The build host has no GPU/display, so runtime validation remains pending.

Compare a shallow pool, a sloped flowing stream, side faces, deep/sky-facing water, grazing angles, camera motion, stone/sand bottoms, glass next to water, and supported/unsupported entities in front of the bottom. Check hand/HUD, underwater/lava, rain/night, Fast/Fancy/Fabulous fallback, resize/fullscreen, F3+T, teleport, dimension change, wateroff/on and world exit. If a shader/resource pack fails, inspect the native-fallback reason and log; off/on can retry after fixing it.

Do not immediately add SSR/caustics. First accept this HDR/native-stream seam. Next is explicitly shadowed low-resolution volumetric integration with its own volume/depth filtering budget. GI and the previously recorded major cache/mesh/DDA optimizations remain deferred.

## 0.21.1 screenshot follow-up

Blue grid/dashes in2026-10-03_11.44.01.png prompted moving surface derivatives before native fragment discard and divergent fallback. Neighboring quad/helper lanes must all evaluate the derivatives. Normal rejection now checks zero/nonfinite cross products rather than a fixed screen-scale cutoff, and atlas identification allows1e-6 normalized UV rounding tolerance. Existing HDR/depth guide guards remain; please retest the same shoreline and camera motion.

## 0.22 native background coverage

With native material source, water follows the captured visible-terrain HDR background instead of fading at24–32 blocks. Local reference keeps the old fade. Background validation, sky/unsupported occluder fallback and native translucent/underwater restrictions still apply; this does not add geometry waves or SSR.


## 0.25 phase1 — HDR scene reflections

Native Fast/Fancy water now traces the reflected world-relative ray against the existing immutable opaque depth. Project with the bound native projection and camera rotation; select16/24/32 samples by quality, with at most48-block reach and5 binary refinements at a negative→positive depth crossing. Accept only a supported HDR texel whose stored radial guide agrees with native depth. Fade confidence near screen edges, the trace limit and uncertain thickness; blend misses to the existing sky reflection. No reflection image, full RGB history, geometry pass or scene-color copy is added.

This is screen-space reflection of current supported opaque surfaces. Offscreen/occluded objects and unsupported HDR surfaces cannot be reflected. Large steps can miss thin objects and changing visibility can still change confidence; there is no SSR temporal history. First-ray missing/sky samples reject the trace conservatively. Compare `water_reflections off/on` next to an on-screen building, moving slowly through grazing viewpoints. Do not infer working scene reflections from the water active status alone.

## 0.25 phase3 — Water animation and quality controls

Replace the two faint GameTime-driven normal components with three directional normal-wave harmonics (default slope strength0.12), using bounded monotonic phase rather than a world-day timer. Integer spatial frequencies repeat over64 blocks, matching camera modulo64 and preserving continuity at positive/negative large world coordinates. Speed changes advance the previous phase before applying the new rate;0 freezes the current phase. GPU views/settings close/reload does not reset these controls or the CPU phase. Animation currently advances with elapsed render time, including paused menus.

`water_waves off` supplies flat geometry normals; `on` restores normal waves. `water_wave_strength 0..0.3` and `water_wave_speed 0..3` expose artistic controls. These deform reflection/refraction/highlight normals, not water vertices or shoreline silhouettes; this is not geometric wave simulation. Defaults are waves/reflections on and quality balanced. Presets change trace/sample budgets only, not density/exposure/world ranges. See[combined checklist](TEST-0.25.md).
