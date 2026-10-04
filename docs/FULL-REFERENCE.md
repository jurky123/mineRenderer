# Reference modes and remaining full-primary work

The existing `/voxellight rt_reference on` is **raster-primary**. It progressively traces separated lighting at bounded resolution, uses full material transport, disables radiance-cache substitution, and accumulates complete sweeps toward `/voxellight rt_reference spp 4..4096`. It is useful for lighting comparison but is not ground truth for primary coverage/reconstruction.

`rt_reference_full` is not implemented in this alpha. No command placeholder is exposed as a working renderer. Required work: OptiX camera rays independent of raster visibility, shared material/scene/NEE/MIS, configurable resolution, 8+ bounce transport, raw progressive output without cache/denoiser, stable scene/camera reset and independent asynchronous output banks.

Current bounded reference windows still use the Vulkan/CUDA semaphore round trip in each display frame. The adaptive pixel budget reduces work but does not guarantee responsiveness for a slow individual ray. True asynchronous reference requires separate ownership of guide/output banks and nonblocking completion checks; simply omitting the semaphore wait would be incorrect.

GPU acceptance remains pending. Record completed sweep count, camera/scene revision, target SPP, pixel budget and raw screenshot; a selected target of 4 SPP is not proof that 4 complete sweeps have finished.
