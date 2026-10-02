# 0.10.0 review and implementation decision

Reviewed baseline: `2808397807449f7b7e490907a9f2b57d470d18ae`, Minecraft 26.2. This records the user's supplied review and the follow-up local source audit. It is a planning decision, not a claim that a GBuffer or a new lighting implementation has shipped.

## Decision

Proceed with a **Visual Foundation** migration. Stop extending the existing post-lighting `shadow.fsh` with AO, GI, water, atmosphere or additional lights. Preserve the 0.10.0 renderer as a named legacy comparison path, its instrumentation, and the independent world-change/caster systems.

The first implementation should prove material capture with actual terrain geometry and native projection/depth/cutout rules. Then implement separated sun/sky/block-light/emission resolve for the supported geometry. Do not call a buffer “albedo” if it still contains baked directional shading, AO, lightmap or fog.

0.10.0 has 81 passing automated tests, including native model geometry and shader bindings. The user confirmed 0.9.0 shape occlusion; 0.10.0 dynamic entity visuals have not yet been confirmed. Entity validation remains an open gate, not a reason to block independent material/API research. Block-entity shadow and light-aware caster selection remain required correctness work; GI waits for the foundation and temporal validation.

## Findings checked against local sources

| Review point | Finding and consequence |
| --- | --- |
| Lighting uses already-lit LDR color | Confirmed in `shadow.fsh`: final color is multiplied by directional visibility and receives artistic local/moon fill. Torch illumination, emission and fog cannot be recovered from this result. Replace the data path, not its coefficients. |
| Depth normals are a representation limit | Confirmed. Keep the current normal mode as a diagnostic/legacy reference. Use actual geometry/material normals for the foundation; normal maps are a separate future feature. |
| MRT feasibility | Native `RenderPassDescriptor.colorAttachments` is a list, and `withColorAttachment` appends targets. An MRT experiment is possible through Blaze3D; this is API evidence, not a GPU validation result. |
| Existing terrain vertices are not sufficient materials | Native `DefaultVertexFormat.BLOCK` contains Position, Color, UV0 and UV2, but **no Normal**. `BlockModelLighter.prepareQuadFlat` still puts cardinal face brightness in Color; `ModelBlockRenderer` multiplies biome/material tint into it. Disabling AO does not produce unlit albedo. The current entity shadow conversion to BLOCK also discards native entity normals. |
| Terrain draw reuse | Native `ChunkSectionsToRender` exposes texture view, per-layer draw groups, index requirements and per-section UBO slices. It offers a concrete replay research point for visible surface capture. It must not replace the independent off-camera shadow caster set. Borrowed native buffers are frame-scoped and must not become long-lived cached references. |
| Composition placement | The current hook runs after all `LevelRenderer.render` work. Native main-pass order is opaque terrain → solid features → translucent features/terrain. Foundation lighting must resolve supported opaque surfaces before transparent composition, rather than relight the final frame. Fog, particles, hands, outlines and UI need explicit ownership. |
| Caster selection | The camera-centered window can miss distant up-light casters. Our `ShadowLight.direction()` is **receiver-to-light**, so caster search extrudes toward **+direction**; do not copy a negative sign from a differently defined light vector. Respect loaded-only selection, world height, budgets and map depth range. |
| Natural-sun cache | Confirmed: exact continuous angle changes invalidate all tiles. Current rendering prioritizes correct moving projections; existing cache benefits cannot be claimed for running daylight. Preserve this reference; dual-angle epochs are a later measured experiment. |
| Cutout cache | Confirmed: cutout bounds are marked each frame for animation safety. Material/sprite animation metadata should eventually distinguish animated from static cutout. |
| Local-light and PCF costs | Confirmed bounded, expensive reference paths: up to 16 lights × 48 block traversal steps, partial shapes up to 16 boxes, and 36 shadow taps per selected cascade with a second depth lookup when dynamic models exist. Do not increase source caps before changing the architecture and measuring GPU costs. |

## Where the roadmap differs from the supplied recommendation

Material capture is the highest-priority next implementation, rather than waiting for every dynamic caster extension. Block entities and light-aware selection fix real gaps, but neither solves second lighting of LDR color. They can follow the capture proof and precede foundation acceptance.

Use three small foundation milestones rather than promise a complete renderer rewrite in one release. Do not implement optional effects or a speculative multi-manager framework. Extract existing responsibilities only as their data/ownership boundaries become necessary for the new path.

A full native GBuffer is not yet proven. Terrain-only diagnostics are an explicit first scope, not a claim that transparent objects, entities, resource-pack emission or all modded geometry are supported. See [VISUAL-FOUNDATION.md](VISUAL-FOUNDATION.md) for the concrete contract and acceptance gates.
