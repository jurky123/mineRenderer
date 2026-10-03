# Experimental hybrid diffuse path tracing

Current: **0.30.6**. [Persistent history, fixes, diagnostics and motion tests](PT-STABILITY.md).

This is a runnable raster-primary prototype, with CUDA voxel traversal and real OptiX 9.1 HDR denoising. It is **not full primary-ray path tracing**, and its traversal does not yet use OptiX acceleration structures / RT cores. Existing Vulkan primary visibility, direct lighting, water and UI remain in place. The traced contribution is added in linear HDR before atmosphere, water-background capture, bloom and tone mapping.

## In-game test

Use the native-enabled 0.30 kit, Minecraft 26.2, native Vulkan and an NVIDIA GPU with an OptiX 9.1-compatible driver. The previously reported RTX 4060 / 591.74 configuration is the intended test machine. No separate CUDA toolkit installation is needed to run this kit.

1. `/voxellight pathtrace on` selects foundation and enables the optional tracer. Wait for scene tracking, then test both stationary views and slow camera movement.
2. `/voxellight status`: look for `pathtrace=diffuse hybrid active`, `pathtraceDenoise=OptiX HDR`, and increasing `pathtraceWorkerBatches` and accepted results. An explicit `unavailable; raster retained` message means initialization failed; report that message and the log. A successful mod load does not prove OptiX is running.
3. Test a white wall next to a sunlit red/green wall, an overhang beside sunlit ground, and a small room with a broad glowstone source. Compare `/voxellight pathtrace off` and `on` from the same stationary view. Look for indirect light and color bleed in shaded surfaces.
4. `/voxellight pathtrace_debug on` shows the **indirect contribution only** on supported nearby terrain. Black in open empty space is expected: primary sky lighting is already provided by raster and is not duplicated. Restore `off` afterward.
5. `/voxellight pathtrace_denoise off` exposes temporally filtered raw samples; `on` uses the actual OptiX HDR denoiser with primary albedo and camera-space normal guides.
6. Move/rotate, place/break a block, teleport, F3+T, resize, switch dimension, and disable/re-enable. Old camera/world results must not appear over the new view. 0.30.3 accumulates per-surface history before denoising and reprojects valid surfaces during motion; newly exposed surfaces can still fall back until the next batch arrives.

## What is traced

- Exact raster primary position, normal and linear albedo come from the material GBuffer. Cutout, animated and entity primary pixels are excluded.
- Up to three cosine-weighted diffuse secondary segments, voxel DDA intersections, emissive hits after another diffuse surface and sun next-event visibility. Secondary sky illumination is accumulated only after a secondary surface hit.
- Eight new samples per observation, at most 10 submissions/second, one capture/trace job in flight; new jobs wait for the 150 ms display transition to finish. Persistent per-pixel EMA/confidence/moments replaces global progressive averaging. World/resource, dimensions, actual local material changes and sun/moon source changes invalidate history; camera motion, proxy-origin shifts and sun/weather bins do not globally reset it. No 4096-sample stop.
- Secondary terrain is an 80³ proxy of immutable section snapshots. Full opaque blocks and emissive blocks are cubes; secondary albedo uses linearized block map colors, not atlas textures. Thin/partial non-emissive shapes, foliage, dynamic entities and water are not secondary occluders. Missing sections terminate rays rather than behaving as air; exiting the bounded proxy uses approximate sky.
- Indirect shading fades from 16 to 24 blocks. This limits **experimental GI only**; native material/direct-light coverage stays at Minecraft's visible-scene range. Emissive lighting has no importance sampling yet, so small torches can converge slowly. This is not a replacement for existing direct local lights.

## Resource and execution contract

CUDA selects the device whose UUID exactly matches the active Vulkan physical device. The native library loads only on explicit enable. Windows x64 DLL, Linux x64 SO, PTX and third-party notices are bundled in the kit. Unsupported platforms/drivers or native errors retain raster lighting and produce status/log diagnostics.

The first bridge deliberately uses staging: three asynchronous Vulkan float4 guide readbacks, one worker-side CUDA batch, OptiX denoising, then a Vulkan upload. There are no CUDA waits or blocking GPU readbacks on the render thread. Secondary-scene encoding also runs on the worker from owned immutable snapshots, cached by scene key; it does not query the live world. Block map colors are read from immutable registered states with an empty block getter. Known snapshots remain until the scene bridge replaces them; local material fingerprints ignore LIGHT/task revisions and snapshot palette order.

Capture resolution is at most 640×360 and at most quarter width/height of the main frame. At that maximum, each float4 image is 3.52 MiB. There are three capture images/readbacks, six resident radiance/guide images (two observations), owned CPU staging, a 1.95 MiB voxel proxy, and one full-resolution RGBA16F composite (28.1 MiB at 1440p). Native images including input and denoised-output history add about 52.7 MiB, plus OptiX state/scratch capped at 192 MiB. Resources are released/reset on disable, world/resource reset and resize; submitted readbacks retain their resources until completion. Results carry generation/camera/scene keys before upload. Normal/depth-guided upsampling avoids spreading low-resolution bounce light across unrelated surfaces.

This staging prototype is **not a performance mode**. No GPU quality, convergence, denoiser or FPS result has been measured on the build host, which has no NVIDIA GPU. Native shader compilation, CPU traversal/sampling, JNI loading/failure, actual Vulkan shader/binding tests and packaging are checked. In-game acceptance is pending.

## Reproduce the native build

`tools/build_optix.py --deps /path/to/dependencies` expects extracted dependencies in the layout documented by the script: `optix-dev-*/include`, `cuda/nvidia/cuda_runtime/include`, `cuda/nvidia/cuda_nvrtc/lib`, and `llvm-mingw-*/bin`. It builds Linux and cross-builds Windows; local JDK 25 and g++ are required. NVRTC compiles PTX for compute_75 without a GPU.

Pinned release inputs:

- [NVIDIA optix-dev v9.1.0](https://github.com/NVIDIA/optix-dev/tree/f1f6dd803f3159992d248178f6e09421c6eb8b6d), SHA `f1f6dd803f3159992d248178f6e09421c6eb8b6d`.
- NVIDIA `nvidia-cuda-runtime-cu12==12.9.79`, `nvidia-cuda-nvrtc-cu12==12.9.86` Linux x64 wheels, extracted under `cuda/`.
- [llvm-mingw 20260922](https://github.com/mstorsjo/llvm-mingw/releases/tag/20260922), `ucrt-ubuntu-22.04-x86_64` archive.

Set `LD_LIBRARY_PATH` to the NVRTC library directory, run the native build, then `./gradlew build clientKit -PnativeKit`. `build/optix-native` is ignored; SDK headers and compiled binaries are not committed. The JAR embeds the compiled optional component and its notices. Source hashes reject stale native output during packaging. Without that output, a regular Java build remains usable as the raster mod and reports the optional component missing when enabled.

Native CPU checks: `g++ -std=c++17 -O2 native/optix/test_paths.cpp -o /tmp/voxellight-test-paths` and run that executable. It exercises the same traversal/sampling code compiled into PTX.

API references: [OptiX denoiser](https://raytracing-docs.nvidia.com/optix9/api/group__optix__host__api__denoiser.html), [CUDA/Vulkan device matching and interop](https://docs.nvidia.com/cuda/cuda-programming-guide/04-special-topics/graphics-interop.html). Exportable Vulkan/CUDA memory, OptiX GAS/IAS ray traversal, emissive importance sampling, dynamic secondary geometry and temporal reprojection are subsequent steps, not implemented claims of this version.

0.30.1 stability fix: accumulation uses order-independent local material content, tolerates tiny floating-point camera noise and waits 250 ms for a stationary view. Genuine movement/edits still reject stale results. Sun/weather reseeds keep the last surface-valid image until replacement; an eight-sample ramp reduces initial pop-in. Check that stationary `pathtraceSamples` now increases instead of repeatedly returning zero.

## 0.30.2 — Motion reprojection

The earlier stationary-preview behavior caused GI to drop to zero during movement and an eight-sample strength ramp brightened it after stopping. That behavior is removed. Tracing continues during motion (one job in flight, at most 10 batches/second); each reset batch traces eight samples before denoising. Sample count controls convergence, not brightness.

Composition reprojects current world positions into the captured camera, adds the camera-position delta to the old guides, and rejects offscreen, depth-distance or normal mismatches. Valid previously visible surfaces retain GI while fresh camera batches arrive. Genuine scene/world/resource changes, section-window changes and camera cuts still reject the old buffer; newly exposed or out-of-history surfaces can temporarily have no GI. This is a first motion-reuse implementation, not complete temporal GI or world-space caching. In-game stability/performance pending.
