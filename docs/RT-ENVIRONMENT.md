# RT environment integration status

Current RT miss and environment NEE share `environment(direction)` in native transport, but it is still a sky-color gradient, not the raster sky/weather/voxel-cloud model. Sampling remains uniform sphere with matching density; this is **not** the requested environment importance sampler.

Opaque primary glossy NEE and BSDF miss now use complementary MIS weights. Directional sun is excluded from this primary estimator because raster owns primary sun/moon direct. Secondary paths retain sun sampling. The future shared HDR environment must exclude the separately sampled solar disk in both raster/RT reflection and environment NEE.

Pending implementation: shared HDR map generation/refresh, luminance × sin(theta) discrete distribution, solid-angle-consistent map PDF, seam/pole filtering, PDF/integral regressions, weather/cloud invalidation and night-source acceptance. GPU memory/ray budgets must be measured rather than inferred from the current gradient.
