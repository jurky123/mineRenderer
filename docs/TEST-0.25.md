# Three-phase bundle: one in-game acceptance — 0.25

The user asked to complete three phases before the next test. All three are implemented in one release: **water scene reflections**, **volumetric spatial filtering**, and **animated normal waves with fixed quality controls**.0.24 is confirmed working; the new bundle still needs native Vulkan visual/performance testing.

Replace the previous VoxelLight JAR, retain Fabric API, select native Vulkan, then enter a world and run:

```text
/voxellight mode foundation
/voxellight quality balanced
/voxellight atmosphere_density 0.001
```

Density remains the preferred0.001. Reflections, normal waves and volume filtering default on. Quality controls are fixed sample counts; they do not alter render distance, geometry residency, lights, exposure or density.

| Preset | Air samples | Water reflection samples | Spatial filter |
| --- | --- | --- | --- |
| fast | 8 | 16 | On unless explicitly disabled |
| balanced (default) | 16 | 24 | On unless explicitly disabled |
| high | 32 | 32 | On unless explicitly disabled |

## 1. Reflections

Use a pond/river next to a tree or building that is currently visible. Compare `/voxellight water_reflections off` and `on`. Look for the object reflected in the water rather than only sky color. Walk/turn slowly, including grazing angles and screen edges. Missing/offscreen/unsupported scene data should fade to sky reflection without black holes; the trace cannot reflect unseen geometry. Check shoreline seams and reflection detachment. Try waves off during this check to separate trace stability from normal motion.

## 2. Volume filtering

Look through trees or a window toward low sun, with a visible opaque background. Compare `/voxellight volumetric_filter off` and `on`. Check reduced grain, retained shaft shapes and foreground fence/leaf/animal edges. Move slowly and then teleport. Spatial filtering cannot promise shimmer-free motion; there is no volume/SSR history and no intended trail behind moving objects. For a stronger comparison density0.003 is optional; restore0.001 afterward.

## 3. Water animation / sampling cost

Stand still by water. Compare `/voxellight water_waves off` and `on`; reflection, refraction and highlights should ripple. Vertices and shoreline remain flat. `water_wave_speed 0` freezes current phase;1 resumes. Default strength is0.12; try0.2 only to magnify motion. Cross camera coordinates±64/±128 to check spatial seams.

Compare quality fast/balanced/high at an identical viewpoint, resolution and density. Record FPS or frame times; neither memory savings nor fixed sample bounds proves measured performance. Default filtering uses about3.52MiB extra quarter image memory at1440p, water reflections reuse existing HDR/depth buffers. Restore balanced and waves/reflections/filter on after comparison.

## Lifecycle checks / useful feedback

Check F3+T, resize, shoreline/underwater, Overworld/Nether, block edits, moving entities, world exit/rejoin and teleport. Unsupported/Fabulous/underwater water remains native as before. Controls survive renderer resource close/reload but reset on a fresh game launch.

If a difference is absent, capture `/voxellight status` and the preset/controls in use. Useful feedback is matching on/off images of one shoreline and one low-sun shaft scene, any flicker/seams/halo/missing reflection location, and FPS/frame cost for balanced versus fast/high. No GPU/display is available in the build environment; automated compilation/binding/math/lifecycle checks do not substitute for these observations.

See[water details](WATER.md) and[volumetric details](VOLUMETRIC.md). After this acceptance, decide whether reflection/volume refinement is still necessary or whether to start the next major visual system.
