# Local alpha.49 debugging

This Windows harness launches the already installed Minecraft 26.2/Fabric client directly into an isolated copy of a local world. It uses the installed Java 25 JDK and the existing game libraries, assets and mod jar. No launcher clicks, saved account tokens, multiplayer connections, bytecode transformations or changes to benchmark gates are required.

The Java agent schedules existing Fabric client commands on the client thread and fixture commands on the integrated-server thread. Fixture cleanup affects only the copied world. The existing benchmark still requires a real focused game window and a stationary camera, and preserves its readiness and timing checks. Other foreground applications can interrupt it. Automated entry removes manual steps; it does not eliminate GPU power/temperature variation or background load.

Before running fixture commands the integrated server loads all chunks intersecting the cleanup bounds. It verifies stone, foliage, gold, glass and iron wall anchors from actual server block states and writes `fixture-verified.properties`; a mismatch aborts the client. Command submission alone is not fixture readiness.

Default local paths are the machine used for this debugging session. Override `--source-version-dir`, `--java`, `--world` and `--state-dir` as needed. The source must be the installed 26.2/Fabric version directory with its JSON, jar, mods and a local world. The Java executable must have `javac.exe` and `jar.exe` alongside it. `prepare` copies mods/config/world on first use and writes separate test options. It never writes to the installed version.

From the repository:

```powershell
python tools/local_debug/local_debug.py prepare
python tools/local_debug/local_debug.py launch --suites production
# Full acceptance, default per-block sample durations:
python tools/local_debug/local_debug.py launch --suites guides,transport,scene
```

The harness creates its runtime under `work/automation`. It sets a fixed 1048x672 window, Vulkan, VSync off and an independent test identity, creates the fixture, positions the camera, configures FULL/Wavefront/Monolithic/ENDPOINT/OPTIMIZED and DLSS, then waits 60 seconds. Production preserves the copied daily visibility/queue/OMM/SER controls; the paired suites apply their own controlled configuration. Requested DLSS is not proof of actual RR: inspect exported state and GPU timing scopes.

After the suites it requests ENDPOINT and ENDPOINT_FAST HDR/Guide captures and native screenshots in separate settled intervals, then restores ENDPOINT and stops the dedicated client normally. Single-frame stochastic image differences and nonmatching jitter cannot establish image equivalence. Static fixture cameras are supported; motion and reload/resize/dimension regressions remain separate acceptance work.

An external watchdog retains a handle to the exact owned JVM, checks its creation time, captures thread stacks after 240/270 seconds without driver progress and terminates only that JVM after 300 seconds. This bounds native render-thread hangs that cannot execute the benchmark's own timeout checks. A terminated run is incomplete; raw segments and liveness diagnostics remain available, and no benchmark gate is relaxed. Restart remaining suites explicitly after inspecting the evidence.

Evidence lives in `work/automation/agent-events.log`, `launch-output.log`, and `game/benchmark-results/voxellight/rt-suite-*.zip` / `rr-guides-*.zip`, with final images under `game/screenshots/`. Raw ZIPs contain actual configuration, validity reasons, GPU/CPU scopes, counters and warmup diagnostics. No speed or quality conclusion is inferred from a successful launch.

Pass `--cameras terrain,foliage,mirror,glass_water,entity` to capture settled ENDPOINT/ENDPOINT_FAST pairs at all five fixture cameras after performance sampling. Performance runs at the first selected camera. Camera and capture identities are recorded in the agent log. Vanilla gold does not establish mirror roughness; verified LabPBR materials and motion sequences are still required.

`gpu-telemetry.csv` records device-wide NVIDIA clocks, power, temperature, utilization and memory once per second. Missing telemetry is reported in `telemetry-output.log`; it is not zero load. The read-only sampler checks the owned JVM creation time and exits with that JVM. Device-wide samples can include other applications.

Compiler statistics capture is explicitly enabled by `-Dvoxellight.rt.pipelineStatistics=true`; normal pipelines omit the optional capture flag. `-Dvoxellight.rt.lifecycleDiagnostics=true` logs retirement and each pipeline destruction boundary. These are JVM options, not shader or benchmark settings. Diagnostic capture may change driver compile/retirement behavior and must be recorded in artifact provenance.

The installed Caustica 0.1.1 reference can be prepared separately:

```powershell
python tools/local_debug/local_debug.py prepare --renderer caustica --state-dir work/caustica-reference
python tools/local_debug/local_debug.py launch --state-dir work/caustica-reference --cameras terrain,foliage,mirror,glass_water,entity
```

This copies the installed `caustica-0.1.1.jar.disabled` into the dedicated directory and removes only the copied VoxelLight jar there. It enables Caustica's existing frame CSV profile, RR Performance/1 spp/4 bounces, and disables FG. After 60s prewarm it observes each camera for 30s, records actual RR dimensions/configuration and profile frame boundaries, captures a screenshot, then saves/exits. Focus loss marks `timingValid=false` and continues quality capture. The copied world's time and weather advancement are disabled through the game's rule API.

For background quality collection use `launch --suites capture`; this skips performance measurement and does not bypass a timing gate. Generate the deterministic LabPBR gold test pack with `python tools/acceptance/mirror_pack.py --help`, then pass its ZIP to `prepare --resource-pack PATH`. Both renderers must use the same pack. Pack loading alone does not prove the decoded material or reflection quality.

OPTIMIZED and ASYNC_PREP share the sliced GPU scratch layout and retain the RT context when switching CPU preparation mode. Entering or leaving LEGACY still changes the execution revision and rebuilds the context. Scene acceptance must retain its existing publication, generation and timing checks.

Caustica's CPU frame envelope differs from VoxelLight's GPU World and client frame-interval scopes. These observations are descriptive reference data, not a same-workload cross-renderer FPS verdict; bounce definitions, light/material models, display transforms and motion/quality must still be aligned. Do not run both renderers or a build at once while measuring.

Offline fixture regression:

```powershell
python -m unittest discover -s tools/acceptance -p "test_*.py"
```

The native/shader/Java product build is not needed for this orchestration agent; it is compiled with the existing JDK and calls the installed mod. Any renderer/shader change still requires the project's full prescribed build and acceptance.
