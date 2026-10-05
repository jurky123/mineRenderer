# 0.38.0-alpha.8 — route resize through viewport lifetime

Alpha.7 GPU log: strict/default realtime initialization completed at 11:42:45, about 13 seconds after startup. Reference target 16 was set at 11:42:47 and explicitly enabled at 11:42:51; dispatch began at 214×120. At 11:42:53 the OptiX context closed and startup restarted at 640×338, completing at 11:43:03. The expected pipeline-retained resize message did not appear. This confirms that alpha.7 did not cover the actual context-destruction path. Cache database size remained zero with identical module keys missing again. No SPP/progress status or image was included, so convergence and visual correctness are not established.

Source audit found `GameRendererMixin` routing `resize`, `resetData`, `setLevel` and `close` to the same `RenderProbe.reset()`. That routine closed the lighting pass and native RTX context before `RtxLightingPass.trace()` could reach alpha.7's viewport-only release/import path. Alpha.8 separates the resize callback. It releases scratch/history/timing state and leaves size-aware pass owners to reallocate at their next prepare. World/data reset and shutdown retain full cleanup. A bytecode contract test verifies the distinct callback targets and guards against closing the lighting owner on resize.

Resource-pack/world generation invalidation can still recreate the native context; this change does not claim to fix disk-cache persistence or preserve accumulation through actual resource/world changes. Reference holds a scene/environment snapshot. After changing weather/time while testing a stationary reference, use `/voxellight rt_reference reset` to refresh the snapshot.

## Verification

Gradle build/clientKit, including Windows/Linux native bundle compatibility checks, passed with 254 tests and zero failures/errors/skips. Native code/artifacts are unchanged from alpha.7. No GPU is available on this host.

Replace alpha.7 with alpha.8. Wait until server resource-pack loading completes, then enable reference at 16 spp. Resize; expect `RTX viewport resized ...; compiled OptiX pipelines retained`, not another module compile. Keep the camera still and collect two `/voxellight status` outputs 15 seconds apart, plus a screenshot of the incorrect result. Check `rtReferenceSamples`, sweep progress and reset count. P0 visual/numerical, convergence and warm-cache gates remain open.
