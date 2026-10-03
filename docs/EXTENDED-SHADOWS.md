# Extended directional shadows — 0.23

The user confirmed native material coverage in0.22.1. This stage extends directional shadows independently, preserving the small local-light/scene window rather than expanding duplicate geometry.

## Coverage and quality

Default receiver range is128 blocks; `/voxellight shadow_distance 12..128` controls the final8-block fade. Native material/atmosphere/display coverage continues to follow supported visible terrain. Artificial shadowed lights remain near-field; dynamic entity/block-entity selection retains its existing count/upload/distance limits.

| Cascade | Map | World half-extent | Receiver transition |
| --- | --- | --- | --- |
| Near | 2048² | 32 | 12–16 blocks |
| Middle | 1024² | 96 | 40–48 blocks |
| Far | 1024² | 192 | Outer fade120–128 blocks |

The near projection and texel density are unchanged. Middle/far detail is deliberately lower; three map allocations remain unchanged (30MiB static color/depth plus24MiB dynamic depth). Projection depth spans cover the longer light extrusion; shaders derive the comparison bias from the matrix so it remains approximately0.036 world blocks rather than scaling with the depth span.

## Native offscreen casters

The existing independent near caster store and384-section bridge remain unchanged. Missing independent sections and distant sections can use native compiled geometry. Query ViewArea by actual section position, not LevelRenderer.visibleSections: a building behind the camera can cast a shadow when its native mesh exists. Loaded columns only; skip empty sections and validate rotating-storage origin. A conservative section sphere intersects the128-block receiver sphere swept96 blocks toward the light. One-block model overhang is allowed.

Borrow solid/cutout vertex/index allocations under the native dispatcher lock. Preserve native custom index type/offset or use the quad sequential index fallback; base vertex uses the actual36-byte BLOCK stride. Refresh allocations every prepare rather than reusing offsets after native relocation. VoxelLight never closes, recompiles, uploads or owns these distant buffers. Their extra duplicate geometry memory is zero; larger raster/draw cost is real and unmeasured.

Compare mesh identity and available layer mask to invalidate shadow pages on compilation, edits, unload and independent/native handoff. Cutout bounds follow the existing conservative per-frame invalidation. Directional history resets when the borrowed caster set changes. Normal native light rebuilds can therefore cause conservative history resets; separate native geometry/light revisions are future work.

## Limits and verification

This is compiled-terrain reuse, not a complete loaded-world shadow compiler. Offscreen sections Minecraft has never compiled can be absent until visited/compiled by the native renderer. `nativeShadowPending` reports non-air missing/uncompiled sections and missing layer allocations; it is not a count of shadowed pixels. Independent near casters continue to provide offscreen coverage within their existing budgets. Models extending beyond the one-block section overhang are not guaranteed. Dynamic casters have shorter independent budgets; longer static coverage does not imply128-block coverage for every entity.

Tests verify conservative admission against exact segment/AABB distance, farther upstream blockers and projection clipping at every cascade/anchor offset, world-coordinate/view invariance, fixed image budgets, and native shader compilation/reflection. This environment has no display/GPU for visual or performance validation.

In game compare48 versus128 on trees/buildings64–120 blocks away, low sun, camera rotation and buildings behind the camera. Check near acne/detail, cascade transitions, new terrain/fast flight, block changes, chunk unload, F3+T, resize, teleport and dimension changes. Capture `nativeShadowSections`, `nativeShadowPending`, `nativeShadowSelectionNs`, caster draws and frame timings. Continuous world-sun full-cache invalidation still exists; no FPS gain is claimed. After acceptance, proceed to quarter-resolution shadowed volumetrics.
