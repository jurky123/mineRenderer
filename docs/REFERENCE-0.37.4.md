# Reference texture transfer and compiler cost — 0.37.4

The user confirmed background startup stays responsive. The new log measures
about 37 minutes 28 seconds from initial startup to the first dispatch, followed
by `Invalid mipLevel` in the RT output copy. These are separate problems.

## Correct GPU buffer-to-texture copies

MC 26.2 requires source x/y/row width/height, destination x/y/copy width/height,
then mip/layer. Previous calls supplied width as mip and zero source dimensions.
All nine signal/guide transfers now share a correctly ordered level-zero copy.
The regression invokes the actual Minecraft CommandEncoder validation, checks
the backend arguments for a 640x338 RGBA32F surface and reproduces rejection of
the old call. Transfers remain GPU-only; no CPU image staging is introduced.

## Bound compiler expansion

Large light-visibility, direct-estimation, integrator, BSDF evaluation and BSDF
sampling functions are no longer forced inline on CUDA. Small vector operations
remain inline. OptiX module optimization changes from level 3 to level 2.
Transport formulas, sample counts and reference quality are unchanged. Function
calls and reduced optimization may change runtime performance; GPU measurements
are still required. Background startup and stage diagnostics remain enabled.

NVIDIA documents that aggressive inlining can increase compilation time and
supports non-inlined device functions: [OptiX guide](https://raytracing-docs.nvidia.com/optix9/guide/index.html).
The old packaged transport PTX was 4,077,162 bytes; the new artifact size is
recorded in CURRENT.md. PTX size measures compiler input, not driver startup time.

## Check

Install the complete matching 0.37.4 native kit. Enable reference, wait for
`RTX initialization complete`, then inspect `/voxellight status` while holding
the camera still. `rtReferenceSamples` should increase toward the selected target,
without the invalid-mip exception. Compare first-start compilation duration and
then restart once to check driver-cache behavior. Resource reload can still
abandon startup; avoid reloading packs during this first comparison.

This host has no NVIDIA GPU/display. Java API validation, BSDF Monte Carlo tests,
and Windows/Linux native compilation do not establish driver compilation time,
reference convergence or GPU performance.
