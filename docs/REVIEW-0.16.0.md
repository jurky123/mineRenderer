# 0.16.0 review decision

The review targets93d4fc5. Keep the Visual Foundation architecture. Deliver0.16.1 stability fixes before Basic AO; do not enlarge D1 history when the user cannot distinguish its benefit.

Verified actionable findings:

- MaterialSurfaceStore retired stale tokens before one-section/frame rebuild. LIGHT-only propagation therefore removed foundation coverage. Retain old meshes through a verified replacement; retire on window/world/resource/section lifetime changes. Keep16 MiB steady and add at most1 MiB transient replacement staging. Same-depth attributes may remain briefly stale; native-depth matching still gates display.
- lighting/shadow, output and temporal shaders computed frame-constant inverses. Upload the actual captured projection inverse and three light normal transforms through the shared560-byte resolve block. Native compilation/UBO reflection and CPU normal-transform equivalence validate the interface. A compiler might already hoist uniform calculations; no measured speedup is claimed.
- Directional history included pure LIGHT events despite accumulating no local-light RGB. Use a geometry change stream revision; retain invalidation for geometry, lifetimes and caster publication.
- README artifact versions and PLAN status drifted. CURRENT is the current-state summary; README uses version-independent build names and older notes move to CHANGELOG. Historical technical documents are explicitly versioned.

Temporal review qualification: a dynamic-affected pixel stores negative history visibility, so its previous footprint is invalid when the caster moves away; no additional prior-footprint mask is needed on the current path. Motion/PCF-edge behavior still requires game testing. Global CPU admission counters cannot prove per-pixel GPU acceptance. User feedback does not establish that D1 is broken or fully accepted.

Defer shadow epochs, cutout animation classification, mesh reuse, clustered lights and history compression to a measured performance phase. Existing foundation coverage boundary remains bounded5³, and AO must not silently extend that window. Next feature is half-resolution Basic AO + depth/normal bilateral upsample, then lighting/tone/sky polish; GI stays last.
