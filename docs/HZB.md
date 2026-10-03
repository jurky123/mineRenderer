# HZB water reflections — 0.29

This implements the depth-pyramid/SSR item from the performance review. `/voxellight water_hzb on` selects a hierarchical reflection trace; `off` restores the previous linear trace. It is opt-in until in-game quality and total cost are measured. Temporal shadows and celestial epochs remain off, surface shadow filter stays balanced, density0.001 and water strength0.09 are retained.

## Representation and traversal

A current-frame half-resolutionR32_FLOAT texture stores the maximum reversed-Z depth of each2×2 pixel group. Native mip levels successively max-reduce it. When a source dimension is odd, its unmatched last row/column is merged into the final parent cell, including one-pixel dimensions. No geometry, CPU readback or additional full-resolution HDR image is needed. Memory is capped at16 MiB:1440p4.69 MiB;4K10.55 MiB. Water's existing96 MiB background cap remains separate. The shared water settings block grows64→80 bytes.

Each reduction reads a single-mip view and writes a disjoint single-mip view. Minecraft26.2 Vulkan usesGENERAL layouts for both and records a memory barrier when a render pass ends; the full-chain view is sampled only after all reductions complete. No read/write feedback occurs on the same mip.

Reflection traversal starts with cells of up to64×64 full-resolution pixels. Perspective clip-space equations solve the next screen-cell boundary. A cell is skipped only if the minimum ray depth over that segment is greater than the cell's maximum surface depth (reversed-Z), or the cell is empty. Potential intersections descend to the original full-resolution depth. The exact pixel-depth plane is then intersected analytically and validated against the existing supported HDR/radial guide. Screen borders, unsupported hits, camera clipping, trace-budget exhaustion and misses fall back to sky; no RGB history is introduced.

HZB visit budgets are64/96/128 for fast/balanced/high. These are traversal visits, not the former16/24/32 linear world-space steps. A visit includes a depth fetch; descent/cell skipping avoids full position reconstruction until a candidate hit. There is no guaranteed reduction in total GPU work: pyramid construction, additional passes and divergent traversal can outweigh saved reflection work, especially when little water is visible. HZB is still screen-space and cannot reflect offscreen objects. Thin geometry or exhausted traversal can miss reflections; compare reference quality.

## Ownership and profiling

The pyramid is owned by WaterPass and built after its immutable pre-translucent depth copy, before native water submission. Turning off HZB, reflections or water releases it. Resize/world/resource lifecycle also releases targets; the chosen HZB option survives close/reload. Compilation/allocation/reduction failure retains the linear path and suppresses retries until reset. No pyramid is built while the option is off. Eligible frames with HZB/reflections enabled build it even if little water is actually visible.

`status` reports `waterHzb`, `waterHzbBytes` and the active reflection trace/visit budget. Per-pass profiling adds `water_hzb` for the complete pyramid build. Existing native translucent timing includes water and other translucent objects. Compare both the added pyramid cost and translucent cost, alongside FPS; per-pass samples are not whole-frame percentiles.

199 tests pass, including actual native-water and pyramid GLSL→SPIR-V binding/stage contracts,80-byte water UBO reflection, conservative odd-size reductions with every single-pixel occluder, perspective boundary math, byte caps, mip render areas and option persistence. No GPU/display is available here: image correctness and measured gains require in-game acceptance.

## In-game comparison

Use `mode foundation`, `water_reflections on`, temporal/epochs off. Keep the same camera, quality, resolution and render distance. Near a shoreline with buildings/trees:

1. Compare `water_hzb off` and `on`, including grazing angles and slow camera motion. Check reflected trees, narrow pillars, nearby walls, distant terrain and screen borders. Reflection hits can differ because hierarchical traversal replaces sparse linear samples.
2. Check `status`: on should report active, nonzero bytes and hierarchical visits; off should show zero pyramid bytes and linear steps. A shader/device failure should retain linear water.
3. With `profile on`, warm up and run20–30 seconds, then `export` each comparison. Include FPS, status and `.passes.csv`; compare `water_hzb` plus native translucent GPU timings. Include an ocean view and a view with little water to expose build overhead.
4. Resize to an odd resolution, F3+T, teleport, enter another dimension and go underwater. Supported water should recover without stale reflections or undefined edge pixels. Fabulous/underwater paths retain existing native fallback.

No HZB-based culling, contact shadows, froxel volume, single-raster material capture or RTX/OptiX implementation is claimed by this release. The pyramid is the initial infrastructure, currently consumed only by water SSR.
