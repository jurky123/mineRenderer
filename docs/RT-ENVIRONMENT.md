# Shared HDR RT environment

Vulkan migration alpha.12 also uses this exact shared HDR shader and palette. Its independent GPU fragment reductions construct cell/row CDFs in a packed Vulkan storage buffer; the Slang sampler uses the same cell solid angles and conditional proposal as `native/rt/environment.h`. Sun/moon/environment/held-light NEE and complementary miss MIS are now connected in `vulkan_pt`. This is still an experimental unreconstructed material path; runtime OptiX environment generation is not used by it. Actual cloud shadow transmittance at each surface, emitter NEE and performance gates remain pending.

The GPU renders a 256×128 RGBA32F lat-long environment using the same `environment.glsl` sky, horizon, sunset, weather, stars and voxel-cloud density functions and lighting palette as raster. Sun/moon disks are excluded: the directional light sampler owns their energy. A bounded 16-step cloud march with three sun-transmittance samples supplies environment cloud radiance. The map is copied directly to an exported interop buffer, with no CPU image readback. Realtime regenerates it with the frame's weather; reference freezes it with its guide snapshot.

RT miss, glossy/dielectric reflection, diffuse miss and probe update query this map. Environment NEE chooses rows/columns from GPU luminance-weighted CDFs. Cell weights use exact lat-long solid angle, equivalent to luminance × sin(theta) integration. Samples are uniform in cos(theta) within the chosen cell. PDF is cell probability divided by solid angle; an entirely black map falls back to uniform sphere. Environment light-selection power uses integrated map luminance. CPU tests check PDF normalization, poles, sample histograms and black-map fallback.

`/voxellight rt_environment on|off` compares the shared map against the legacy gradient. `rt_environment_map` and `rt_environment_distribution` are separately profiled.

Limits: fixed resolution/nearest lookup, unresolved small stars, approximate cloud march, no sun disk, no full atmospheric multiple scattering. Raster clouds may still use their plane fallback rather than voxel clouds; matching the cloud renderer selection is outstanding. No screenshot/GPU timing comparison has been captured. CPU PDF tests establish proposal correctness, not environment visual calibration.

Alpha.4 builds the environment row CDF and row reduction with an ordinary CUDA kernel, outside the OptiX compiler graph. The radiance map, weighting and sampling algorithms are unchanged.
