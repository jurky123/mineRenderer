# Full-primary and asynchronous reference

`/voxellight rt_reference_full on` casts jittered OptiX camera rays through the inverse projection/view matrix. Primary visibility comes from the resident GAS/IAS scene, independently of raster depth. The same Material 3 lookup, BSDF, NEE and MIS are used for up to 12 transport steps. Radiance-cache substitution, caustic-cache injection and temporal denoising are disabled. Linear radiance accumulates before exposure/tone mapping. Final reference display runs after world raster rendering, so raster entities and translucent terrain cannot overwrite valid reference pixels. The hand/HUD remains the normal game UI.

Commands:

- `/voxellight rt_reference_full on|off`
- `/voxellight rt_reference_full scale 1|2|4` (resolution divisor; default 4)
- `/voxellight rt_reference spp 4..4096`
- `/voxellight rt_reference reset`

The existing `rt_reference` remains raster-primary for comparison. Both modes use progressive adaptive windows and completed sweep counts. Target SPP is not completed SPP. Hold the camera still; changes reset convergence. Uncomputed full-reference pixels retain raster until observed, and the low-resolution reference display uses nearest sampling.

## Asynchronous ownership

Display bank A consists of VoxelLight-owned Vulkan signal textures. Work bank B consists of exported Vulkan buffers imported by CUDA. Vulkan signals readiness; CUDA waits and computes a bounded window, signals done, then records a completion event. The render thread polls `cuEventQuery`; when incomplete it uses the last display bank and submits no wait for unfinished CUDA work. When complete it acquires the done semaphore and copies the work bank into the display bank. There is at most one outstanding window. Guide snapshots and the HDR environment remain CUDA-owned during a reference sweep. A camera mismatch rejects stale display. Reset/close during work defers native destruction and external-resource retirement until CUDA finishes.

This is two-bank ownership, not two simultaneously writable interop slots. GPU compute still competes with raster for device time. No GPU responsiveness, semaphore-lifetime or visual acceptance is claimed on the Linux build host without NVIDIA hardware.

Full-resolution reference allocates no temporal denoiser/history and uses one accumulation image. Exported resources have a 768 MiB cap; CUDA-owned reference allocations have a 1536 MiB cap. High resolutions may be rejected by these budgets. RTX realtime retains its 512 MiB CUDA-owned budget.

## Medium reference and limitations

Reference uses RGB-mixture exponential free flight with explicit HG scattering events, colored transmittance and light NEE/MIS. Realtime uses a finite-segment truncated exponential single-scattering estimator. The medium stack is shared with surface refraction.

The full reference is a transport/coverage comparison within the current captured scene, not an exhaustive world renderer: resident section radius/count, captured dynamic animation, texture resolution, straight-line dielectric visibility and layered/microfacet approximations still apply. Atmospheric sky is an environment boundary, not full volume transport. No reference layered random walk has been implemented yet. Do not label its results absolute ground truth for those approximations.
