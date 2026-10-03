# Single-raster native material capture — 0.31.1

After the user confirmed Material 2.0 working, this implements the review's next performance milestone before sky/cloud development. Opaque terrain is submitted once into native color/depth plus the four material attachments. It borrows the same native draw groups, buffers, chunk UBOs, atlas and lightmap. No additional world geometry or material textures are allocated.

The active native 26.2 shader supplies filtering, lightmap, fog and native alpha cutoff. The material extension shares the unlit texture sample; tangent derivatives run before native discard. Unsupported material markers clear only material coverage and retain native color. Solid and cutout terrain use separate pipelines. Native scene color and depth load their existing contents; only material attachments clear. Private material depth receives a copy after submission, preserving existing lighting/entity/GI consumers without a second terrain raster.

`single_raster` defaults on for native Vulkan material/foundation modes. Off, local material mode, other backends, wireframe and unavailable targets retain their previous paths. Shader contract/compile failure or runtime failure disables single raster and returns to the native-plus-material reference path. This is pinned to the 26.2 terrain shader; shader mods with another source contract are not validated. Five color attachments must be supported by the GPU/backend. Translucency, water, entities and UI retain their existing passes.

## In-game comparison

1. Install 0.31.1 and run `/voxellight mode foundation`.
2. Compare `/voxellight single_raster off` and `on` at the same location, view, time and render distance. Test solid blocks, leaves, grass, distant chunk fading and PBR normal/specular maps. Appearance should remain comparable; cutout material coverage now follows native cutoff rather than the old extra .5 threshold.
3. `/voxellight status` should report `materialRaster=single` with `singleRaster=true`. `reference` indicates comparison/fallback. Check ALBEDO, SURFACE_NORMAL and MATERIAL_COVERAGE modes too.
4. Test F3+T, resize, dimension travel, fast movement, native/local switching and opaque entities. Check water still renders normally. If a problem appears, `/voxellight single_raster off` restores the accepted reference path.
5. Enable `/voxellight profile on`, collect separate stationary off/on intervals, then `/voxellight export`. The on interval should have `native_material_single` and `material_depth_copy` timings and no `material_native` duplicate terrain pass. Compare full frame FPS and lighting/material pass costs; the new timing includes native terrain work whereas the old material timing includes only the extra pass.

Targets remain 20 B/pixel plus existing atlas/LUT and HDR budgets. The depth copy remains a bandwidth cost; this milestone does not claim zero-copy depth, reduced memory or a measured FPS improvement. GI sample/history policies, local-light count and shadow epochs remain unchanged.

219 Java tests pass, including real native solid/cutout Vulkan shader rebinding, five ordered outputs, all six vertex inputs, depth state, color/depth load semantics and resize areas. The build host has no GPU/display; game appearance and FPS require user validation. Next art milestone: a bounded sky/cloud/weather layer with cloud shadows. Shared temporal/motion, underwater and reflections remain later work.
