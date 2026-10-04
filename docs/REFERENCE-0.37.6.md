# Reference frame scheduling — 0.37.6

The 0.37.5 log confirms module compilation/linking completed in about 134 seconds.
The new complaint starts after reference dispatch. Choosing 4 instead of 256 spp
changed the convergence target, not frame cost: every display frame still queued
three full-image 8-bounce signals before releasing the CUDA→Vulkan semaphore.
Splitting that image into 8192-pixel launches did not bound aggregate frame work.
The no-optimization compiler policy also carries a possible execution penalty.

## Progressive image sweeps

Reference now dispatches only one shared pixel window per display frame for diffuse,
specular and transmission. It starts at 64 pixels; delayed GPU event timings adjust
that budget between 8 and 1024 pixels. Above 16ms for the three signal passes it
halves the budget; below 4ms it doubles. These are feedback thresholds, not a GPU
frame-time guarantee: cold calls, AS updates and scene complexity may cost more.
No full-image transport launch remains in the reference signal loop. A whole image
sweep advances one spp; after the selected target, reference transport dispatches
stop until a reset. Material equations, BSDF/PDF, NEE/MIS and eight-bounce quality
remain unchanged. Normal RTX Quality scheduling is unchanged by this fix.

Untouched pixels retain raster fallback through zero signal confidence / invalid
surface keys. Newly processed windows display their current reference estimate.
The four guide buffers are snapshotted on reset and preserved across window updates,
including RT-resolved first dielectric interfaces. This prevents fresh raster guides
from erasing previously resolved glass/water interfaces as the sweep progresses.
Camera, scene and configuration changes reset both cursor and accumulated samples.
Lighting/wave time and dynamic instance freezing begin with the first window rather
than waiting for the first whole-image sample.

The reference path skips full-image temporal-guide raygen and only reduces telemetry
for the current window. The extra persistent guides cost 64 bytes per RT pixel:
13,844,480 bytes at 640x338, inside the existing native 512MiB allocation cap.
CUDA external-memory ownership and binary semaphore handoff remain GPU-only.
Reference output is still acquired for display; this is bounded frame scheduling,
not a new fully asynchronous renderer.

## Progress and acceptance

`/voxellight status` includes:

- `rtReferenceSamples`: completed whole-image sample sweeps;
- `rtReferenceSweepPercent`: progress through the next sample sweep;
- `rtReferencePixelsPerFrame`: current adaptive pixel budget.

Install the matching 0.37.6 native kit. Start with 4 spp and hold the camera still.
The world/settings should render while the sweep percentage advances. Check that
one complete sweep increments samples, 4 samples stops dispatch, and a camera turn
resets progress. Then try 256 for screenshot convergence. More spp means more sweeps,
not a larger per-frame launch. Reference is intentionally progressive; completing
an image can take longer in exchange for smaller display-frame workloads.

All host BSDF tests still run, with new scheduler checks proving each pixel receives
exactly the target samples for odd/rectangular image sizes, target saturation,
reset semantics, and budget limits. Windows/Linux native builds pass. NVIDIA FPS,
first-sweep duration and visual convergence remain unmeasured on this GPU-less host.
