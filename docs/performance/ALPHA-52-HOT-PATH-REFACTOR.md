# alpha.52 — Integrated hot-path refactor

Baseline: `d9f11df`. This change integrates preparation and removes repeated work without reducing FULL transport, EXACT shadow traversal, or sample budgets. It is not a measured FPS parity claim against Caustica.

## Changes and evidence

| Module | Repeated work addressed | Implementation | Verification |
| --- | --- | --- | --- |
| Surface BSDF | Outgoing-direction and material preparation repeated across evaluation, sampling and direct lighting | One PreparedBsdf context per surface hit; shared energy preparation and prepared lobe entry points; historical GGX_PREPARED pipeline becomes a compatibility alias | 57,600 material cases / 3,225,600 components per numerical bank; maximum normalized error 0.000381917 |
| Direct RIS | Emission/atlas and BSDF evaluation before receiver rejection | Generate candidate geometry and consume the original proposal RNG, reject impossible receiver directions, then fetch exact spatial emission and evaluate prepared BSDF | Existing 200,000-sample RIS cases and matched hit-PDF checks; shared emission/cutout checked at multiple barycentric positions |
| Hit contributions | Repeated global beauty and AOV reads/writes within a hit | Begin with the existing global running sum; preserve contribution order and invalid-value handling; flush once at exit, with an explicit cache-probe observation boundary | 48 bitwise cases including large prefixes, invalid values, overflow, channel mapping and metadata |
| EXACT shadow interface | Full shaded material decoding for visibility traversal | Decode only classification and exact optical interface fields; retain ordered media and medium identity | 32 terrain cases / 1,408 components, maximum normalized error 2.98023e-08; sigmaA/sigmaS/phaseG/IOR/thickness and identity parity |

## Validation scope

- Java client compilation passed.
- Production Slang/native transport regression passed, including material, terrain, environment, cache, guide/reconstruction, queues, visibility and RIS checks.
- All 110 production Vulkan RT stages compiled and passed SPIR-V/ABI validation. Camera ABI remains 96 bytes; continuation storage remains 64 bytes plus 288-byte cold media and 48-byte AOV storage.
- End-to-end validation passed on 2026-10-11: one isolated client, glass/water scene, capture-only, with observed FULL/EXACT/CLEAN/Wavefront and realtime policy FULL. ENDPOINT and ENDPOINT_FAST exported all 13 raw float layers each and final screenshots. Every float value was finite; HDR input, RR output and transmission AOV were nonzero. Input was 524x336 and RR output 1048x672. The owned client exited normally with code 0; Windows-MCP verified its foreground window.
- Local evidence is under workspace `outputs/alpha.52`: `module-tests.txt`, `e2e-validation.json`, two raw capture ZIPs, final PNGs, overlay provenance and verified exit/focus records. The development JAR SHA-256 was `f9f5b33d5bfeca496b20e424324e69410edf10a9d2e569c97259d0f0aa6507fa`.

The local development overlay retains the baseline native libraries and replaces current Java/resources and validated shader stages. The original installed Minecraft directory is untouched. Historical prepared-energy A/B results remain historical; the retired experiment tool does not generate a misleading new A/B from integrated production shaders.

## Limits and next decisions

These changes eliminate identifiable repeated operations, but register pressure, compiler optimization and workload distribution can change their GPU benefit. Passing module and end-to-end checks establishes correctness and integration, not a new measured speedup or Caustica parity. The requested reduced validation scope does not include repeated cross-renderer runs.

Do not replace spatial emitter textures with uniform average emission or alter proposal/PDF/RNG budgets. Compact emitter resource preprocessing and any further shader register/traversal optimization remain separate changes requiring their own evidence. The optional external model review produced no final answer and is not counted as verification.
