# Material 2.0 — 0.31.0

This is the first PBR milestone from the 0.30.7 review. Opaque terrain gains material profiles, GGX sun/moon and selected-lamp highlights, approximate sky specular, static LabPBR maps and rain wetness. Diffuse GI remains the 0.30.7 backend. It still refines new views; this release does not claim to remove async GI acquisition latency.

## Compare in game

Use native Vulkan and `/voxellight mode foundation`. PBR defaults on in `look polished`; rendering still defaults off at startup. Place stone, oak planks, iron, gold, copper, polished stone and ice together. Turn the camera through the reflected sun direction, then compare `/voxellight pbr off` and `on`. Metal blocks should have colored highlights and reduced diffuse shading; stone stays rough. Sky reflection is an analytic hemisphere, not a reflection of nearby buildings.

Use `/voxellight pbr_debug roughness`, `metal`, `normal`, then `off`. Roughness shows linear GGX alpha (dark is smooth); gold means conductor. Normal shows the shading normal rather than the original geometry normal. Entities and cutout/foliage retain their compatibility BRDF.

Set rain in a world where you have permission. Compare `/voxellight wetness off` and `on` on exposed upward stone, sand, planks and metal. Porous surfaces darken; less porous surfaces become glossy. Roofed/cave surfaces should remain dry. Wetness follows current rain immediately, with receiver skylight gating; drying history, puddles and rain ripples are not implemented.

For a static LabPBR pack, put `stone_n.png` and `stone_s.png` beside the corresponding `stone.png`. Enable the resource pack and reload with F3+T. Check `labPbrSprites` and `pbrProfiles` in `/voxellight status`, and use the normal/roughness views. Also test dimension changes, resize, native/local capture switches and opaque entities. Animated sprites deliberately use stable default profiles until map animation can be synchronized.

## Contract

Existing geometry normals, depth, material light channels and 36-byte terrain vertices keep their semantics. A fourth RGBA8 color target contains a 16-bit little-endian material ID in RG and oct-encoded world shading normal in BA. The separate shading normal influences BRDF only; shadow bias, AO and GI surface validation retain geometry normals. Entity capture writes a neutral ID and remains diffuse.

The 256×256 RGBA8 lookup contains smoothness, reflectance/metal identifier, porosity/SSS byte and material AO. Two atlas-aligned RGBA8 textures contain material IDs/emission and tangent normals. Atlas dimensions are capped at 2048 per axis; high-resolution packs lose map detail at that cap. Unique parameter tuples are deduplicated; excess tuples beyond 65,536 fall back to neutral material ID 0, with overflow reported. Atlas resources are rebuilt on world/resource reset and released with material capture.

Material targets now cost 20 B/pixel: 70.31 MiB at 1440p, 158.20 MiB at 4K, capped at 192 MiB. Atlas storage is at most 32.25 MiB. HDR lighting remains 8 B/pixel, with the combined 256 MiB admission cap. Native terrain vertex size and GI/shadow sample budgets do not grow. Terrain still uses an additional material raster pass; single-raster MRT is a later milestone.

Profiles are exact texture IDs in `assets/voxellight/pbr_materials.json`, for example:

```json
{
  "minecraft:block/iron_block": {
    "roughness": 0.35,
    "f0": 0.04,
    "metal": 230,
    "porosity": 0.0
  }
}
```

Here roughness is perceptual roughness; its square supplies GGX alpha. `metal` is 0 for dielectrics, 230–237 for LabPBR conductors or 255 for albedo-tinted generic metal. `f0` is linear dielectric reflectance. Unknown textures use roughness .85, F0 .04 and porosity .4. A pack's profile file replaces the bundled table; static `_s`/`_n` maps override profiles per texel.

[LabPBR specification](https://shaderlabs.org/wiki/LabPBR_Material_Standard): `_s.r` is smoothness, `_s.g` dielectric reflectance or conductor ID, `_s.b` porosity in 0–64; SSS values are preserved but not evaluated. `_s.a` 0–254 provides emission; 255 is ignored. `_n.rg` supplies DirectX tangent normal XY; Z is reconstructed, blue supplies ambient-only AO. UV/world derivatives supply the tangent basis before discard. Height, POM, SSS transmission, entity texture maps and animated material maps are not implemented. This is basic static-map support, not full LabPBR feature parity.

GGX uses Schlick Fresnel and Smith visibility with a minimum alpha .045 to bound narrow unstable highlights. Conductors suppress diffuse lighting; specular environment uses existing hemisphere colors and skylight. Selected lamps reuse their existing visibility query. Emission stays outside shadow and AO modulation. The GI composite suppresses diffuse GI on conductors and applies dielectric F0 energy retention; worker history/sample policies stay unchanged. Secondary voxel hits still use the existing approximate diffuse proxy. Specular GI/reflection tracing is future work. Material debug views bypass GI addition. Water retains its existing separate HDR shader.

## Measurement and remaining work

For the same stationary scene, collect `/voxellight profile on`, PBR off/on intervals and `/voxellight export`. Compare material and lighting pass p50/p95, plus `materialTargetBytes`, `pbrAtlasBytes` and `pbrAtlasBuildNs`. Reload construction is a setup cost, not recurring frame work. No GPU measurements are available on the build host, so no FPS gain is claimed.

Next: accept the material contract and appearance, then investigate single-raster native MRT. Sky/cloud shadows, shared temporal/motion infrastructure, underwater caustics, solid-surface SSR/RT reflections and RTX zero-copy/AS remain separate future milestones. Do not expand GI samples, local-light count or shadow epochs for this material release.
