# Basic terrain ambient occlusion

0.17.0 adds a bounded **GTAO-inspired horizon AO reference**, not a full XeGTAO port or a ground-truth quality claim. The architectural reference is [Intel's XeGTAO explanation](https://github.com/GameTechDev/XeGTAO): horizon slices, nearby-sample falloff, depth-aware spatial filtering and the limitations of representing geometry with screen depth. VoxelLight uses its own normalized slice integral, fixed sampling and native raster pass owners; no third-party shader implementation is bundled.

Inputs are current native reversed-Z SceneDepth and depth-validated material normal/albedo metadata. Receiver and occluder support is opaque terrain inside the existing material window. Cutout, unshaded and ENTITY flags stay neutral. No texture/color is accumulated.

Pipeline:

1. Two full pixels per dimension become one half-resolution sample, rounding up. Its guide is the fixed(1,1) texel of the2×2 group, clamped for odd final groups. Verify scene/material depth within8 ULP and supported normal/material before searching.
2. Unproject using the captured actual inverse projection; transform real world normals into view coordinates. Search four slice directions with four quadratic-spaced steps per side, radius1.5 world blocks, screen radius capped80 full-resolution pixels. Coplanar samples below.035-block normal-plane bias do not occlude. Smooth distance falloff, normalized cosine-weighted slice visibility and.35 floor provide a conservative reference.
3. A5×5 bilateral spatial filter accepts matching normals and tangent planes within the radius. It preserves the representative guide's oct normal and linear view depth.
4. Lighting uses four depth/normal/tangent-plane weighted guides to upsample. No matching guide means AO=1. Strength.85 is applied to visibility; this gives a minimum applied factor.4475.
5. Multiply minimum ambient, hemisphere sky and the remaining unshadowed native block-light fill by AO. Do not multiply selected direct local lamps, direct sun/moon or emission. HDR/tone/native fog run once. Existing directional visibility temporal history remains independent.

Two half-resolutionRGBA16F targets storeR=visibility,GB=oct view normal,A=linear view depth. Invalid guides haveA=-1. Size is16×ceil(width/2)×ceil(height/2) bytes, capped32 MiB;1440p14.0625 MiB and4K31.640625 MiB. A16-byte neutral target and32-byte uniform are additional. Budget fallback keeps normal foundation lighting; no SceneColor copy or native-depth write. Enabling/disabling, resize and world/resource/mode reset follow existing owned-resource lifecycle. No AO history, jitter, depth mips, bent normals, multibounce or moving-entity AO are included.

`/voxellight ao view` enters foundation and displays the applied factor directly, bypassing tone/fog for supported pixels. Open surfaces are white and contact regions gray. Unsupported pixels/sky retain their native image; cutout/entity receivers are white. `ao on` restores normal lighting, `ao off` compares the baseline. View mode's direct-light temporal correction is zero and settings changes invalidate directional history.

Acceptance: compare wall/floor corners, stairs, raised blocks, tree roots, torch-lit caves and building interiors. Check flat planes remain neutral, silhouettes have no broad dark halos, emissive pixels/direct-lit terms are preserved, plants remain stable, and reload/edit/teleport/FOV/odd resize/dimension changes stay correct. Half-resolution/subpixel and screen-edge/offscreen omissions are known representation limits. A separate game screenshot/performance test is required; native shader compilation and CPU numerical-integral tests cannot establish final pixel quality or frame cost.
