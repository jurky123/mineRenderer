# 0.19 local-light materials and held-light reference

This phase keeps the existing16-source,80³ voxel atlas and48-step shape visibility reference. It adds authored colors and one bounded dynamic source, without changing shadow or material geometry budgets.

## Try it

```
/voxellight mode foundation
/voxellight held_lights on
```

In a dark room, hold a torch, soul torch, lantern, glowstone or end rod, move near a wall, and compare `/voxellight held_lights off`. The feature is on by default when local lights are enabled; startup mode still defaults off. Main/off hand default-state emissive BlockItems are supported. If both hands qualify, the higher block emission wins (main hand wins ties), giving one source near the player's interpolated eye/body rather than attaching it to the camera. Third-person rotation therefore does not move the source away from the player. Dead/spectator players do not emit. Light disappears on unequip rather than leaving history or stale geometry.

A held source reserves one of the16 slots: up to15 selected static emitters plus one dynamic. Static selection retains its existing admission fade/hysteresis within the reduced budget; reserving a slot can immediately evict the last static slot. No additional per-pixel DDA loop, texture, scene invalidation, material rebuild or light packet is introduced by player motion. Camera-relative upload subtracts doubles before converting to floats. Shape/unknown-space occlusion remains in effect; wait for local voxel warmup before assessing it. Entity bodies do not occlude these local voxel lights. Held-source position is a virtual body source, not an exact animated hand model.

In foundation, placed lights still replace the native block-light baseline only when stronger. Held contribution is added separately because vanilla does not contain that source. AO and directional shadow visibility do not attenuate held direct light; only Lambert, radius falloff and local voxel visibility do. Local RGB remains current rather than accumulated in directional visibility history. Legacy `shadow` remains an already-lit color comparison path; foundation is the primary held-light test.

## Authored colors

The resource file is `assets/voxellight/light_materials.json`:

```json
{
  "minecraft:torch": [1.0, 0.62, 0.24],
  "example:blue_lamp": [0.1, 0.4, 0.9]
}
```

Keys are exact namespaced block registry IDs, values are three finite0..1 RGB components, with at most1024 entries. The actual block state's native emission still supplies intensity/radius; JSON color does not turn non-emissive blocks into light sources. Placed emitters use their actual state; held items use the block's default state, so an unlit lamp/candle item does not emit. Unknown IDs use neutral warm fallback; there is no substring-based color guess. Bundled torch/soul/redstone/lava/fire/sea-lantern/end-rod/froglight families have explicit profiles.

A resource pack replaces the file as a whole; include existing entries if desired. `F3+T` advances resource generation, reloads colors and rebuilds the bounded static emitter extraction. A malformed file is rejected as a whole, logs the reason and restores bundled profiles; status reports the fallback. `lightMaterials`, `activeHeld`, `heldLights` and the combined `activeLights` are observable.

## Scope and acceptance

This is the first local-light polish increment. Dense4³ static cells still retain their strongest emitter; energy/centroid aggregation is deferred because grouping solid glow blocks at an interior centroid can break boundary visibility. Burning entities, dropped emissive items, other players' held sources, emissive non-BlockItems, local entity shadows and new falloff models are not included. These can follow after this dynamic source is verified. Water/atmosphere and GI remain future phases.

Automated validation covers resource IDs/colors, malformed profile rejection, bundled colors, combined static/dynamic slot cap, shader compilation/bindings and existing lifecycle contracts. In-game acceptance remains pending on this host without a GPU/display. Check torch/soul torch in both hands, inventory swap/removal, third person, thin walls, world boundaries, local-light off/on, teleport, resize, F3+T, and dimension changes. Ensure no persistent light remains after the item/world is removed.
