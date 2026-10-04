# 0.37.2 reference crash hardening

The supplied 0.37.1 log ends immediately after reference activation and contains no fatal stack or CUDA error. It establishes a depth-pyramid exception beforehand, but does not establish the fatal crash cause. Authentication errors in the earlier startup log are unrelated to renderer execution.

## Changes

- Minecraft 26.2 reports rectangular mip-view dimensions with raw shifts. The former pyramid continued after its shorter dimension reached one, producing a `1x0` Java texture view while the render area remained `1x1`. The chain now ends when either dimension reaches one. Remaining coarse cells retain their conservative reversed-Z maximum; SSR already uses the actual mip count. Thin/portrait/ultrawide dimensions are tested.
- Status/GUI telemetry now reads CPU-owned counters only. It no longer runs CUDA collection from a screen draw, and its JNI boundary catches C++ exceptions. GPU collection remains in the guarded renderer path. This removes a process-termination hazard; it is not proof that the original crash was that hazard.
- Reference observations are scheduled at one sample per frame instead of four. Each diffuse/specular/transmission dispatch is divided into at most 8,192 pixels, with explicit global indexing for outputs and counters. The same target spp, eight-bounce BSDF/NEE/MIS and progressive average are retained. A smaller launch reduces long-kernel/watchdog exposure, but cannot guarantee that an arbitrary GPU workload will never time out.
- Reference disables probe and approximate caustic reuse, including previously filled caustic buffers. CPU/CUDA dispatch helpers are shared and tested for exact coverage, no unsigned sample-count underflow and finite completion.
- Startup and first reference dispatch now emit diagnostic markers. Caught failures report the stage before retaining raster fallback.

## In-game test

Select native **Vulkan**, open **VoxelLight → RTX → rt reference → on**. Wait for nearby section admission; keep the camera still. The sample counter should reach its target (one spp per rendered frame). Try 16 spp first, then 256. Verify narrow/normal/ultrawide windows no longer emit the `1x0` pyramid warning.

If the process still exits, provide the end of `logs/latest.log` after the new `RTX native initialization` / `RTX reference dispatch` markers and the launcher crash report or `hs_err_pid*.log` if generated. Windows GPU-driver/watchdog failures can terminate before a Java stack is printed, so those artifacts are necessary to distinguish launch failure, native memory faults and device loss.

Native compilation and CPU/shader tests are available here; NVIDIA execution, crash reproduction and driver recovery have not been verified on this GPU-less build host.
