# VoxelLight current state

Current release: **0.18.0** (`mod_version` in `gradle.properties`). This is the current implementation/acceptance summary; PLAN and versioned document sections preserve the roadmap and historical decisions.

Minecraft26.2 / Java25 / Fabric Loader0.19.5 / Fabric API0.160.0+26.2. Native Vulkan only, client only; effects off at startup. Build with `./gradlew build clientKit`; install the resulting mod from the kit and run `/voxellight mode foundation`. Detailed commands and checks: [INSTALL.md](INSTALL.md).

| Stage | State |
| --- | --- |
| B1 material diagnostics; B2 separated terrain HDR lighting | User confirmed corrected inputs and the foliage fix. |
| B3a block-entity shadows; B3b light-aware volume; B3c opaque model material lighting | User confirmed0.13/0.14/0.15; bounded supported streams, unsupported/blended/custom models remain native. |
| D1 directional visibility temporal history | Implemented0.16; user cannot distinguish the improvement, so no claim of full temporal acceptance. Ghosting/cascade/resize checks remain pending. |
| 0.16.1 stability follow-up | Retain surface until verified replacement; LIGHT-only history resets removed; per-frame CPU transform inverses. User confirmed the0.16.1 stability release. |
| D2 basic terrain AO | Implemented0.17: half-resolution horizon AO, spatial bilateral filter/upsample, ambient-only composition. User confirmed it works, with modest visual benefit; no AO history. |
| 0.18 lighting/color polish | Implemented: filmic/manual exposure, hemisphere sky palette, emissive terrain bloom, bounded material-distance blend. In-game acceptance pending. |
| Next visual milestone | After polish acceptance: local-light materials/dynamic-light polish, then water/atmosphere; preserve the direct/emission composition contract. |

Current budgets: material125 sections within5³,16 MiB resident + up to1 MiB replacement staging,1 MiB/section,one material build/frame. Geometry/light attributes still rebuilt together. Light-aware scene cap384 loaded sections; shadow terrain32 MiB; local-light reference16 sources. D1 adds32 bytes/pixel with128 MiB cap (1440p112.5 MiB;4K falls back to current shadows).

AO uses two half-resolutionRGBA16F targets (32 MiB cap,1440p14.1 MiB,4K31.6 MiB), plus a16-byte neutral texture and32-byte settings. It is terrain-only and bounded by existing material coverage.

Polish adds two quarter-resolutionRGBA16F bloom targets (16MiB cap,1440p3.52MiB,4K7.91MiB). `look reference` restores0.17 lighting/output; polished is the foundation default. Coverage blending fades24–32 blocks within the guaranteed material window; `coverage_blend off` restores the sharp window for comparison. See[polish contract](POLISH.md).

No full RGB TAA, motion vectors, atmosphere/water rewrite, GPU voxel DB or GI yet. Performance results are unmeasured. Continuous world-sun and cutout cache invalidation, duplicate meshes, DDA cost, and history compression remain future work; do not increase local-light count or extend temporal scope before evidence warrants it.

Review decision: preserve the accepted material/lighting architecture; material refresh is confirmed and basic AO is confirmed operational with modest benefit. Lighting/color polish is now implemented. See[AO contract](AO.md). See [0.16 review decision](REVIEW-0.16.0.md) and [release history](CHANGELOG.md). This environment has no graphics device/display; automated native shader checks do not replace in-game acceptance.
