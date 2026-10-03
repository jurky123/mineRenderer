# 0.25.1: water motion and measured hotspot follow-up

0.25 was reported working with modest visible benefit; the user subsequently reported unnatural waves. This patch changes water normals, not geometry or shoreline shape. Six smaller wind-biased ripple bands replace three broad repetitive waves, with a reduced default strength0.09 and footprint filtering before native fragment discard. The64-block spatial and12-second clock periods remain continuous. Physical displaced waves, foam and caustics remain future work.

## In-game checks

Use `/voxellight mode foundation`, keep `/voxellight atmosphere_density 0.001`, and stand beside a pond with a building/tree visible above it. Compare `water_waves off/on` with `water_wave_strength 0.09`; try0.15 if too faint. Check close water, distant ocean, grazing views and camera crossings at64-block boundaries. Use `water_wave_speed 0` to inspect a frozen pattern, then1 to resume. Check that no blue seams return, underwater/native fallback works, and F3+T/resize remain stable.

Fly through new terrain and use F3+A. The36-byte writer now reserves once and writes directly, preserving native color/light packing and count/lifecycle checks. The stride still costs8 extra bytes per vertex, even with effects off; this patch does not implement sidecar attributes or single-raster MRT.

Distant shadow admission now occurs outside the dispatcher lock and reuses conservative spatial candidates within the same camera section. World/view/radius changes, significant light-direction changes, chunk load/unload and a10-prepare refresh rebuild admission. Candidate padding32 blocks covers within-section camera movement and small direction drift; exact per-frame light-volume checks avoid drawing the padding. Empty sections remain spatial candidates so block placement can use newly compiled geometry without waiting for admission. Each frame re-borrows current buffers under the allocation lock. It never owns/closes native buffers. Test distant block edits, low sun, rapid flight, teleport, chunk unload and render-distance changes.

## Performance baseline

`/voxellight profile on` enables bounded nonblocking per-pass timestamps. Let each view run20–30 seconds, then `/voxellight export`; `/voxellight profile off` stops new samples. Profiling defaults off; it does not add textures or waits. Mode/resource reset clears its ring, so export each A/B run before switching modes or F3+T. Delayed final query samples can have blank GPU columns; no guessed durations.

Export directory: `.minecraft/benchmark-results/voxellight`. The new `.passes.csv` records CPU submission and GPU execution separately for native/local material capture, three terrain and dynamic shadow cascades, AO, lighting, temporal, bloom, volumetric march/filter, tone, water background and the native translucent group including water. It also records CPU caster selection and buffer borrowing (including lock wait). The `frame` column of this supplementary file is a unique sample ID, not a Minecraft frame number; width/height are0 because targets differ. GPU timings are per pass, not whole-frame FPS. Translucent timing includes glass and native translucent work, so it is not an isolated SSR measurement. Compilation overhead, whole-frame p50/p95 and total driver VRAM still require an external profiler; byte budgets/status are not driver memory measurements.

Summarize with `python3 tools/benchmark_summary.py path/to/file.passes.csv` (CPU/GPU p50/p95 milliseconds). Capture status, FPS, resolution, render distance and GPU alongside exports. Compare:

- Fixed identical city/forest views: `shadow_cache off/on`, first fixed sun then `sun world`.
- Ocean and forest sunrise: `quality fast/balanced/high`, then `water_reflections off/on` and `volumetric off/on` independently.
- Native/local materials and foundation/off, including new-chunk flight. Effects off still uses the extended vertex format; comparison to actual vanilla needs a separate launch without VoxelLight.

Continuous-sun cache invalidation, animated/static cutout classification, HZB SSR, froxel visibility, sidecar vertices, compressed history, single-raster MRT and clustered lights are deliberately still pending. This patch supplies two CPU fixes and measurement, rather than pretending static review proves which GPU pass dominates. No GI added. GPU visuals/FPS must be checked in game; automated checks compile native shader contracts and verify startup transformations only.
