# VoxelLight current state

Current release: **0.21.1** (`mod_version` in `gradle.properties`). This is the current implementation/acceptance summary; PLAN and versioned document sections preserve the roadmap and historical decisions.

Minecraft26.2 / Java25 / Fabric Loader0.19.5 / Fabric API0.160.0+26.2. Native Vulkan only, client only; effects off at startup. Build with `./gradlew build clientKit`; install the resulting mod from the kit and run `/voxellight mode foundation`. Detailed commands and checks: [INSTALL.md](INSTALL.md).

| Stage | State |
| --- | --- |
| B1 material diagnostics; B2 separated terrain HDR lighting | User confirmed corrected inputs and the foliage fix. |
| B3a block-entity shadows; B3b light-aware volume; B3c opaque model material lighting | User confirmed0.13/0.14/0.15; bounded supported streams, unsupported/blended/custom models remain native. |
| D1 directional visibility temporal history | Implemented0.16; user cannot distinguish the improvement, so no claim of full temporal acceptance. Ghosting/cascade/resize checks remain pending. |
| 0.16.1 stability follow-up | Retain surface until verified replacement; LIGHT-only history resets removed; per-frame CPU transform inverses. User confirmed the0.16.1 stability release. |
| D2 basic terrain AO | Implemented0.17: half-resolution horizon AO, spatial bilateral filter/upsample, ambient-only composition. User confirmed it works, with modest visual benefit; no AO history. |
| 0.18 lighting/color polish | Implemented: filmic/manual exposure, hemisphere sky palette, emissive terrain bloom, bounded material-distance blend. User confirmed working. |
| 0.19 local-light polish | Implemented: exact-ID resource-pack colors and one player held-emissive-block source within16 combined lights. User confirmed working. |
| 0.20 atmosphere foundation | Implemented analytic height/distance aerial perspective and directional glow; local supported receivers only; user confirmed working and prefers density0.002.0.20.1 adopts that default. |
| 0.21 Water Foundation | VisualComposite ownership split; native-stream HDR water with Fresnel/absorption/sky reflection/refraction. Screenshot showed blue water grid/dashes;0.21.1 fixes derivative evaluation order and UV rounding. In-game retest pending. |
| Next visual milestone | Validate the HDR/native water seam, then explicitly shadowed low-resolution volumetrics; SSR/GI remain deferred. |

Current budgets: material125 sections within5³,16 MiB resident + up to1 MiB replacement staging,1 MiB/section,one material build/frame. Geometry/light attributes still rebuilt together. Light-aware scene cap384 loaded sections; shadow terrain32 MiB; local-light reference16 combined sources (one slot reserved while a held source exists). D1 adds32 bytes/pixel with128 MiB cap (1440p112.5 MiB;4K falls back to current shadows).

AO uses two half-resolutionRGBA16F targets (32 MiB cap,1440p14.1 MiB,4K31.6 MiB), plus a16-byte neutral texture and32-byte settings. It is terrain-only and bounded by existing material coverage.

Polish adds two quarter-resolutionRGBA16F bloom targets (16MiB cap,1440p3.52MiB,4K7.91MiB). `look reference` restores0.17 lighting/output; polished is the foundation default. Coverage blending fades24–32 blocks within the guaranteed material window; `coverage_blend off` restores the sharp window for comparison. See[polish contract](POLISH.md).

Atmosphere adds16 bytes of settings and no extra image/history. It is an analytic local haze approximation, not shadowed shafts; underwater/non-overworld/unsupported pixels stay native. See[atmosphere scope](ATMOSPHERE.md).

Water adds oneRGBA16F HDR background + oneD32 immutable depth image (96MiB cap;1440p42.19MiB,4K94.92MiB), no duplicate water meshes. Supported nearby Fast/Fancy water only; Fabulous/underwater/uncaptured backgrounds stay native. See[water contract](WATER.md).

No full RGB TAA, motion vectors, complete transparent HDR rendering or shadowed volumetrics, GPU voxel DB or GI yet. Performance results are unmeasured. Continuous world-sun and cutout cache invalidation, duplicate meshes, DDA cost, and history compression remain future work; do not increase local-light count or extend temporal scope before evidence warrants it.

Review decision: preserve the accepted material/lighting architecture; material refresh is confirmed and basic AO is confirmed operational with modest benefit. Lighting/color polish is confirmed working; local-light materials and held source are confirmed working; analytic atmosphere is confirmed working with user-preferred density0.002. The0.20 review leads to Water Foundation with a small composition ownership split; density0.002 is retained. See[local-light scope](LOCAL-LIGHTS.md). See[AO contract](AO.md). See [0.16 review decision](REVIEW-0.16.0.md) and [release history](CHANGELOG.md). This environment has no graphics device/display; automated native shader checks do not replace in-game acceptance.
