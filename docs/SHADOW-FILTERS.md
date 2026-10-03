# Surface shadow filter budgets — 0.28

The surface lighting PCF formerly used36 taps for an effective5×5-texel box. The new independent command `/voxellight shadow_filter fast|balanced|high` selects:

| Filter | Effective footprint | Taps per cascade |
| --- | --- | --- |
| fast | 1×1 | 4 |
| balanced (default) | 3×3 | 16 |
| high (previous reference) | 5×5 | 36 |

The extra row/column supplies continuous texel-phase interpolation. Weights normalize by the effective footprint area, not the number of samples, preserving0..1 visibility. Each tap still corrects receiver-plane depth and uses the existing0.0357-block bias. Terrain and dynamic depth blockers are unioned before weighted filtering; optional epoch maps use the same chosen footprint and current dynamic reprojection. Cascade/world-radius selection, boundary fade and caster admission are unchanged. High retains the previous footprint and weighting; lower budgets sharpen shadow edges and can show more coarse-map aliasing.

One cascade with dynamic casters samples both terrain and dynamic depth: balanced can use32 depth reads instead of72. Cascade overlap and opt-in epochs multiply this further. The command controls surface shadow filtering in foundation and legacy shadow/mask; volumetric2×2 PCF and water SSR budgets are unchanged. `quality` still selects only volume/reflection budgets. No new images/buffers, no enlarged world coverage and no shadow-map rebuild is required when switching; optional temporal history is invalidated. Status shows `shadowFilter` and `shadowFilterTaps`.

User reports temporal off is15 FPS faster; temporal and epochs remain off by default.191 previous tests plus3 filter tests pass: budgets, agreement with independently interpolated discrete box filters, and continuity at texel boundaries. Native Vulkan shader compilation/binding/UBO checks pass. The machine has no display/GPU; fewer shader samples are **not a measured FPS gain**.

## In-game acceptance

1. Use `mode foundation`, `temporal_shadows off`, `shadow_epochs off`; keep density0.001 and water strength0.09.
2. In the same building/forest view, compare `shadow_filter high` and `balanced`. Look at stairs, walls, low-sun shadows, leaves and slowly moving animals. Slightly sharper edges are expected; missing shadows, acne or unstable stripes are not accepted.
3. Slowly walk/rotate across cascade seams, inspect distant shadows and test block placement/removal. `fast` is an optional performance comparison, with more visible hard-edge/coarse-map aliasing.
4. Enable `profile on`, warm up and run20–30 seconds for each filter, then `export` before switching. Send FPS, resolution, render distance, status and `.passes.csv`. Compare lighting GPU timings; shadows/volume should retain their budgets. Export under `.minecraft/benchmark-results/voxellight`; `tools/benchmark_summary.py` summarizes per-pass p50/p95.
5. F3+T, resize and dimension changes must preserve valid shaders/resources. `high` remains available immediately if balanced looks worse; these command preferences are session-local.
