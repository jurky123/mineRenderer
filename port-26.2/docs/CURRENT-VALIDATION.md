# Prototype.9 validation

The last in-game validation and client installation completed on 2026-10-25.
CPU module checks were repeated successfully before publication on 2026-10-26.
Publication changes documentation and includes the existing demo data pack;
production Java sources and mod resources are unchanged from the tested artifact.

## Identity and requirements

- Manifest: `0.1.0-port26.2-prototype.9`.
- Installed JAR SHA-256:
  `35061c7541c297c90679fd90f9c8292bbf9033c361186ca29a4370768950c137`.
- Fixed upstream base: `170197a63d233f8462e921d231cf5124feb08ffe`.
- Minecraft 26.2, Java 25, Fabric Loader 0.19.5; validated with Fabric API
  0.160.0+26.2 and the official v0.1.0 win64 native Runtime/adjacent JNI bridge.
- 149 compiled Java sources, 247 production classes, 9 CPU check groups.
- 45 official facade sources and 150 official asset files retain upstream bytes.
- Native ABI, C API header and JNI are unchanged. The small prototype JAR does
  not bundle the native Runtime. See the module README for staging requirements.

The build writes a fresh source/hash receipt. ZIP metadata can change the JAR
hash on a rebuild; the above hash identifies the installed and validated binary.
The accompanying source-identity JSON maps every production input to its SHA-256
both before and after Git line-ending normalization (`sources` and
`repositorySources`, respectively).

## Held glass and terrain surfaces

The transparent feature phase has its own typed submit entry point. Binding
ownership only in the simple phase left local-player transparent third-person
items classified as ordinary world geometry. First-person glass separately had
the correct view-model category, producing an apparent extra model.
`TranslucentFeatureSubmitMixin` now binds the same owner before capture.

In-game inspection confirmed local-player ownership for the third-person hand,
the separate first-person view model, and primary-space player-model exclusion.
Before the fix, 180 observations over roughly 1400 frames showed one stable
first-person glass mesh/instance; repeated mesh creation was not the cause.
Glass keeps its physical IOR/transmission. Temporary simplified hand-glass
experiments were withdrawn. This does not establish that every RR/FG artifact
is eliminated.

Opaque sprites in cutout layers are promoted to solid. Exact coextensive overlay
faces receive decal handling. Static vanilla grass side/overlay pairs with
uniform tint are composited into one closed opaque face, including PBR channels.
Animated, nonuniform, emissive or subsurface variants retain the generic decal
path. CPU checks cover masks, tint composition, unrelated/backfacing foliage and
opaque promotion; the live grass composite was confirmed opaque and solid.

## Sampling and live checks

Options -> Remix rendering and `/rtx sampling` expose native default, 1/16,
1/8, 1/4, 1/2 and 1 lighting sample density at the internal render resolution.
This Runtime does not expose 2/4 SPP. Manual selection transitions to the native
Custom preset before setting the requested density; otherwise the Quality layer
masks the write. Restoring default restores the saved preset and sampling flags.

Live checks confirmed 0.5 and 1.0 resolved density, adaptation disabled in manual
mode, sparse sampling disabled at 1.0, and successful default restoration.
DLSS, Ray Reconstruction, Frame Generation and path limits stayed unchanged.

The final candidate passed cold start, resource reload, the Options-screen entry,
sampling changes/restoration, transparent-owner inspection and normal exit in an
isolated client. The new creative demo save was sanitized of test-player data
before installation. Its original data pack is included under `demo/`.

SPBR 22.5 is an unchanged separate GPL resource pack. The Apache mod does not
bundle it. Water, lamp calibration, native payload and authored scene profiles
retain the preceding validated implementation. No performance improvement or
official-renderer performance parity is claimed for this release.
