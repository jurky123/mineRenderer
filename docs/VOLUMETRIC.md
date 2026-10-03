# Shadowed volumetric light — 0.24

The user confirmed0.23 extended shadows. This phase uses those shadow maps to light an actual sampled air path, rather than inferring direct scattering from receiver skylight alone. The default remains density0.001.

## Rendering contract

Polished foundation, Overworld skybox, non-fluid camera medium and enabled atmosphere admit the pass. `/voxellight volumetric on|off` selects shadowed medium or the0.23 analytic comparison. Atmosphere off, density0, AO debug, reference look, underwater/lava/powder snow and other dimensions disable it. Startup effects remain off. Unsupported HDR pixels and native sky are not overwritten.

One quarter-resolutionRGBA16F stores in-scattered HDR RGB and transmittance A. Sixteen stratified ray samples integrate camera-to-surface density using height above sea level. Optical depth is bounded at2. The forward phase uses anisotropy0.65 and an artistic scale0.25; direct radiance follows the existing environment sun/moon color and strength. New moon cannot manufacture direct light. March distance is at most96 blocks, clipped inside the configured directional-shadow final fade.

Terrain and dynamic shadow layers are combined at each sampled air position. Four comparison taps soften each selected map; overlapping cascades blend using the same world-distance ranges as surface shadows. Out-of-map coverage rejects direct scattering. Native uncompiled/offscreen caster gaps still affect this result: a clear texel does not prove the air is physically unoccluded. Sky/ambient scattering retains the endpoint receiver-skylight approximation; this is not an independently occluded sky-volume or multiple-scattering solver.

Jitter is a deterministic spatial gradient, fixed across frames. There is no blue-noise asset, frame-varying noise, volume history, motion vector or denoiser in this first reference. Low-resolution/sample noise and march steps can remain visible, especially in motion. Refinement requires in-game evidence rather than assuming temporal solves everything.

## Composition and resources

Depth-guided2×2 upsampling compares each quarter sample's original full-resolution depth coordinate with the current receiver distance. Invalid/edge-crossing samples are rejected with neutral transmittance1 and scattering0. This prevents foreground objects receiving far-background haze, but thin geometry can have incomplete volume coverage.

Composition is `opaque HDR × transmission + scattered HDR`, followed by emissive bloom, exposure/filmic mapping and the existing native fog. Analytic atmosphere is replaced on these pixels, never added a second time. Opaque entity composition recomputes the quarter pass against the current depth; `volumetricPasses` makes this additional work visible. No RGB history is modified. Native HDR water retains its existing analytic camera-to-water atmosphere path; full transparent/sky volumetric integration is deferred.

Extra image budget is8 bytes per quarter pixel:1440p1.76MiB,4K3.96MiB, cap8MiB. One neutral1×1RGBA16F texel and16-byte settings remain for fallback. Resize reallocates the target, close/reload releases its views/textures/settings; no native buffer/depth ownership or extra full-resolution image. Density is shared with atmosphere controls. GPU/CPU performance is unmeasured in this environment.

## Acceptance

Use a forest edge or window looking toward a low sun with opaque background behind the air. Compare volumetric off/on without changing exposure/density. Density0.003 is an optional stronger demonstration; restore0.001 afterward. Expect light shafts/shadowed air between objects, not a whole-scene brightness boost.

Check slowly moving camera and animals, foreground leaf/fence edges, cascade transitions, enclosed caves and entrances, rain, full/new moon, shoreline/native water/underwater, F3+T, resize, teleport and dimension changes. Record matching images plus status and frame timings. Automated tests check medium energy/transmission, forward phase, depth rejection, odd-size/budget admission, render-area/depth attachment policy, actual native shader compilation/reflection and shared water stage linkage. No graphics device/display is available here, so visual stability remains pending.


## 0.25 phase2 — Spatial stability and phase3 quality controls

The0.24 pass is user-confirmed working. Default filtering now runs horizontal and vertical5-tap positive Gaussian kernels over current quarter-resolution scattering/transmittance. Each neighbor samples depth at the same full-resolution coordinate as its quarter ray and rejects radial-depth mismatch using the existing guide tolerance. Normalized weights preserve constant radiance and bounded transmission; full-resolution2×2 depth-guided upscale remains. Thin geometry and close-depth edges can still be imperfect. This is spatial smoothing, not temporal stability or RGB accumulation.

One equal-size scratch increases admitted image bytes to16/quarter pixel (1440p3.52MiB,4K7.91MiB) under the existing8MiB cap. `volumetric_filter off` releases scratch on the next render, restores raw0.24-style output and uses8/quarter pixel. Filter settings add16 bytes; medium controls are32 bytes for the quality sample count. Two filter draws run per volume composition; late entities still recompute against current depth. `volumetricPasses` counts march and filter draws.

`quality fast/balanced/high` selects8/16/32 march samples and16/24/32 water reflection samples. The shader loops are bounded32; optical-depth clamping uses the actual step count to preserve the same maximum2 opacity and constant-medium energy. Density0.001, anisotropy, phase scale, exposure, map sizes and effect distances do not change. No adaptive switching. Use the[combined checklist](TEST-0.25.md) to assess smoothing/detail loss, movement, silhouettes and frame cost.
