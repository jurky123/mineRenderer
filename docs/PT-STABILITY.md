# 0.30.3 — Persistent indirect-light stability

This implements the review's stability work, without replacing the Vulkan raster primary renderer or adding RT-core traversal. CUDA traces secondary diffuse light; OptiX remains a spatial HDR denoiser, now operating on persistent, temporally filtered radiance. GPU quality/FPS acceptance remains pending.

## Fixes and verified findings

| Review problem | Resolution |
| --- | --- |
| Camera motion resets a global progressive image | Each batch supplies eight new samples; a persistent per-pixel EMA carries valid world-surface history through camera reprojection. A global serial only seeds RNG, not radiance normalization or history confidence. |
| Latest asynchronous image directly replaces GI | Native history ping-pongs radiance and luminance moments, validates old position/normal, clamps history against compatible current neighbors plus variance, and blends with alpha >= 0.125. Confidence is per pixel, capped at 32 observations; disocclusion takes the new observation directly. OptiX denoises this persistent field before upload. |
| Proxy origin changes every section | The 80³ origin has a 16-block inner-region hysteresis. A proxy-origin change affects the trace scene but does not itself invalidate unchanged surface history. Local material changes, unloads and world/resource generations invalidate it. This is the requested short-term hysteresis option, not a full rolling multi-level clipmap. |
| Global 8-block reprojection cutoff | Removed. World/scene lifetime and per-pixel projected position, normal, plane/depth-distance and screen bounds determine reuse. |
| 8-ULP dual-depth gate | PT capture/composite use view-space depth tolerance max(0.01 blocks, 0.001*view depth). Native SceneDepth drives primary reconstruction. Unsupported/cutout/entity pixels remain excluded. Other raster/AO diagnostic gates retain their existing contracts. |
| Albedo used encoded sRGB | Primary capture decodes exact piecewise sRGB to linear reflectance before CUDA throughput and OptiX albedo guides. Secondary map colors now use the same piecewise transfer instead of approximate gamma 2.2. |
| Raster/PT emissive direct-light overlap | Raster owns first-hit emitter direct illumination. CUDA adds emitter radiance only after another diffuse hit; sun-lit secondary surfaces and secondary sky still contribute indirect illumination. |
| Sun/weather bins reset GI | They no longer clear persistent history. Changing illumination supplies fresh observations to the bounded EMA. Sun/moon source transitions still invalidate conservatively. Use existing `/voxellight sun fixed` for the requested fixed-light A/B. |
| Upload buffer freed too early? | Checked the actual Minecraft 26.2 Vulkan bytecode: writeToTexture invokes uploadStaging; its staging allocation path calls MemoryUtil.memCopy before returning. Caller bytes are copied into renderer-owned staging, so immediate release is safe for this pinned backend. A bounded delayed-release comparison switch is included. |

History operates on the worker's capture cameras, at up to 10 observations per second. It is not a per-display-frame temporal denoiser. Static views use a bounded EMA too; there is no 4096-sample rendering stop. Fixed eight-sample batches replace the old reset/global-average architecture. Neighbor clamping is a reference filter, not a claim of complete variance-guided RT GI. Dynamic secondary geometry, textured/partial secondary shapes, OptiX's temporal model/flow, zero-copy interop and rolling clipmaps remain separate future work.

## Diagnostics / test

Start with `/voxellight pathtrace on`, walk slowly, rotate and cross section boundaries. Test a sunlit colored wall beside a white shaded wall and a broad glowstone-lit room. Inspect `pathtraceWorkerBatches`, `pathtraceAccepted`, `pathtraceRejected`, `pathtraceHistory` and worker time. There is no global per-pixel sample count in the status now.

- `/voxellight pathtrace_freeze on`: stop new submissions and discard in-flight observations, retain the last image with raster/reprojection continuing. If frozen GI stops flickering, compare fresh-batch/denoising/history rather than assuming a framebuffer race. Restore `off` to resume.
- `/voxellight pathtrace_history off`: disable native persistent EMA, showing independent eight-sample batches through the same OptiX/composite path. Default `on`; toggling clears the history/resources for a clean A/B.
- `/voxellight pathtrace_debug on`: indirect-only view.
- `/voxellight pathtrace_rejection on`: **green** accepted, **red** no supported material, **orange** dual-depth mismatch, **yellow** no valid scene/history, **blue** previous-camera offscreen/behind camera, **magenta** normal mismatch, **cyan** missing guide/position-depth rejection. There is no camera-distance rejection category because that global cutoff was removed. Restore `off` to return to normal composition.
- `/voxellight pathtrace_denoise off/on`: compare raw temporally filtered radiance with OptiX HDR output. To isolate spatial denoiser alone, also turn `pathtrace_history off`.
- `/voxellight pathtrace_upload_delay on/off`: retain source buffers for up to eight render frames, capped at four pending results. Native staging-copy verification means this is a diagnostic, not a required GPU fence. Default `off`.
- `/voxellight sun fixed`, then `world`: celestial A/B using the existing directional-light control.

Freeze does not make newly revealed surfaces acquire valid history. Unsupported surfaces and disocclusions may retain raster-only lighting until the next observation. Edits, F3+T, resize, teleport and dimension changes must not reuse incompatible histories. Check color bleed without first-hit emitter brightening, and test source-buffer delay for image parity.

## Budgets / verification

Capture stays <= 640x360; one capture/worker job in flight and a 10 Hz submission cap. New native history adds six float4 images (two radiance, two moments, previous position and normal): about 21.1 MiB at the maximum resolution. The denoiser state/scratch cap remains 192 MiB. Material fingerprint bookkeeping is bounded to the current proxy window. Delayed CPU upload sources cap at four results (42.2 MiB at maximum capture size) and are released on reset/disable.

Tests compile the actual Vulkan shaders/bindings, CUDA PTX and Windows/Linux native libraries. Native CPU tests run the same CUDA traversal/temporal functions and cover emitter ownership, blend-versus-replace, normal/position rejection, and disabled history. Java tests cover camera admission, content/version independence, origin hysteresis, cross-origin history lifetime and linear reflectance. There is no NVIDIA GPU on the build host, so motion, residual flicker, ghosting and cost need in-game verification.

## 0.30.4 — Residual motion flicker

User reports reduced flicker frequency in 0.30.3, with some remaining motion flashes. This follow-up replaces nearest-texel native history admission with a bilinear footprint gather of compatible surfaces. Radial-distance rejection is removed from native/composite lookup: tangential movement changes radial distance on the same plane, especially at grazing angles. Plane/normal/proximity/screen checks remain. If the composite's primary four samples are invalid, a bounded 4x4 compatible-plane gather fills sparse capture holes; genuinely disoccluded surfaces still reject.

History clamping now includes the new observation's luminance innovation in its variance allowance, so one noisy eight-sample dark batch cannot force stable history to near-black. Neighbor bounds exclude parallel surfaces on a different plane. No new GPU images or larger tracing budget. Native CPU tests exercise grazing reprojection, valid-neighbor recovery and the dark-observation case. Test slow rotation along a wall and walking at shallow angles; residual flicker/ghosting acceptance remains pending.

## 0.30.5 — Freeze lifetime and denoised-output stability

User reports residual flicker and that freeze looks like PT is disabled. The display used the same material-revision admission as accumulation: a proxy update could therefore erase the displayed result, while freeze discarded incoming results with no valid image to retain. Display admission now uses world/resource lifetime and resolution, leaving surface validity to guide reprojection. Material/source updates still reject native accumulation, but the old displayed observation remains until replacement. World/resource changes explicitly reset all PT state. A retained observation can have stale indirect lighting briefly after an edit, but changed primary surfaces still undergo plane/normal/proximity checks.

Freeze now accepts a first valid observation if none exists, then stops submissions/replacement. Status reports `freeze pending first valid observation` or `frozen valid observation`, plus `pathtraceDisplayValid`. It freezes an observation, not a full world-space light field: unseen surfaces still have no GI, and moving beyond the captured view can reject it. For the diagnostic, let GI become visible, freeze, and first keep the same view.

A second validated temporal pass filters the actual OptiX HDR output against the preceding displayed field before upload. Input EMA alone did not constrain spatial denoiser output changes. This is our own guide-validated EMA, not OptiX temporal-mode denoising. Raw mode remains input-history only. This adds two low-resolution float4 images (~7.0 MiB maximum) and one CUDA temporal kernel; sample count remains eight per batch. No full-resolution images added. Regression coverage includes display lifetime versus accumulation invalidation; 210 Java tests and native builds pass. Motion, ghosting and freeze visibility require GPU testing.

## 0.30.6 — Continuous display-time batch transitions

Freeze is user-confirmed to retain GI, but resumed updates still flicker. Worker-side history filtering did not prevent the final asynchronous texture swap from changing every displayed pixel at once. The Vulkan composite now retains two complete radiance/position/normal observations, reprojects and validates each against the current surface, and interpolates over 150 ms with smoothstep. It retains a compatible old observation when the new one lacks coverage; newly observed surfaces fade in. World/resource/resolution resets invalidate both. Freeze holds the exact transition weight, and resume continues it without a jump.

A new trace is admitted after the current display transition finishes, with the existing 10 Hz maximum and one-worker-job cap. This lowers observation cadence to at most about 6.7 Hz (less when staging/tracing takes time); it trades responsiveness for continuity, rather than claiming the tracer itself is noise-free. Geometry validation still rejects unrelated surfaces, so moving into uncaptured areas can remain raster-only. No RGB history: raster direct light and UI remain current.

Additional resources: three low-resolution RGBA32F images, at most 10.55 MiB; no new full-resolution image or CUDA allocation. The composite samples both observations during transitions, so its GPU cost can rise; actual timings need in-game measurement. Status includes `pathtraceBatchBlend`, `pathtraceHistoryResets` and `pathtraceHistoryResetReason` to distinguish content-driven worker resets from display transitions. 212 Java tests cover timing, bounded transition completion and freeze/resume continuity, alongside actual shader/binding compilation. GPU motion, residual flicker and ghosting still require verification.
