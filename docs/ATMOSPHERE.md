# 0.20 analytic aerial perspective

The first atmosphere increment adds a bounded analytic participating-medium approximation to supported foundation surfaces. It is not a shadow-marched volumetric renderer, and it does not implement water, sky/cloud replacement or GI.

## Compare

```
/voxellight mode foundation
/voxellight atmosphere on
/voxellight atmosphere off
```

Atmosphere is on by default in polished foundation, disabled by `look reference`. Default density is0.001 per block (updated user preference,0.23.0); `/voxellight atmosphere_density 0.03` gives a stronger comparison, with accepted0..0.08 range (0 disables visible haze). Try an outdoor tree line or building10–24 blocks away, look toward the low sun, and compare rain/clear weather. Startup effects are still off until foundation is selected. Status reports the atmosphere admission and density.

## Composition and limits

A four-point height-density quadrature estimates optical depth along the camera-to-surface ray. Density decays with height above the world's sea level, with exponent clamped to[-1,4], ray distance capped48 blocks and optical depth capped2. Beer-Lambert extinction mixes current HDR radiance toward horizon/sky scattering. An analytic12th-power forward lobe uses the admitted sun/moon direct color and intensity; it cannot create direct moonlight during new moon. Rain increases density. Surface skylight access gates the medium, suppressing haze on fully skylight-dark cave receivers.

This is a receiver-skylight approximation: it does not integrate occlusion along the air volume and therefore cannot guarantee blocked sunlight in every roofed/open-sided scene. There are no shadowed shafts, god rays or local-light volume scattering. Those require a separate low-resolution volume pass and depth-aware reconstruction; this increment does not disguise an unshadowed lobe as volumetric rays.

The order is separated current/temporal directional lighting → analytic HDR extinction/scattering → emissive bloom → exposure/tone map → native display fog → native transparent composition. Native fog is applied once, with depth and actual frame transforms preserved. AO view bypasses atmosphere as well as tone/fog. Late supported entity material output uses the same current medium parameters without accumulating RGB history. No SceneColor copy, new geometry, full-resolution target or atmosphere history is added; only a16-byte uniform block, existing48-byte LightingEnvironment and MaterialEmission view are bound to output.

Admission requires polished foundation, overworld skybox and camera medium FogType.NONE. Water/lava/powder-snow fog and non-overworld environments retain their native policy. Unsupported/native surfaces, sky, clouds and transparent water remain native; atmosphere stops with the existing material coverage and24–32-block distance blend. This is local aerial perspective rather than horizon-wide atmospheric rendering. Toggle it off to reproduce0.19 output while leaving held lights and other controls unchanged.

## Verification

Automated checks compare horizontal optical depth to Beer-Lambert, quadrature to a dense height integral, density/weather/altitude bounds, forward-lobe direction, shader/producer uniform sizes and native GLSL→SPIR-V bindings. No GPU/display is available on the build host; in-game acceptance remains pending.

Check outdoor depth layering and low-sun forward glow, a fully enclosed cave, night/full/new moon, rain, first/third person held lights, plants/animals, native water and lava immersion, Nether/End, resize, F3+T and world exit. Use density0 and atmosphereoff to isolate any color/composition regression. After acceptance, the next substantial pass can target water surface inputs/composition, followed by explicitly shadowed quarter-resolution volume scattering; GI remains deferred.


## 0.24 opaque volume override

The analytic description above remains the comparison path and the water display path. With polished Overworld foundation +atmosphere +volumetric enabled and density>0, supported opaque HDR pixels instead use quarter-resolution shadow-marched in-scattering/transmittance. The two paths are not layered. `volumetric off` restores analytic rendering. See[scope and verification](VOLUMETRIC.md).
