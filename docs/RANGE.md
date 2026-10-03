# Range and quality decision

The user's 0.21.1 shoreline retest confirms the seam fix, but exposes the next priority: the renderer's local coverage bubble. Range work takes precedence over volumetrics. This is an implementation contract, not a delivered increase in render distance.

## Limits verified in the current code

`MaterialSurfaceStore` selects camera ±2 sections from `WorldSceneBridge`, tessellates its own ENTITY-format meshes, and admits one build per frame under a 16 MiB resident limit. `VisualPolish` fades supported lighting at 24–32 blocks; `WaterPass` uses the same distances. Directional receivers separately stop at 48 blocks. The scene bridge is a bounded shadow/local-scene window, not the complete native visible-terrain stream.

Increasing these constants together would grow duplicate geometry and build latency, while leaving shadow density and cache churn unresolved. A larger slider alone is not the solution.

## Separate coverage from effect quality

| Work | Proposed first target | Cost control |
| --- | --- | --- |
| Material, sky, emission, tone and analytic atmosphere | Native visible loaded terrain, initially validate 128 blocks | Consume native terrain submissions; no radius-sized duplicate mesh store |
| Water | Captured HDR background range | Preserve invalid-background fallback; share material coverage rather than a fixed 32-block cutoff |
| Directional shadows | Initially 128-block receiver range | Near/middle/far cascades with decreasing spatial resolution; light-aware offscreen casters |
| Local shadowed lights | Keep current near-field reference initially | Keep 16-source and DDA budgets; eventual clustered selection |
| AO | Screen-space, with finite world-space sampling radius | Keep half-resolution buffers; no volume-sized geometry requirement |
| Dynamic casters | Near and middle first | Independent count/upload budgets and cascade intersection tests |

128 blocks is an initial validation target, not a guaranteed performance claim or the final maximum. Material coverage should ultimately follow Minecraft's loaded/visible terrain and configured render distance. Expensive effects may have shorter ranges without turning the entire scene back into vanilla lighting.

## Integration sequence

1. Prove material capture against one native opaque/cutout submission using its actual vertex/index buffers and section transforms. Preserve atlas sampling, alpha coverage, chunk visibility and native depth agreement. The existing water pipeline substitution demonstrates submission interception, but is not sufficient for opaque material capture.
2. Resolve the attribute contract before replacing the independent meshes: native terrain attributes cannot be assumed to contain the current ENTITY normal/emission metadata. Inspect the pinned compiler/format. Attach compact attributes during native compilation or use a verified primitive/material side stream. Do not recover albedo from lit color or silently revert to depth-derived normals. Separate light changes from geometry revisions where supported.
3. Establish buffer ownership and resource/world generation invalidation. Borrow native buffers only during valid submissions; never close them from VoxelLight. Keep the existing material path as the comparison/fallback until native coverage and reload tests pass.
4. Extend opaque foundation coverage to the first target, remove the fixed global fade only for verified native material pixels, and let water consume those pixels. Coverage failure must remain observable. Do not enlarge `WorldSceneBridge` into a copy of all visible terrain just for materials.
5. Extend directional coverage independently. Stabilize near-cascade texel density, distribute far resolution, and budget light-space caster admission. Longer receivers require farther offscreen casters, especially at low sun angles; the current 384-section cap must not silently truncate them. Start with a same-quality full-update reference, then introduce progressive cached updates with valid fallback and dual-angle epochs for moving sunlight.

## Acceptance and measurements

Compare 32/64/128-block coverage on the same route, resolution and world: shoreline, forest, town, low sun, night, fast flight and block/light edits. Record CPU frame/build p50/p95/p99, actual delayed GPU pass timings when available, material/caster bytes, draw/upload counts, deferred work and missing coverage. Report unavailable GPU measurements explicitly.

Capture matching images to check real normals, cutout edges, water fallback, distant lighting, cascade transitions and offscreen shadows. Test F3+T, resize, teleport, dimension changes and chunk unload. The near-field accepted image must retain equivalent quality; distant degradation should change shadow detail, not produce a camera-centered vanilla-color boundary. No automatic quality oscillation initially: fixed budgets and explicit presets make comparisons reproducible. Choose defaults after measuring the RTX 4060 user's scenes; no invented FPS target.

## Current delivery

0.22 implements the native material-stream proof using inline compile attributes and one extra raster over borrowed native buffers. Material/display/water coverage no longer has the32-block fade for supported native terrain. Shadow/local-light limits remain unchanged. See [implementation and validation](NATIVE-COVERAGE.md). Single-raster MRT and longer shadow coverage remain pending.


## 0.23 delivery

0.22.1 native coverage is user-confirmed.0.23 extends directional receiver range to128 blocks without growing the384-section scene bridge or shadow geometry budget. Distant static casters borrow Minecraft’s compiled solid/cutout buffers from light-space section lookup, independently of main-camera visibility. Shadow maps retain2048/1024/1024 dimensions; middle/far coverage increases at reduced texel density. The near map retains its former projection and scale. Uncompiled offscreen sections remain a declared limitation and are reported; no FPS/GPU improvement is claimed. See[details and acceptance](EXTENDED-SHADOWS.md).
