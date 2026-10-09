# Local first optimization batch — 2026-10-09

Implementation base: alpha.49 `f83ce25`; branch `codex/local-debug-automation`. This batch does not establish Caustica parity or a native pipeline retirement fix.

## Product change

OPTIMIZED and ASYNC_PREP share the sliced GPU scratch layout. Switching between them no longer changes the execution revision, so the Vulkan RT context remains alive. Entering/leaving LEGACY still rebuilds. Existing scene commit discards unpublished preparation work; generation/version/source checks remain intact. Tests cover both revision contracts and discarding in-flight work.

Pipeline compiler statistics now require `-Dvoxellight.rt.pipelineStatistics=true`; unavailable statistics remain explicitly absent. `-Dvoxellight.rt.lifecycleDiagnostics=true` records retirement and native pipeline destruction boundaries. Destruction clears successfully freed handles and is idempotent. Normal rendering has no added queue drain or per-frame device idle.

## Evidence and limits

- Java compileClientJava passed. Selected Java tests: 5 passed, none skipped. Python fixture regression: 3 passed. Full native/shader release build was not run on this Windows host.
- Local Java overlay retains all 313 non-class jar entries byte-for-byte. Original installed mod/config/world are unchanged.
- Scene suite `rt-suite-1791552181826`: 8/8 valid blocks, no pipeline retirement between first block warmup and final sample. Repeat variation 20.8039% makes the performance verdict `not_comparable`; no FPS/CPU speedup claim.
- After Scene export, restoring original QUERY/OMM/SER settings hung at `vkDestroyPipeline(material_indirect_full)`; the watchdog saved stacks at 240/270 seconds and terminated the owned JVM at 300 seconds. A completed suite does not imply a completed session.
- Statistics-off Transport completed once (16/16) but hung on repeat. A prototype draining two submit slots also completed 16/16, yet destruction still took 48/49 seconds. The prototype was removed; only its diagnostic patch/evidence are retained locally.
- Prior static captures had unloaded fixture chunks: commands were submitted but some fills were rejected. Those empty-camera images are not quality acceptance. The harness now loads the full cleanup extent, verifies five actual server wall block states, and aborts on mismatch.
- Updated quality-only VoxelLight run: five verified cameras, ten raw HDR/Guide exports, no nonfinite floats, normal saved exit. Gold wall interior RR roughness ~5.9139e-8 in ENDPOINT and ENDPOINT_FAST. This is material-input verification, not temporal quality equivalence.

## Repeat local work

Read `tools/local_debug/README.md`. Use separate state directories per renderer, one client at a time. `--suites capture` collects quality in the background without running a performance benchmark. Performance suites retain real focus/readiness/repetition gates. Record actual RR dimensions and artifact hashes; do not compare Caustica's CPU frame envelope against VoxelLight GPU World as if they were the same scope.

Next: isolate native create/use/retire/destroy with Vulkan lifetime/synchronization validation and a GPU timeline. Cover suite restoration, reload, resize, dimension/world generation changes before claiming lifecycle reliability. Then validate mirror/transmission motion and material filtering/mips; use measured B1/B2 costs before promoting algorithm changes. Keep FULL/Wavefront/Monolithic/OPTIMIZED/ENDPOINT defaults; SIMPLE/TWO_PASS/FAST/ASYNC_PREP remain controlled experiments.
