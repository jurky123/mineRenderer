# Visual Foundation: implementation contract

Current implementation and acceptance: [CURRENT.md](CURRENT.md). This document is the material/lighting contract; versioned sections below describe historical implementations.

## Objective

Produce lighting from unlit material data, geometry normals, separated illumination and emission. Directional visibility affects sun/moon direct light, not emission, local/block light or the final fogged image. Keep a runtime legacy comparison and retain timings, budgets, status and export.

The foundation must make the following scene meaningful: a torch-lit wall behind a sun-shadowing obstruction retains its local illumination, while the direct sun contribution disappears; an emissive surface retains its own radiance.

## B1: material capture proof — corrected diagnostics accepted in 0.11.2

Start with native opaque/cutout terrain. Add independently inspectable material/albedo, geometry-normal, emission and coverage modes. Render actual geometry using the native camera projection, section offsets, alpha texture and depth convention. Do not derive normals from neighboring screen depth. Do not use the already-lit SceneColor as albedo.

Two concrete native boundaries exist:

- `ChunkSectionsToRender.renderGroup(OPAQUE, sampler)` has the actual terrain draw groups, atlas, sequential indices and frame-scoped section UBOs. A replay can establish depth/cutout/projection alignment without recompiling a second visible terrain set.
- `ModelBlockRenderer.tesselateBlock` / `BlockQuadOutput` carries the block state and baked quad context before the final BLOCK stream loses material/normal information. `BlockModelLighter.prepareQuadFlat` still applies cardinal brightness; tint must be captured separately from that lighting. Test the chosen interception against the native call sites and keep changes local to the enhanced path.

Native BLOCK has no normals. Prefer material/quad normals captured before packing. Fragment derivatives of interpolated **geometry position** can be a terrain-only geometric-face reference, but cannot supply smooth entity normals or normal-map data; label that scope accurately. Preserve ENTITY normals on its later migration instead of routing material geometry through the current shadow-only BLOCK conversion.

Do not invert approximate lightmap/color factors to “recover” materials. Do not infer emission from a bright final texel or vertex block-light value. Distinguish block emission, baked-quad/material emission and unsupported resource-pack emissive conventions. Unknown material coverage remains explicitly unsupported.

Initial MRT contract, subject to device format validation:

| Resource | Proposed format | Content |
| --- | --- | --- |
| Material | RGBA8_UNORM | Authored texture × unlit vertex/biome tint; alpha = exact integer flags encoded in UNORM, not blended coverage. Document sRGB decoding. |
| Normal/coverage | RGBA16_FLOAT | Signed unit geometry normal in world space; alpha = valid supported surface. Initial clarity over compact oct encoding. |
| Emission | RGBA16_FLOAT | Linear emissive radiance; alpha reserved. Block emission strength alone is not emission color. |
| Depth | Native D32, borrowed or private as the experiment requires | Native reversed-Z, same camera/projection; no hand/UI depth. Borrowed depth must not be sampled while simultaneously used as an attachment. |

Clear coverage every frame; no old pixel validity after movement, resize or reload. A native main-depth attachment with EQUAL/no-write replay requires bit-identical position transforms and cutout behavior. A private-depth alternative must validate agreement and composition; a tolerance must not expose hidden geometry. A proof must check actual native vertex/shader conventions before choosing either.

At 2560×1440 the three material targets cost 73,728,000 bytes (~70.3 MiB); a private 4-byte depth adds 14,745,600 bytes (~14.1 MiB). Targets are size-dependent owned resources, not fixed 1440p allocations. Enforce a size/byte limit and an observable fallback before allocating. No histories or GI textures in B1.

B1 acceptance: six-face block normals, sloped/stair faces, slab silhouettes, foliage/cutout gaps, tinted grass/leaves, textured albedo independent of daylight/torch brightness, known emission independent of directional shadows, depth alignment during bob/hurt/nausea, resize/reload/teleport/world-switch reset. Record unsupported material/entity pixels. Native MRT shader reflection/binding tests are necessary; GPU screenshots are also required.

## B2: separated opaque lighting

Introduce a named foundation path beside the legacy path. Resolve after supported opaque geometry and before transparent/weather/particle composition. The existing after-LevelRenderer callback remains valid only for legacy diagnostics; it is not the final foundation integration point.

Compute a linear lighting result:

`albedo * (skyAmbient + directionalDirect * directionalVisibility + localOrBlockLight) + emission`

Retain a defined vanilla block-light compatibility term until every local source can be represented. The bounded 16-source local-light reference is not a replacement for all vanilla block lighting; avoid both losing excluded sources and adding their complete illumination twice. Choose and document a baseline/replacement convention and test dense torches.

Separate sun/moon color and intensity, hemisphere sky ambient, local light visibility and emission. No AO, GI or volumetric term yet. Local lights still use the bounded reference visibility implementation. Normals/materials feed lighting directly; current depth-normal stabilizers do not become foundation dependencies.

Write linear HDR lighting to RGBA16_FLOAT (~28.1 MiB at 1440p), then apply exposure/tonemapping and native fog in a documented order. Respect that fog is a composition term, not surface albedo. Decode texture color correctly once; treat normals, flags and depth as linear data. Test the native target's color-space convention; do not assume RGBA8_UNORM itself implies sRGB storage.

No full-frame SceneColor-copy dependency for fully supported opaque lighting. Temporary mixed coverage may need an explicit baseline/fallback; identify that cost rather than claim the copy disappeared. Unsupported geometry remains vanilla rendered and must not receive blanket legacy relighting at the end of the foundation frame. Hand, UI, outlines, spectator effects and transparency remain native until individually integrated.

B2 acceptance: sun-shadowed torch wall, emissive blocks under sun shadow, cave/night visibility, rain and low sun, native fog applied once, sky/background unchanged, water/glass/particles in the correct composition order, local source count overflow preserving declared baseline. Compare with legacy under identical camera/time/weather settings.

## B3: geometry coverage and caster correctness

Validate 0.10.0 players/mobs/armor/animations/off-camera shadows/removal/reload/third-person/multi-entity behavior. Migrate supported entity materials/normals to the foundation, using native model submissions with separate material ownership. Held items remain a known gap until their actual submit path is implemented.

Refactor the current entity capture into a dynamic caster boundary before adding block entities. Use loaded section/block-entity enumeration and native `BlockEntityRenderDispatcher.tryExtractRenderState` / `submit`; do not use only the camera-visible block-entity list. Prioritize chest, bed, shulker and banner; list unsupported submit types and mod renderers. Keep dynamic shadow layers independent of terrain cache invalidation.

Directional caster selection becomes receiver-driven: receiver sphere plus bounded extrusion toward receiver-to-light direction. Validate the search against cascade near/far planes and low-sun shadow reach. Preserve loaded-only requests and residency caps. Keep local-light voxel selection independent. Report missing/over-budget coverage honestly; neither increasing the cube nor choosing a long extrusion guarantees complete coverage under a fixed memory budget.

Only after B1–B3 acceptance call the material lighting foundation complete.

## Ownership boundaries

Extract only real responsibilities required by the migration:

- Terrain caster store: section meshes, tokens, bounds, geometry residency and budgets.
- Directional shadow system: cascade targets, projection, tile validity and shadow draws.
- Dynamic caster system: entity/block-entity capture and transient shadow geometry.
- Local-light system: emitter/occluder data and bounded source selection.
- Material capture and lighting resolve: GBuffer targets, supported coverage, lighting and composition.

A renderer coordinator owns enhanced-mode frame order and resets. Keep COLOR/DEPTH/legacy NORMAL diagnostics and metrics separate from material lighting. Rename `RenderProbe` only when the new effect coordinator is actually introduced; avoid cosmetic churn or empty managers.

## After foundation

Temporal shadow/lighting reprojection with depth/normal rejection comes before GI. Reject unsupported dynamic pixels without motion data. Add basic AO, data-driven light materials, improved emitter aggregation and bounded dynamic sources next. Water/atmosphere/SSR are separate optional features after composition and histories are correct. GPU voxel database and probe GI follow verified material/normal/emission and lighting inputs.

Maintain a quality reference while later measuring dual-angle shadow epochs, static-vs-animated cutout, native caster reuse, clustered lights, rolling voxel uploads, lower-cost PCF and dynamic batching. “Visual first” does not waive memory/upload caps, lifecycle safety or accurate metrics. No performance benefit is accepted without comparisons.

## 0.12.0 B2 reference implementation

`mode foundation` reuses accepted terrain materials and the existing directional/entity shadow maps and local-light voxel reference. `LightingResolvePass` owns linear RGBA16F HDR, a 32-byte environment UBO and the two lighting/output pipelines. `ShadowRenderer.updateLighting` produces visibility resources without touching SceneColor; material capture and resolve have separate owners.

The reference emission properties target now stores block/quad emission strength in R/G, interpolated native sky-light level in B and block-light level in A; normal alpha remains coverage. Emission radiance is decoded authored albedo × max(R,G) × 2.4. This is a declared reference color convention, not authored emissive texture support or a material registry. `emission` diagnostic still displays strength R/G.

Directional diffuse uses the actual geometry normal, a warm elevation-dependent sun or cool moon, native celestial/weather intensity and sky access. Hemisphere sky and a small constant visibility floor remain independent of directional occlusion. Block compatibility uses native packed per-vertex light **levels**, with a declared warm squared-level response rather than claiming exact native lightmap/night-vision/gamma parity. Selected local sources retain shape-aware visibility; a smooth energy comparison replaces the baseline with stronger selected illumination instead of adding the same complete term twice. Disabling `local_lights` disables selected local sources, not this compatibility baseline. There is no exact per-source subtraction or clustered lighting yet.

Linear lighting is tone mapped with fixed exposure 1 and Reinhard, encoded to display sRGB, then mixed with the native encoded FogColor using native environmental and render-distance fog ranges. Distances are reconstructed per pixel, rather than native vertex-interpolated fog distances. The main target is the same native RGBA8_UNORM convention as terrain textures/fog, and does not apply a hardware sRGB conversion. Native entities, fluids, glass, weather, particles, hand and UI continue afterward; they retain vanilla shading.

Unsupported/unaligned HDR pixels have alpha 0; output discards them, retaining the already present native main color without sampling or copying it. Both new fullscreen passes have no depth attachment/write. At 1440p HDR is 28.125 MiB, total material+HDR 112.5 MiB (32 bytes/pixel); combined active target limit 256 MiB, material sublimit 192 MiB. Existing shadow/local-light/geometry budgets remain independent. Material diagnostics still own a 4-byte/pixel scratch copy; foundation reports scratchBytes=0. Residency is still the finite 125-section material window; camera movement can reveal unsupported native fallback pixels, not a complete view-distance renderer.

`SurfaceToken` includes LIGHT changes to retire stale packed light immediately; existing `GeometryToken` behavior for shadow casters is unchanged. Surface rebuild is one section/frame, so a large light update can briefly expose native fallback while rebuilding. Resize/world/resource lifecycle closes owned targets through existing reset hooks; HDR coverage is cleared/replaced every frame.

Acceptance remains: torch-lit sun-shadowed wall, glowstone under shadow, full/new moon, caves/Nether/rain, glass/water/particles/fog/UI composition, >16 nearby emitters, placing/breaking lights, reload/resize/dimension travel. CPU/native shader/binding tests do not replace these visual checks. B3 entity materials/block-entity casters/light-aware volume and later temporal work remain ahead.

## 0.12.1 foliage stability

The user confirmed the overall 0.12.0 foundation effect and reported plant flicker. Capture now matches native terrain backface culling; native plant models provide opposing faces and should not receive additional coplanar backface writes. Cutout diffuse/hemisphere response uses a two-sided convention, and local visibility bias selects the emitter-facing side instead of flipping with camera position. Both changes preserve strict depth validation and native cutout sampling. Plant fix acceptance is pending; ordinary subpixel cutout aliasing is not a claim of temporal stabilization.

## 0.13.0 B3a dynamic caster coverage

DynamicCasterSystem owns the mob and loaded block-entity stream boundaries; DynamicModelBuffer owns shared bounded native model conversion, sprite-coordinate expansion, GPU/CPU vertex memory, renderer checkpoint/rollback and borrowed sequential indices. Both streams render into the existing dynamic cascade depth alongside terrain depth; static cache generations are unchanged. Index growth/refresh happens once for both streams after each terrain cascade, before opening the dynamic pass.

Block-entity selection enumerates only already loaded chunks within the 64-block candidate radius, independent of camera frustum/visible sections. Each stream has separate 32-object/128-model/1-MiB frame limits, 256-KiB scratch and status; packed block positions retain 64 bits for deterministic ties. Native dispatcher extraction and model/model-part submission preserve chest/shulker/banner animation and atlas UVs. Failed object capture rolls back its entire partial stream; toggles/removal clear the dynamic layer next frame. Non-model, blended/custom/item/text submits are not claimed as covered. Native 26.2 beds are ordinary BedBlock terrain models, not block entities.

This is B3a rather than a declaration that B3 is complete: dynamic model materials/normals are still vanilla-rendered and current shadow-only BLOCK conversion remains. B3 material migration must use retained normals in its new stream; receiver-driven light extrusion is also outstanding. In-game acceptance: opening single/double/ender chests, shulker animation, banner wind, off-camera caster, removal/toggle, reload/world/resize, mobs/player alongside block entities, and bounded overflow. Dynamic light visibility remains block-voxel-only.


## 0.15.0 B3c native opaque model material stream

Actual ModelFeatureRenderer.renderToBuffer is evaluated once: a bounded tee copies the native attributes after pose application, with equivalent sprite UV wrapping. It preserves smooth normals and native overlay/light attributes in ENTITY, independently of the BLOCK shadow stream. Supported known solid/cutout/ordinary armor pipelines use native .1 cutout, cull state, Sampler0/1 and the same DynamicTransforms/Projection. ENTITY flag16 keeps cutout mobs out of the terrain foliage's abs(diffuse) rule. Hurt overlays remain tint; entity emission is zero rather than inferred from fullbright light.

The late boundary is PreparedFrame.executeSolid TAIL inside world rendering, before depth copies and executeTranslucent. Reuse material/HDR targets after terrain output; clear coverage/depth, capture models, match native scene depth (8 ULP), reuse prepared shadow/local-light data, resolve/tone/native fog without main-depth writes or SceneColor copy. Armor-decal trim gets a coverage exclusion after supported surfaces; any dropped trim mask disables entity relighting for that frame. Later native translucent/glint/particles/UI remain in their original order.

128 attempts/1 MiB CPU+GPU frame/256 KiB scratch; borrowed native textures/uniforms remain frame-local. No new fullscreen textures; settings total64 bytes. Counters and separate *_ENTITIES pass metrics are included. Ordinary mobs/armor and some opaque block entities are covered; blended player skins, emissive/dissolve/custom effects, held items and arbitrary custom coplanar layers are not fully migrated. User acceptance must check animation/depth/cutout/normal/lighting/overlays/removal/reload/resize/world travel. B3 is not declared universally complete and no motion history/GI is introduced.

## 0.16.0 D1 directional visibility history

用户确认0.15.0 opaque entity material lighting，进入temporal第一步。仅terrain direct sun/moon visibility过滤，MRT保留current HDR + visibility/direct term，额外pass重建position并reproject至上一帧packed visibility/oct-normal/radial-distance history，再修正current direct radiance。不会积累RGB；late entity material lighting仍current。dynamic caster遮挡足迹与animated/ENTITY材料拒绝history，强visibility变化立即响应。

GameRendererMixin捕获renderLevel实际ProjectionMatrixBuffer.getBuffer(Matrix4f)，包含bob/hurt/nausea；不能使用nominal camera projection替代。world/resource/scene changeRevision、caster geometryRevision、light source/jump、camera cut、frame gap、settings/resize使history失效。Bridge changeRevision仅增加可观测dirty-stream序号，不改变geometry/light-only契约。

四张RGBA16F、32 bytes/pixel、128 MiB额外cap和96-byte UBO；超过cap退回unfiltered foundation。两个MRT render descriptors显式renderArea，不写native depth。CPU admission counters不代表GPU pixel acceptance。native shader binding/阶段编译与CPU reprojection/invalidation/budget检查通过；移动阴影/编辑/plant/model/reload/resize实机验收待用户。下一步在D1验收后做basic AO或后续temporal覆盖，full TAA/motion vectors/GI未完成。

## 0.16.1 material refresh and frame transforms

SurfaceToken仍推进packed light版本，但同一section lifetime内可以retain旧mesh至replacement。新mesh通过current token与resident budget验证后才替换；oversized/等待邻居/stale build不移除旧mesh。资源、世界或entry incarnation不匹配以及离开窗口时立即释放。几何变化期间旧surface仍必须通过现有native depth 8-ULP验证；same-depth材质或light可短暂陈旧，不能承诺瞬时更新。steady16 MiB与1 MiB/section保持，替换临时GPU顶点至多额外1 MiB。

ShadowResolveSettings增加InvProjection和三张LightNormalMatrix（mat4 upper3×3），总560 bytes，所有四个fragment消费者共用反射验证。actual world projection通过现有hook同步CPU inverse，caster矩阵每frame CPU inverse-transpose；不改变native depth、PCF、fog或HDR tone模型。没有实测GPU性能提升。

D1 history key使用geometryChangeRevision，不受纯LIGHT dirty影响；动态阴影上一帧eligible=false会保存negative visibility，因此刚移开dynamic caster也拒绝该history。完整移动动物/开箱/banner/camera/cascade实机验收仍pending，不扩大temporal系统。

## 0.17.0 D2 ambient occlusion composition

`AmbientOcclusionPass`位于terrain MaterialCapture与current lighting之间；只输出/过滤visibility + view normal + linear view depth。AoSettings32 bytes，各fragment consumer反射验证。Lighting中ambient=minimum fill + sky + (1-replacement)×native block-light fill，AO仅作用于ambient；selected lamp term、sun/moon direct与emission独立不受AO。

Late native entity material capture复用已有terrain AO targets和同帧lighting inputs，但ENTITY receiver明确返回AO=1，不重新搜索/accumulate动态AO。AO view在tone/fog前直接显示应用后factor，不混入directional history的directRGB；settings change重置原shadow history。full depth仍只读。

32 MiB half-target cap、frame-current空间滤波与现有5³coverage不扩张；texture/cutout/entity AO和full TAA仍未实现。实现与实机门槛见[AO.md](AO.md)。
