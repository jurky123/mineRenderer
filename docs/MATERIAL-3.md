# Material 3.0 — VoxelLight 0.37

Material data selects a scattering model. The native BSDF owns evaluation, sampling and probability density; the integrator owns light transport. Raster primary visibility, single-raster MRT, incremental OptiX GAS/IAS and the existing Performance fallback remain in place.

## Contract and units

`world/Material3.java` authors the atlas table; `native/rt/bsdf.h` is the actual CUDA/CPU BSDF implementation. The ten classes, in ABI order, are diffuse, rough diffuse, conductor, dielectric, thin dielectric, coated diffuse, coated conductor, diffuse transmission, emissive and water. Base color is **linear reflectance**, not an illuminated color. Every traced terrain hit reads the real atlas texel, tint, tangent normal and the same material table used by raster lighting.

JSON `roughness` and `v_roughness` are **perceptual roughness**. The microfacet distribution uses `microfacetAlpha = perceptualRoughness²`; anisotropic V uses its own squared value. The small minimum alpha of 0.0005 prevents singular numerical arithmetic. This floor is shared by raster and RT. Coating roughness has its own alpha and never overwrites the base roughness.

The packed table has five RGBA8 planes, each 65,536 entries:

| Plane | Contents |
| --- | --- |
| 0 | LabPBR smoothness, linear F0/conductor selector, porosity/SSS, material AO |
| 1 | Material class, IOR scaled by 3, coat weight, coat alpha |
| 2 | RGB absorption scaled by 8 inverse blocks, HG g encoded from −0.9 to +0.9 |
| 3 | RGB scattering in inverse blocks, transmission weight |
| 4 | Coat IOR scaled by 3, V microfacet alpha, reserved |

Atlas IDs contain the 16-bit table index, emission, and surface/override flags. Material table size is 1.25 MiB; no new full-resolution GBuffer attachment or terrain raster is introduced. Low-range absorption quantization and IOR quantization are limitations of this compact reference contract. Underwater camera initialization decodes **these same quantized values**, so it agrees with traced water interfaces.

## LabPBR 1.3

The [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard) is authoritative for supplied channels:

| Channel | Decode |
| --- | --- |
| `_s.R` | Perceptual smoothness s; perceptual roughness 1−s; microfacet alpha (1−s)² |
| `_s.G` | 0–229: linear F0 byte/255; 230–237: published RGB eta/k; 255: linear base color as F0 |
| `_s.B` | 0–64: porosity byte/64; 65–255: SSS (byte−65)/190 |
| `_s.A` | 0–254: emission byte/254; 255: no emission |
| `_n.RG` | DirectX tangent normal XY; reconstruct Z; raster and RT use UV derivative/triangle tangent bases |
| `_n.B` | Linear material AO |
| `_n.A` | Height retained for future POM; no displacement/POM is enabled |

Unassigned metal selectors 238–254 use the standard's albedo-F0 fallback, rather than invented eta/k or a diffuse material. An explicit nonmetal LabPBR map demotes a curated metal model. Supplied emission also overrides native emission, including an authored zero. Hardcoded metals use the published iron/gold/aluminum/chrome/copper/lead/platinum/silver eta/k constants, with the texture reflection tint prescribed by LabPBR. No extra yellow/gold boost is applied.

Static companion maps are supported. Animated normal/specular companion maps still require synchronization with Mojang's animated sprite frame; they retain stable presets. AO/height are decoded and preserved, but AO is an ambient raster factor, not an extra physical attenuation of traced radiance. SSS currently selects the thin diffuse-transmission approximation rather than a subsurface volume.

## Models

- Diffuse: Lambert. Rough diffuse: Oren–Nayar base plus dielectric microfacet reflection and Fresnel attenuation.
- Conductor: anisotropic Trowbridge–Reitz/GGX, visible-normal sampling, correlated Smith masking and exact complex Fresnel. **No diffuse lobe.** Custom albedo-F0 metal retains Schlick Fresnel.
- Dielectric/water: GGX reflection/refraction, Fresnel branch probabilities, refraction Jacobian, radiance eta compensation and TIR. Thin dielectric is a discrete parallel-interface sheet with two-interface Fresnel and thickness absorption; it does not push the medium stack.
- Coated diffuse/conductor: independent dielectric top reflection, Fresnel attenuation of the base and a matched sampling mixture. This is a thin-layer approximation, not PBRT's complete layered multiple-scattering random walk.
- Foliage: front/back Lambert transmission mixture with energy divided between the two sides. No expensive vegetation volume.
- Emissive: emission plus diffuse scattering. Emission itself remains separate from reflected radiance.

Single-scattering GGX loses energy to unresolved multiple microfacet bounces at high roughness. Furnace tests reject energy gain; this documented masking loss is not compensated with arbitrary brightness. Dispersion/Abbe number is reserved in the native contract, with linear RGB transport throughout.

## Source priority and overrides

1. Supplied LabPBR channels.
2. Explicit VoxelLight override (legacy changed `pbr_materials.json` entries also remain supported).
3. Curated Vanilla texture preset.
4. Family classification.
5. Generic rough dielectric fallback.

The bundled `materials/vanilla/blocks.json` covers all **1,269** Vanilla block textures in the pinned 26.2 client jar. Categories include stone/polished stone, woods, cloth, metals and oxidized/waxed variants, glass/panes/ice, ground, foliage, emissive and special blocks. These are curated family presets, not measured optical data for every Minecraft texture.

Resource packs can provide `assets/<namespace>/material_overrides/*.json`; the user can provide `config/voxellight-materials.json`. Files accept one object or an array. Within explicit overrides: exact texture > `block=` selector > `#tag` > namespace `:*`; last equal-priority rule wins. Reload with F3+T.

```json
[
  {"material":"minecraft:block/polished_andesite","type":"coated_diffuse","roughness":0.32,"ior":1.5,"coat_weight":0.6,"coat_roughness":0.08},
  {"material":"minecraft:block/frosted_ice_0","type":"dielectric","roughness":0.35,"transmission":1,"ior":1.31},
  {"material":"minecraft:block/example","type":"emissive","emission":0.8},
  {"material":"minecraft:*","roughness":0.85}
]
```

Other keys: `f0`, `metal`, `porosity`, `v_roughness`, `coat_ior`, `absorption_r/g/b`, `scattering_r/g/b`, `phase_g`. Absorption is 0–8 inverse blocks; scattering 0–1 inverse blocks; IOR 1–3. Invalid values reject that override rather than uploading NaNs.

Block/tag selectors resolve the active blockstate/model assets, parent models and texture aliases; procedural models without those assets use an exact block-name texture fallback. A shared texture cannot have different materials for two blocks without additional geometry metadata. Exact texture overrides are unambiguous. Captured entity textures retain the supported generic dielectric contract; terrain-specific LabPBR is not fabricated for entity models.

Rain adds a water-film coating at IOR 1.333 and alpha 0.025, weighted by native sky access, upward orientation and porosity. The same model is applied to traced hits. Porous surfaces darken their base slightly; foliage and transmissive volumes do not acquire a mirror coat.

## Audit and tests

```bash
python3 tools/material_report.py --minecraft-jar /path/to/minecraft-client-only.jar --csv materials.csv
python3 tools/material_report.py --minecraft-jar /path/to/minecraft-client-only.jar --pack pack.zip --overrides overrides.json
```

The report lists assignment class/source and coverage/fallback counts. Pack-map presence is reported as LabPBR; spatially varying maps can contain multiple classes, so the report is a texture-level assignment audit, not a GPU per-texel classifier.

`native/rt/tests/bsdf_test.cpp` includes white/grazing furnace, sample/eval/PDF agreement, numerical PDF integral versus accepted-sample probability, reciprocity, roughness ladder, conductor tint, thin-sheet energy, TIR, nested identity, RGB Beer and HG normalization. Java tests execute this **production** header on CPU and test material precedence, LabPBR semantics, quantized medium agreement and database coverage.

GPU acceptance must compare roughness ladder, metal-to-white-wall bounce, dry/rain coating, front/back foliage, nested/stained glass and shoreline silhouettes. Compilation/tests do not establish visual acceptance.
