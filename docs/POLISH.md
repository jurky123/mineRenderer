# 0.18 lighting/color polish

This phase changes supported `foundation` surfaces. It preserves the separated material/direct/ambient/emission inputs and bounded shadow/local-light systems. All effects still start off until `/voxellight mode foundation`.

## Compare

```
/voxellight mode foundation
/voxellight look reference
/voxellight look polished
```

`polished` is the foundation default: manual exposure + rational filmic tone curve, a continuous day/dusk/night hemisphere sky palette, emission-only bloom, and a camera-distance coverage blend. `reference` restores the0.17 sky/direct intensities and Reinhard output, bypassing exposure, bloom and distance blending; AO and directional-history switches remain independent. Existing terrain/entity/material diagnostics remain unchanged.

- `/voxellight exposure 0.75`: default EV; supported range -2..2. This is manual exposure, with no histogram/readback, temporal exposure or adaptation pumping.
- `/voxellight bloom on|off`: quarter-resolution emission bloom, defaulton with polished look.
- `/voxellight coverage_blend on|off`: defaulton with polished look. Turn off when comparing tone alone or checking the outer shadow receiver range.
- `/voxellight ao view`: bypasses tone, exposure, bloom, distance blending and fog in the displayed AO diagnostic. `/voxellight ao on` restores normal output.

## Lighting and output

The sky hemisphere interpolates between horizon and zenith colors using the actual geometry normal, preserving the accepted two-sided foliage policy. Clear days have cooler zenith light; low sun warms the horizon; night stays dim and cool. Rain attenuates/desaturates sky lighting. The existing celestial source, horizon visibility, rain attenuation and moon-phase policy still determine direct-light admission; no moonlight is invented at new moon. Non-overworld dimensions retain the reference policy.

The filmic curve is a rational toe/shoulder normalized at linear white6, with default +0.75EV. It is an artistic reference curve, not a claim of ACES or AgX compliance. Linear emission bloom is added to HDR before exposure/tone mapping; display encoding and native fog occur once. No native already-lit SceneColor is used as material input. Unsupported pixels keep native rendering.

Bloom area-averages the supported material emission over4×4 full-resolution pixels, checking private/native depth within8 positive reversed-Z float ULPs. This avoids losing small torches to a single center sample. Two13-tap separable Gaussian passes run at quarter resolution; two independentRGBA16F targets avoid read/write feedback. No brightness threshold is applied to non-emissive sky/white textures, and no bloom history is added. The terrain emission image is captured once before late entity material capture overwrites the GBuffer; late entity lighting reuses that bloom image and does not accumulate bloom a second time.

Initial bloom is limited to captured terrain emission such as torches and glowstone, and to supported foundation receiver pixels. Fluids/lava, translucent/custom surfaces, block-entity materials and sky halos remain native; dynamic entity emission bloom is not added in this phase. Bloom is an optical glow, not bounced light or a replacement for local-light occlusion.

The material store remains5³ sections. At any camera position within its center section, its closest cube face is at least32 blocks away. Smoothly blending supported surfaces back to native color between24 and32 blocks hides the hard outer window boundary, at the cost of a smaller fully lit radius. This does not enlarge material residency or change shadow-map receiver/caster controls. Native-target RGB blending occurs after display encoding and fog and preserves the target alpha. It is a compatibility transition, not linear HDR compositing. Unsupported holes and initial one-section-per-frame admission can still remain visible; there is no claim of complete native terrain stream coverage.

## Ownership, budgets and acceptance

LightingResolvePass owns the visual controls and32-byte tone/fade UBO. LightingEnvironment uploads48 bytes for direct, zenith and horizon/lower-hemisphere data. EmissiveBloom owns two quarter-resolutionRGBA16F images,16-byte blur settings and a16-byte neutral black texture. Extra bloom target cap16MiB:1440p about3.52MiB;4K about7.91MiB; larger targets fall back to no bloom independently. Resize/off/world/resource lifetimes close the owners through the existing foundation reset paths. No extra full-resolution color copy or geometry compile.

Automated checks cover tone monotonicity/bounds, EV validation, sky/weather/dimension continuity, guaranteed fade bounds, odd target sizes/memory caps, explicit render areas, output blend state, native shader compilation and actual pipeline uniform/sampler/stage bindings. This host has no GPU/display: runtime visual acceptance remains pending.

Please compare a sunlit white wall and adjacent shadow, sunrise/sunset faces, a torch or glowstone in a dark room, and movement through the material window. Check slow moving animals/plants, resize, F3+T and dimension changes. Check `look reference` remains usable and AO diagnostic is unchanged. Water/atmosphere and local-light material improvements remain subsequent phases; GI and large performance changes stay deferred.
