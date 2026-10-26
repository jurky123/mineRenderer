# Minecraft 26.2 integration prototype

This independent module uses the public v0.1.0 Java facade, JNI ABI and
OpenGL/Remix image-sharing implementation. The original 1.21.1 source and
release remain intact. It is an integration experiment, not a feature-complete
port or a performance-qualified renderer.

Implemented entry points:

- Minecraft 26.2 / Java 25 / Fabric Loader 0.19.5 client initialization.
- OpenGL context version selection through `GlBackend.setWindowHints`.
- CPU mesh snapshots at `SectionCompiler.compile` return, before vanilla frees
  its buffers; bounded queues cross to the render thread.
- Native mesh and retained instance lifetime, camera, directional light.
- Unchanged upstream Win32 memory/timeline-semaphore interop.
- World-color composition into the 26.2 render target before its UI pass.
- Final surface presentation through `GlSurface.present` instead of the removed
  `RenderSystem.flipFrame` method.
- Unmodified official `RemixInputGate` policy with 26.2 keyboard, character,
  preedit, mouse, held-key release and cursor ownership adapters. Alt+X toggles
  the developer overlay; native close controls release Minecraft input.
- Scene/GL cleanup before Minecraft closes; native Runtime shutdown after
  Minecraft has destroyed its GLFW window, matching upstream's lifetime contract.
- Simple and translucent feature phases both preserve entity ownership. Glass
  held by the local third-person shadow model retains player categories rather
  than appearing as an unowned world object. First-person glass keeps refraction.
- Opaque textures in cutout terrain layers are promoted to solid. Coextensive
  overlays use the native decal category and blend state. Static vanilla grass
  side/overlay pairs with uniform tint are composited into one opaque face,
  including normal/height/roughness/metal channels; the bounded worker cache is
  cleared with native texture resources. Animated/emissive/SSS variants and
  nonuniform tints keep the general decal path.
- Options → Remix rendering adds explicit lighting sample density (native default,
  1/16, 1/8, 1/4, 1/2, 1). Manual control uses the native Custom-preset migration
  to preserve other quality choices, then disables resolution adaptation for a
  literal per-internal-pixel density. Restoring the native default restores the
  prior preset and sampling options. The current Runtime does not expose 2/4 SPP.
  The same page is available through `/rtx sampling`.
- Official LabPBR conversion adapted to 26.2's ARGB NativeImage API: normals,
  height/POM, roughness, metallic, emission and subsurface channels. All 150
  shipped `assets/` files are included byte-for-byte from upstream.
- Missing PBR channels on vanilla blocks use separate metal, oxidation, wood,
  stone, polished/glazed, fiber and vegetation defaults. Authored maps replace
  these per-channel defaults. Item-specific and entity textures do not inherit block guesses.
- Slime and honey use separate tinted refractive materials with a diffuse
  surface layer rather than ordinary alpha-blended opaque materials.
- LabPBR channels preserve their own resolution instead of wrapping or cropping
  into albedo dimensions. Static maps can accompany animated albedo; emission
  combines the appropriate albedo frame with its mask. Unused animation tiles
  in older maps are allowed. Animation timing follows the albedo; independent
  PBR-side `.mcmeta` timelines and legacy/Bedrock MER formats are not interpreted.
- Per-sprite terrain materials, cutout/blend categories, separate glass,
  stained glass and water transmission. Terrain biome tint is captured before
  vanilla AO/cardinal lighting, leaving the original raster output unchanged.
- Animation indices, timing, repeated frames and interpolation follow vanilla's
  actual AnimationState, including patch forwarding across PBR channels.
- Entity, block-entity, item and text feature vertices are captured from vanilla's
  existing feature preparation; models/animations are evaluated only once.
- Resource/dynamic textures and on-demand GPU-only texture capture (including
  R8 glyph coverage); dynamic/glyph uploads invalidate CPU snapshots.
- Resource reload removes scene references before texture handles; worker
  generations reject snapshots from an earlier world/resource session.
- Water uses the official 120-frame normal flipbook, with continuous ten-metre
  world-space UVs across terrain sections and world features. Surface transmission
  is a subdued cyan-green, preserving shallow bottom colors while attenuating
  with distance; the camera medium remains separate
  from the animated surface. These are normal-map waves, not displaced geometry.
  Surface transmission now includes neutral density: RGB (.64,.752,.744) at
  two metres. This preserves the prior channel ratios while reducing overly clear
  deep water. It adds no compositing pass; independent pool-volume scattering
  and caustic focusing are still outside this implementation.
- Official Rayleigh/Mie compute shader and asynchronous atmosphere readback,
  sun/moon direct-light extinction and 30-degree orbit tilt. The sky adapts to
  26.2's extracted environment angles, rain and thunder; skyless dimensions
  publish a black dome and the End a constant violet dome.
- Official additive sun/moon sky quads with 26.2's separate moon-phase textures,
  and the canonical vanilla star vertices captured before their CPU buffer closes.
- Default authored analytic light rules for torches, campfires, candles,
  amethyst, conduit and invisible light blocks. Discovery is CPU-only and shares
  the section generation/lifetime. Ordinary held/dropped luminous block items
  use persistent light identities and the official first-person collision limiter.
- Calibrated hybrid lighting for glowstone, lantern/soul lantern, sea lantern,
  shroomlight, three froglights and powered redstone lamps. Outward rectangular
  panels avoid self-shadowing; opaque neighbors cull hidden panels. Lamp surfaces
  use intensity 35 instead of 100 and preserve authored emission masks. Panel hue
  follows loaded emission pixels. Held lamps use an equivalent-area sphere.
  Each placed lamp adds up to six analytic lights; this is appearance calibration,
  not mesh-only energy equivalence or a performance improvement claim. Vanilla
  lamp geometry is assumed; custom model shapes may need different light rules.
- SPBR 22.5 was validated against 1355 block sprites (1262 authored specular maps),
  live channel uploads and resource reload. The unmodified pack is installed
  separately in the client; its GPL assets are not bundled in this Apache mod.
- World composition runs inside the frame graph after the main pass, before
  clouds, weather, always-on-top features, hands and screen effects. Imported
  primary depth is flipped/upscaled and converted to the actual reversed 26.2
  projection; weather targets receive the converted depth too.
- Official owner/part retention and closest-K selection policies. Cuboid model
  parts share immutable native meshes, update transforms only when changed, and
  retire unseen instances. A bounded offscreen sweep supplies reflection/shadow
  geometry within 112 blocks. Owner identity is the entity object, not its numeric ID.

- Official authored conf-layer driver: day/night transitions, rain/thunder,
  dimension, biome, underwater medium, view distance, volumetric settling and
  named zones. World-authored profiles override the shipped mod catalog. Zone
  definitions use the official schema and 26.2 SavedData persistence; passive
  command-block markers and client authoring commands share the same registry.
- Native first-person hands/items use vanilla's actual hand-render method and
  extracted features, official FOV correction and VIEW_MODEL categorization.
  Local-player geometry remains available for reflections/shadows, with the
  official collision/spring placement and third-person body categories.
- Arm retention ignores temporary `Model.Simple` wrapper identity. First-person
  baked items retain model-space meshes in separate left/right slots and update
  poses, preserving native motion history while walking, bobbing and turning.
- Physical quad particles consume 26.2's extracted particle states, including
  rotation, partial sprite UVs, color/alpha and terrain alpha testing. Custom
  non-quad groups keep their vanilla path.
- Physical voxel and procedural clouds reuse the official geometry and placement
  generators. A bounded CPU worker prepares geometry; only the render thread
  creates native assets. Vanilla clouds are replaced once physical meshes exist.
- Native graphics settings are accessible with `/rtx graphics`, including DLSS,
  Ray Reconstruction and Frame Generation. Existing native `user.conf` choices
  are preserved; the adapter no longer forces Frame Generation off at startup.
- The native FPS HUD counts presented frames, including generated frames.
  Before Runtime startup the adapter adds `dxvk.hud = fps` to `dxvk.conf` only
  when no explicit HUD choice exists. Set `dxvk.hud = 0` and restart to hide it.
  Minecraft's own FPS counter continues to count rendered game frames.

Parity is still pending: specialized rain/snow, resource-pack light-rule overrides,
texture-derived fallback light colors, official custom blocks, decal and held
flame/smoke specialization, complete section revision handling
and offscreen block-entity extraction. Lines/strips, blob shadows and glint
are not exported as physical feature geometry. Vanilla world rendering still
runs. Non-cuboid world features, world items, text and unsupported block-entity models still
use the frame-batch fallback; those batches replace their native meshes each
frame. Quad particles also rebuild active batches instead of using the original
persistent particle-slot registry. GPU-only textures need a synchronous readback
on first use/change. These
are known costs and feature gaps, not performance-qualified implementations.
Do not compare this module's frame time to the complete official renderer yet.

The pinned opaque API has no authored dielectric F0 input; LabPBR metal codes
are represented through metalness/albedo rather than separate conductor optical
constants. The translucent API has no independent roughness input. These limits
remain even when a resource pack supplies those values.

Local functional validation produced a shared 1048x672 color image, textured
terrain, native glass refraction, a textured cow and a chest behind glass. F3+T
resource reload restored the scene without stale native references. Alt+X,
blocking movement while the native menu owns input, native-menu close, Alt+F4
and the window close button were exercised in a disposable validation world.
See the external phase-2 report for exact artifact identities and the additional
glyph/dynamic-texture check; compilation alone is not runtime acceptance.
The phase-4 cold-start validation additionally exercised profile overrides and
zone persistence, held-item/hand placement, physical particles and both cloud
modes, underwater/night profiles, resource reload, native graphics controls and
persisted Frame Generation enablement. Effective enablement and loaded NGX
libraries do not establish an interpolation multiplier or a performance result.

## Build

The 26.2 client is already named. Compile against the matching installed
Minecraft/Loader/LWJGL classpath; annotation processing is not needed for named
Mixin targets.

```powershell
python build.py --java-home <JDK25> --classpath-file <classpath.txt> --fabric-api <fabric-api-0.160.0+26.2.jar>
python check.py --java-home <JDK25> --classpath-file build/compile-classpath.txt
```

The command writes `build/rtx-remix-26.2-prototype.1.jar` and a source/hash
receipt. The stable filename is retained for installer/rollback compatibility;
the manifest version is `0.1.0-port26.2-prototype.9`. It does not download,
build native components, stage a game or launch
anything. The root Gradle project still targets 1.21.1 and is not this module's
build entry point.

See [current validation](docs/CURRENT-VALIDATION.md) for the installed artifact
identity, checked behavior and remaining limitations. The [demo data pack](demo/README.md)
recreates the demonstration layout in a new disposable creative world.

The supplied Fabric API's nested modules are compile-only dependencies for the
optional Indigo capture, client commands and chunk lifecycle hooks; neither
Fabric API nor Indigo is bundled. The vanilla capture decorates
`ModelBlockRenderer`'s output argument rather than redirecting `SectionCompiler`'s
call, which Fabric API owns. Indigo capture observes surviving quads before AO,
preserves each vertex's authored color, and applies the same cached biome tint as
Indigo without modifying the raster quad. The optional mixin targets the Indigo
implementation shipped with Fabric API 0.160.0+26.2.

## Isolated runtime

Use a new Fabric 26.2 game directory with only this prototype JAR. Select the
OpenGL backend, use the verified v0.1.0 win64 Runtime and adjacent JNI bridge,
and copy the release's `rtx.conf` into that game directory. The adapter loads
`remix-sdk/` beneath the game directory by default. For an external diagnostic
Runtime, override it with:

```text
-Dremix.port.sdk=<verified extracted remix-sdk directory>
```

Copy the official `textures/water_normals_flipbook.dds` and
`textures/overcast_thunderstorm_bc6u.dds` payloads too. The water flipbook is
required for water material creation. Without the weather texture the adapter
keeps the atmospheric dome and does not claim an overcast composite.
Native ABI, JNI sources and Runtime stay unchanged.
The pinned Runtime has one camera-dependent homogeneous volumetric medium.
Water seen from above therefore has surface transmission but no separate lit
water volume. This module does not implement water caustic focusing; animated
surface normals alone do not create sunlight caustics on the bottom.
Copy the official `rtx-remix/mods/minecraft-logic/confs/` payload from the release
as well; the scene driver discovers these files rather than embedding defaults
in Java. This module does not generate a substitute catalog if it is absent.

## Scene and graphics controls

With Fabric API installed, `/rtx status` reports the active scene driver and
`/rtx conf reload` reloads profiles. Singleplayer world overrides belong under
`saves/<world>/rtx-remix/confs/`, using the same paths as the shipped catalog
(for example `zones/cave.conf` or `biomes/plains.conf`).
`/rtx zone define cave --center 19 100 -3 --inner 5 --outer 15` defines a zone;
`/rtx zone remove cave` removes it and `/rtx zone rescan` rediscovers passive
command-block markers. 26.2 saves the official schema through its dimension
SavedData system; this adapter does not import legacy 1.21 zone-file paths.

`/rtx clouds vanilla`, `/rtx clouds procedural` and `/rtx clouds off` choose the
cloud mode. Size, height and smoothness are available as `/rtx clouds <setting>
<value>`. These Minecraft-side choices persist in `config/remix-port.json`;
native DLSS/RR/Frame Generation choices remain in the Runtime's `user.conf`.
Use the native graphics menu's Save Settings control to persist those choices.
On the tested RTX 4060 Laptop the official Frame Generation choice is off or 2x;
higher multipliers require supported Multi Frame Generation hardware. The native
menu owns capability checks; a requested `maxInterpolatedFrames` value is not a
measurement of the active multiplier. The FPS HUD uses the Runtime's present
count instead of multiplying Minecraft's FPS by a configuration value.

The DLLs remain external; this small prototype JAR is not a standalone
redistribution. It must not be installed alongside the original Remix Mod or
VoxelLight. For the local validation only, `remix.port.proofDirectory` requests
one native scene/interop receipt and render-target screenshot after frame 240.
It is not a user graphics setting.

The code expects a current NVIDIA driver, the required external-memory/GL
semaphore extensions, and a modern Microsoft C++ runtime. Runtime initialization
must be verified separately from Java compilation. Do not modify the copied
upstream C API header or JNI layouts to work around a Runtime mismatch.
