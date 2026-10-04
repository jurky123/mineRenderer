# Reference startup responsiveness — 0.37.3

The supplied 0.37.2 log ends at `RTX native initialization`, before the first
reference dispatch. Native startup performed CUDA device/context initialization,
OptiX module compilation, program-group creation and pipeline linking synchronously
on Minecraft's render thread. A slow compilation therefore prevented frame
presentation and input handling. The log alone cannot prove whether the driver
compiler eventually completes or hangs internally.

0.37.3 moves native library loading and OptiX context/module/pipeline creation to
one daemon startup worker. Vulkan resources and shader compilation remain on the
render thread, after successful native startup. Raster lighting continues until
startup finishes. No partially initialized native handle is published. Failed
startup reports an exception and retains raster rendering. Disabling RTX during
startup abandons the result; any late handle is destroyed on the worker rather
than adopted or leaked. Startup is not forcibly interrupted inside NVIDIA APIs.

Settings/status show the current startup stage and elapsed seconds. Logs distinguish
CUDA matching, OptiX context creation, transport-module compilation, program groups,
pipeline linking, stack configuration, external-resource imports and Vulkan shaders.
No external semaphore wait is issued while native startup is pending.

## In-game check

Replace the old mod with the complete **0.37.3 native kit** (Java and native artifacts
must match). Open `/voxellight settings`, enable reference and start with 16 spp.
During first startup, the world and menus should remain responsive while status
shows compilation/linking. Wait for `RTX initialization complete; preparing scene`
and the first reference dispatch. Try disabling/re-enabling RTX during startup.

If progress stays at one stage, send the final `RTX` log lines and the elapsed time.
If the whole game still freezes, the last newly reported stage distinguishes native
startup from Vulkan import/shader work and reference dispatch.

Host regression tests cover nonblocking startup, cancellation before completion,
exactly-once disposal, adoption and recoverable initialization errors. This host has
no NVIDIA GPU/display; NVIDIA compiler timing and in-game responsiveness require
user acceptance. Reference still has substantial GPU cost after startup; this
change addresses the observed initialization boundary, not every possible driver
or GPU workload stall.
