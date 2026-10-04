# VoxelLight settings

0.37.1 provides a vanilla-widget settings screen; MineUI is not required. In a world, click **VoxelLight** in the pause menu or Options, or run `/voxellight settings`. The menu does not pause rendering, so reference accumulation continues while progress is visible.

Five searchable categories cover Rendering, RTX, Environment, Water and Diagnostics. Click a choice field to open its list; numeric fields show the accepted range and have an **Apply** button. Invalid values leave rendering unchanged and show the validation error. Existing commands remain available. The screen catalogue is derived from their registered argument tree, so accepted choices/ranges have one source of truth. Nested `rt_reference spp` has its own field.

A field marked **Default — choose** has no recorded override; it does not claim to read an individual render pass's internal default. Successful command edits and menu edits update the same displayed override values. Selecting a preset clears previously saved individual overrides; subsequent edits are replayed after that preset.

Preferences are written atomically to `config/voxellight/settings.json`, and replayed on joining a world. Debug views, profiling, benchmark actions, legacy freeze and reference **on/off/reset** are session-only. Reference sample target is saved. Restore feedback may appear in chat. Settings require a world connection; the main-menu Options entry can be opened, but renderer actions require joining a world. Effects remain off on a fresh installation with no saved preferences.

## Reference mode

Choose **RTX → rt reference → on**. This also enables Foundation and initializes OptiX if needed; manual backend activation is no longer required. Set **rt reference spp** (default 256), then keep the camera stationary. The footer shows `Reference: completed / target spp`. If it says RTX inactive, inspect `/voxellight status` for the backend/device error: RTX still requires native Vulkan and the matching NVIDIA CUDA device.

Unchanged frame-budget quality no longer resets accumulation. Reference disables projection jitter, RGB TAA and adaptive budget application while active. Explicit quality changes, camera movement, scene edits and reset still invalidate it. Animation in the RT scene is frozen after the first sample; sunlight/weather/waves were already frozen. Raster primary visibility continues to render, so use static acceptance scenes for screenshots. On leaving reference, dynamic RT updates resume and normal TAA/adaptive controls continue using their existing preferences. Reference is raster-primary, not a full primary-ray offline renderer.

## In-game checks

1. Open settings from Pause and Options. Search, page through controls, choose a preset, and edit a numeric field. Verify an invalid value produces an error without changing the renderer.
2. Change a setting with a command, reopen its category and confirm the displayed override. Rejoin/restart and verify the saved preset plus overrides return. Debug and reference must not auto-enable.
3. Enable reference without first enabling RTX. With stationary camera, verify the counter advances to 256, including scenes with nearby animated entities. Warm-up section uploads may reset it while the RT scene finishes loading.
4. Move the camera or press reference reset: accumulation should restart and converge again. Turn reference off and verify animation/normal rendering resumes.

Java preference and accumulation-state regressions plus native/shader compilation are automated. UI interaction, RTX convergence and screenshots still require in-game acceptance on an NVIDIA Vulkan system.
