# 0.38.0-alpha.7 — reference convergence and viewport lifetime

Alpha.6 user GPU evidence (591.74, RTX 4060 Laptop, strict PTX/default): first initialization completed in about 16 seconds. Diffuse driver graph fell from alpha.5's 62,994 instructions to 8,342; all modules linked successfully. The cold <30 s gate passes for this run. A resize from 214×120 to 640×338 recreated the context and took another roughly 9 seconds. Identical module keys missed the SDK disk cache, whose logged size stayed zero. Warm-cache acceptance and rendering correctness remain open.

Alpha.7 retains the compiled OptiX context, SBT, world GAS/IAS and probe allocations on viewport resize. It synchronizes completed CUDA work, releases external imports/semaphores and viewport/denoiser allocations before closing Vulkan allocations, then imports new buffers. An unfinished asynchronous reference window is polled before resize. World/resource generation changes still rebuild the context. The SDK disk cache persistence problem is **not** claimed fixed.

`/voxellight rt_reference spp N` sets the reference convergence target. Realtime RTX continues at one observation per pixel per frame and uses its temporal denoiser/cache. SPP now sends explicit feedback. Changing a positive target retains the existing sum and sweep; camera/settings/reset changes still invalidate it. Increasing the target resumes accumulation; decreasing below completed SPP stops new sampling without changing the existing estimate. This is not N samples in one frame.

Reference stays unfiltered for numerical comparison. Its one-SPP sweeps now allow adaptive windows up to 8192 pixels (previously 1024), while retaining the 4–16 ms measured signal-time control and 8-pixel minimum. This removes the hard 1024-pixel throughput ceiling; actual speed still depends on GPU transport time and alternating dispatch/display frames. It does not guarantee realtime convergence. `rtReferenceSamples` counts completed whole-image sweeps; `rtReferenceSweepPercent` shows the current sweep; `rtReferenceResets` helps detect camera/configuration invalidation. `rtRealtimeSpp=1` makes the mode distinction explicit.

## Host verification

Windows/Linux native libraries and all 41 RT artifacts built; Gradle build/client kit passed with 253 tests, zero failures/errors/skips. Python report tests: 3 passed. No GPU/display is available on this host.

## GPU check

1. Replace alpha.6 with alpha.7. Start RTX Quality, resize once; logs should say `compiled OptiX pipelines retained` without another module compilation.
2. Run `/voxellight rt_reference spp 16`, then `/voxellight rt_reference on`. Hold camera still. Collect `/voxellight status` twice about 15 seconds apart. Confirm completed SPP/sweep advances and reset count stays stable.
3. Raise the target to 32; completed SPP must be retained. Lower it below completed SPP; image brightness must not jump. Explicit reset/camera movement should restart it.
4. Compare reference and realtime with a fixed view; report specific black patches, brightness/color, glass/reflection or edge errors and attach the view plus full status/log. Compilation success and these host checks do not prove the callable GPU result is correct.

P0 rendering/numerical and warm-cache acceptance stay open; P1–P7 remain deferred.
