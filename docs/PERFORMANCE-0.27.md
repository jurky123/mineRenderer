# 0.27 packed material and performance acceptance

Material albedo, normal and emission/light use three RGBA8_UNORM targets plus private D32:16 bytes/pixel instead of24. XYZ normal is encoded as `n*0.5+0.5`, decoded as `encoded*2-1`, then normalized. Alpha is the previous validity/native-fade value divided by3; consumers multiply by3 before interpretation. Clear alpha0 stays invalid; reference/entity1 stays supported; native2..3 retains chunk visibility. Decoding covers lighting, AO, temporal opt-in, bloom rejection, native fog/output and material diagnostics. Vertex formats and native geometry ownership remain unchanged.

Emission and light channels store0..1 strength/access, not HDR radiance. All16 source emission levels are exactly representable in8-bit UNORM; interpolated lighting quantization is bounded by1/510. Normal tests cover signed axes and5000 normalized directions within0.5 degrees; native fade has85 intervals. HDR lighting, atmosphere, bloom and temporal history formats are unchanged.

At2560×1440 material targets drop84.375→56.25 MiB (28.125 MiB saved). Temporal shadow is now off by default: its four full-resolution targets are not allocated and its resolve pass/lighting MRT are omitted; optional history remains32B/pixel,112.5 MiB at1440p. Epochs also stay off by default. These are resource-accounting savings, **not measured FPS improvements**. Duplicate material raster, PCF, local DDA, volumetric and SSR still need profiling. No OptiX/CUDA code or new native library added.

## In-game checks

1. Replace the old mod with0.27.0. Use `mode surface_normal`: inspect all six block faces, stairs, leaves and opaque entities. Normals must retain their directions, not become all-positive or dark.
2. `mode material_coverage`: supported terrain/entities remain green. `mode emission`: glowstone/torches/lava still register; nearby non-emissive blocks stay dark.
3. `mode foundation`: check foliage, animals, hand-held light, a cave torch, sun/moon shadows and distant chunk visibility fade. Compare with0.26.1 for unexpected banding, shadow bias or coverage changes; a major appearance change is not intended.
4. Place/remove torches and blocks; F3+T, resize, teleport and change dimensions. The packed outputs must remain valid in native and local reference capture (`native_material off/on`).
5. `status` should show `temporal=off`, `temporalHistoryBytes=0`; `materialTargetBytes=width*height*16`. Enable `temporal_shadows on` to check restored reprojection/normal rejection, then return it off.

## Measurements to choose the next optimization

Use the same scene/camera/resolution/render distance, keep density0.001, water strength0.09 and quality fixed. Enable `profile on`, warm up, run20–30 seconds and `export` each run before mode/reset. Export lives under `.minecraft/benchmark-results/voxellight`; send `.passes.csv`, status and FPS. Summarize with `python3 tools/benchmark_summary.py <file.passes.csv>`.

Compare temporal off/on first; then epochs off/on, water_reflections off/on, volumetric off/on. Compare material capture between0.26.1 and0.27.0 with temporal/epochs disabled in both. `native_material off` is a limited-range local reference, not an equal-coverage performance benchmark. Pass timings are delayed nonblocking measurements; exported pass percentiles are not whole-frame percentiles, and native translucent timing includes other translucent work. The current environment has no display/GPU, so in-game measurements require the user.
