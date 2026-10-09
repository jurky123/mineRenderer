# Local alpha.49 debugging

This Windows harness launches the already installed Minecraft 26.2/Fabric client directly into an isolated copy of a local world. It uses the installed Java 25 JDK and the existing game libraries, assets and mod jar. No launcher clicks, saved account tokens, multiplayer connections, bytecode transformations or changes to benchmark gates are required.

The Java agent schedules existing Fabric client commands on the client thread and fixture commands on the integrated-server thread. Fixture cleanup affects only the copied world. The existing benchmark still requires a real focused game window and a stationary camera, and preserves its readiness and timing checks. Other foreground applications can interrupt it. Automated entry removes manual steps; it does not eliminate GPU power/temperature variation or background load.

Default local paths are the machine used for this debugging session. Override `--source-version-dir`, `--java`, `--world` and `--state-dir` as needed. The source must be the installed 26.2/Fabric version directory with its JSON, jar, mods and a local world. The Java executable must have `javac.exe` and `jar.exe` alongside it. `prepare` copies mods/config/world on first use and writes separate test options. It never writes to the installed version.

From the repository:

```powershell
python tools/local_debug/local_debug.py prepare
python tools/local_debug/local_debug.py launch --suites production
# Full acceptance, default per-block sample durations:
python tools/local_debug/local_debug.py launch --suites guides,transport,scene
```

The harness creates its runtime under `work/automation`. It sets a fixed 1048x672 window, Vulkan, VSync off and an independent test identity, creates the fixture, positions the camera, configures FULL/Wavefront/Monolithic/ENDPOINT/OPTIMIZED and DLSS, then waits 60 seconds. Production preserves the copied daily visibility/queue/OMM/SER controls; the paired suites apply their own controlled configuration. Requested DLSS is not proof of actual RR: inspect exported state and GPU timing scopes.

After the suites it requests ENDPOINT and ENDPOINT_FAST HDR/Guide captures and native screenshots in separate settled intervals, then restores ENDPOINT and stops the dedicated client normally. Single-frame stochastic image differences and nonmatching jitter cannot establish image equivalence. This harness initially covers one terrain camera; mirror/glass/entity motion and reload/resize/dimension regressions remain separate acceptance work.

An external watchdog retains a handle to the exact owned JVM, checks its creation time, captures thread stacks after 240/270 seconds without driver progress and terminates only that JVM after 300 seconds. This bounds native render-thread hangs that cannot execute the benchmark's own timeout checks. A terminated run is incomplete; raw segments and liveness diagnostics remain available, and no benchmark gate is relaxed. Restart remaining suites explicitly after inspecting the evidence.

Evidence lives in `work/automation/agent-events.log`, `launch-output.log`, and `game/benchmark-results/voxellight/rt-suite-*.zip` / `rr-guides-*.zip`, with final images under `game/screenshots/`. Raw ZIPs contain actual configuration, validity reasons, GPU/CPU scopes, counters and warmup diagnostics. No speed or quality conclusion is inferred from a successful launch.

Offline fixture regression:

```powershell
python -m unittest discover -s tools/acceptance -p "test_*.py"
```

The native/shader/Java product build is not needed for this orchestration agent; it is compiled with the existing JDK and calls the installed mod. Any renderer/shader change still requires the project's full prescribed build and acceptance.
