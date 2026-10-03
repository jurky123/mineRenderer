> **0.26.1:** Epoch blending is disabled by default after the user reported higher FPS with it off. Use `shadow_epochs on` only for comparison. Static foliage caching remains active in either mode. Extra endpoint sampling is a suspected cost; pass timings are needed before choosing a replacement.

# 0.26: moving celestial light with reusable terrain shadows

The previous cache treated every distinct native sun/moon angle as a full-map invalidation. This release retains the128-block coverage, near/middle/far resolutions and current-frame entity/block-entity shadows, but displays two fixed-angle terrain epochs, with a third allocation preparing the following endpoint. The normal world-sun path enables them by default; fixed sun and `shadow_cache off` use the continuous-angle reference.

## How it works

At initialization A uses the actual light direction; B is half a degree ahead. A is completely rendered before use. B is initialized in the background with4 near +2 middle +2 far pages per frame: at most8 pages, at256² texels per page. For an unchanged scene all96 future pages complete in16 frames (near64 pages /4; other maps16 /2). A future epoch is never sampled until every cascade is initialized.

Visibility is blended between A and B as the native light angle advances. It is not depth interpolation and does not quantize the visible sun, direct-light color, moon intensity or weather. While A/B are displayed, look-ahead C builds at the following angle. At rollover B becomes A and the completed C becomes B; the old A allocation is recycled for the following C. This avoids periodically holding shadows still while rebuilding the next displayed endpoint. Completion ramps the display weight by at most0.05/frame instead of abruptly adopting an already advanced blend. Time reversal/jumps, unavailable future endpoints and source changes reseed from the current direction. World/resource/renderer reset releases all three allocations. Initial B and each new look-ahead C use the same8-page background budget; only one unpublished endpoint is built at a time.

Camera-anchor changes are repaired synchronously for both published epochs, so walking does not drop a previously blended B back to A. Geometry addition/removal invalidates both old/new footprints, and published maps apply those repairs before lighting. Only construction of an unpublished future map is page-budgeted. Initial warm-up, a new anchor or edits can therefore require more than8 total pages. An unfinished B restarts on anchor changes; A stays valid. The existing near-mesh and native-allocation ownership rules remain unchanged.

Dynamic entities and block entities retain three maps at the **actual current light direction**. Each terrain PCF tap is mapped to the matching receiver-plane position in the current dynamic map; terrain and dynamic blockers are unioned before filtering. This avoids averaging separately filtered occlusion, which could miss disjoint blockers. Dynamic coverage still excludes directional temporal history. Volumetric scattering uses the same epoch visibility and current dynamic shadows. Raw `shadow_map` diagnostics now show the current terrain epoch only: depths from different light orientations must not be directly min-reduced.

## Static cutout classification

The existing36-byte vertices already carry animation flags. Near caster extraction classifies cutout CPU vertices once. A native `CompiledSectionMesh` now owns the same classification, captured before its CPU vertices are released; no per-frame CPU scan or GPU readback. Static leaves/plants no longer automatically dirty their shadow pages each frame. Animated sprites and unknown/raw/custom vertex formats remain conservative and refresh their footprints. Resource reload/recompile creates a new classification with the new mesh. The ordinary alpha test is unchanged.

## Cost and scope

Six additionalD32 terrain images use at most48 MiB; existingR8 attachments are shared sequentially. Terrain/dynamic shadow textures total at most102 MiB instead of54 MiB. The shared resolve UBO expands from560 to1344 bytes for CPU-uploaded epoch projection/normal transforms and blend controls. No new caster geometry, world scan, camera visibility dependency or scene-color copy. Native caster admission has a2-block guard for the endpoint/look-ahead direction difference; material coverage/render distance and128-block receiver fade stay unchanged.

Warm blended visibility samples both terrain epochs, increasing resolve work. Volumetric shadow queries likewise cost more; the change reduces terrain-map submissions, not every GPU cost. Therefore lower scheduled page counts are **not a measured FPS gain**. Animated-heavy/custom foliage can still refresh all initialized maps. Moving rapidly, large edits and time jumps remain expensive. Half-degree endpoint blending can slightly soften/double a moving shadow edge, especially at low sun; compare with the reference and report it. The directional temporal pass is unchanged in scope and no GI added.

## In-game acceptance / benchmark

Use `/voxellight mode foundation`, `/voxellight sun world` and density0.001. Stand still in a forest or beside a building for30 seconds. Compare `/voxellight shadow_epochs off` and `on`; export each run before mode/reload resets. Shadow silhouettes should remain similar while `mapReuses`/`pageReuses` grow and `updatedPages` usually stays low after warm-up. `nextEpochUpdatedPages` reports background/repair pages separately; `nextEpochReady`, `epochBlend`, `epochRotations`, `lookaheadEpochReady`, and native static/animated cutout layer counts explain progress.

1. Slow camera motion and natural time progression: look for jumps at epoch rollover,8-block camera anchors, low sun and cascade seams.
2. Moving pig/cow/player, chest lid and banner: current shadows must track animation without lagging with terrain epochs.
3. Place/break an overlapping caster, move a torch, unload a chunk: no ghost blocker or missing edited shadow after native geometry becomes ready.
4. F3+T with an animated-cutout resource pack: animated alpha must still refresh; static leaves should show reuse. Unknown emitters must retain conservative refresh.
5. Teleport, dimension changes, resize, large time jumps/reversal where permitted: current map reseeds; future map is withheld until ready.
6. Profile the same static forest/city/ocean view for30 seconds with epochs off/on (`profile on`, then `export`). Compare terrain shadow submissions, lighting and volume timings, total FPS, resolution and render distance. Keep water/quality settings fixed. Send status and `.passes.csv` exports.

Use `shadow_epochs off` for the exact continuous-angle sampling reference, and `shadow_cache off` for unconditional full redraws. Preferred water strength0.09 and atmosphere density0.001 are preserved. This environment compiles real native Vulkan shaders and verifies mixins at startup but cannot measure GPU visuals or frame rate.
