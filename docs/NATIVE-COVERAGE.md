# 0.22 native terrain material coverage

## Delivered path

Foundation/material diagnostics default to native visible geometry. Minecraft still compiles and renders its terrain normally. A SectionCompiler output wrapper decorates each emitted block-model quad with real geometry normal, unlit tint, independent block/model emission and flags. It does not enumerate blocks or tessellate a second model. Native per-vertex shaded color, positions, atlas UVs and light coordinates are preserved.

The native BLOCK vertex format keeps its original first28 bytes and appends UV1 metadata and a four-byte packed normal/validity marker: stride36, extra8 bytes/vertex. Tint RGB and quad emission/flags occupy UV1; normalXYZ and block emission/validity occupy Normal. BufferBuilder's pinned fast path is adapted to fill the extended fields. Raw/fluid emitters are zero-initialized, keep valid native vertices, and are excluded from material capture when no compiler metadata exists. A nested thread-local scope is cleared in finally; simultaneous compilation threads cannot borrow each other's materials.

After opaque native terrain, the material MRT pass borrows ChunkSectionsToRender's solid/cutout draw lists, native uber-buffer references, indices, per-draw offsets and ChunkSection uniforms. It draws the same visible geometry to the existing material targets. References are scoped to that call and released in finally; VoxelLight does not own or close native buffers. No native material mesh store, new section cache, material vertex upload or independent one-build/frame admission remains on this path. MaterialCapture still owns the existing MRT targets and descriptors.

This is native geometry reuse plus an extra raster pass, not single-raster native MRT. Minecraft itself still rebuilds native terrain for light changes. The old local material path remains available with `/voxellight native_material off`; `on` restores the native path, default on each launch.

## Coverage and independent limits

Supported visible solid/cutout terrain supplies materials regardless of its membership in the bounded shadow/local-light scene bridge. Full lighting/display disables the global24–32-block fade for this source. Native chunk visibility is retained in the material normal alpha and used during final composition. Real normals, unlit albedo and current native depth matching remain required; unsupported pixels stay native.

Water uses the same source-mode coverage control and can shade farther surfaces when a valid captured opaque HDR background exists. It retains background-depth validation and native fallback for sky/unsupported backgrounds, separate Fabulous targets and underwater cameras. No geometry waves or SSR are added.

Directional shadow receivers still stop at48 blocks; colored shadowed local sources still fade16–24 blocks. Distant surfaces receive material-based sky/direct/emission/native block-light baseline without far directional visibility. Analytic atmosphere still caps its integrated path at48 blocks. These limits must be improved independently; they do not switch all distant material color back to vanilla.

## Costs and compatibility

The8-byte expansion is global for native BLOCK buffers, including effects-off/local-reference modes and BLOCK-format shadow meshes. It adds28.6% vertex storage relative to the original28-byte format; it is not removed by the source toggle. Existing material target/pixel budgets remain unchanged. A larger visible material raster increases GPU coverage; no measured FPS improvement is claimed.

This contract is for the pinned26.2 native terrain renderer. Renderer mods that replace the compiler or assume a hardcoded28-byte stride have not been validated. Supported native compiler geometry is the boundary; arbitrary raw mod vertices are not promoted to materials merely because they share a buffer.

## Validation

- Shader/SPIR-V and actual native pipeline binding tests cover native capture with all vertex attributes live, the three MRT outputs and revised composition bindings.
- Attribute tests cover original offsets, packed tint/flags/emission including signed-short values, slope normals and nested/thread isolation.
- Packaged mixin selectors/shadow fields are checked against the pinned game bytecode. The first native format-builder hook is separately tied to BLOCK initialization.
- Development client startup executes a transformed-writer check: expected inline bytes, geometry normal/emission marker, raw-emitter zero marker, and compiler class transformation. It logged `Native terrain material writer verified: 36-byte stride, shared visible geometry`, then stopped at GLFW because DISPLAY is absent. This is startup/CPU verification, not a GPU rendering acceptance.

In-game acceptance is pending: compare `native_material on/off`, `material_coverage`, `surface_normal`, `albedo`, `emission` and foundation at16/24/32 chunks. Far supported terrain should be green coverage without the camera-centered32-block style boundary. Check foliage, slopes, biome tint, emissive blocks, water, rapid flight, torch/block edits, F3+T, resize, teleport and dimensions. Record frame-time and native memory/draw counters; no benchmark result exists yet.

Next: accept this coverage proof, then extend light-aware directional caster/cascade range and cache scheduling under independent quality budgets. Single-raster MRT may follow once material attributes and native lifecycle are proven.
