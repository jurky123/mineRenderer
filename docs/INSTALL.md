# VoxelLight 0.39.0-alpha.18 安装

Minecraft Java 26.2 / Java 25 / Fabric Loader 0.19.5 / Fabric API 0.160.0+26.2。安装包只含本 mod，不重复打包已有前置。

1. 删除旧 VoxelLight jar，将 `mods/voxellight-client-26.2-0.39.0-alpha.18.jar` 放进客户端 `mods/`。
2. 视频设置选择原生 Vulkan 图形 API，然后重启。
3. 进入世界，执行以下命令。

```text
/voxellight rt_backend vulkan_pt
/voxellight held_lights on
/voxellight rt_accumulate freeze off
/voxellight rt_accumulate spp 256
/voxellight rt_accumulate on
/voxellight status
```

默认目标 64 spp（4–4096）。静止时累积，达到目标后持续动态平均；太阳、水波、手持光每帧更新。手持灯切换、明显光照变化、移动镜头、地形编辑或 F3+T 会重置。`rt_accumulate freeze on` 显式冻结并在目标达到后停止，`freeze off` 返回动态模式。`reset` 清空历史，`off` 恢复每帧 1 spp。

`rt_backend vulkan_poc` 查看法线，`vulkan_transport_test` 查看灰色材质输运，`raster` 返回光栅。`preset vulkan_quality` 开启 Vulkan 材质与累积，performance/balanced/quality 是光栅预设。设置界面：暂停/Options → VoxelLight 或 `/voxellight settings`。

旧 OptiX/CUDA 追踪器和旧命令已删除；安装包不含 native DLL/SO/PTX，不需要 CUDA/OptiX SDK。旧保存配置自动迁移。独立 OptiX 去噪尚未接入。

实机检查：先等地形稳定，开启动态累积并观察目标计数。保持镜头静止，切换火把/空手、观察太阳随世界时间变化，状态应显示手持灯参数和太阳方向更新；在暗处放置/破坏发光方块应更新直接光照且无 miss。检查水/玻璃/掠射角区域累积没有红色坏像素，再测试冻结开关、移动镜头、F3+T 和缩放窗口。构建主机无 NVIDIA GPU，本版视觉与性能验收需要实机完成。

新增 `/voxellight rt_spp 2`：每帧 2 个路径样本（范围 1–8），减少运动时噪声，GPU 开销相应增加。请验证萤石旁放置/破坏火把不再改变萤石自身发光面，以及暗室手持/放置火把在相同源距下照明接近。
