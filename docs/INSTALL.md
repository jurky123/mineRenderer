# VoxelLight 0.39.0-alpha.13 安装

Minecraft Java 26.2 / Java 25 / Fabric Loader 0.19.5 / Fabric API 0.160.0+26.2。安装包只含本 mod，不重复打包已有前置。

1. 删除旧 VoxelLight jar，将 `mods/voxellight-client-26.2-0.39.0-alpha.13.jar` 放进客户端 `mods/`。
2. 视频设置选择原生 Vulkan 图形 API，然后重启。
3. 进入世界，执行以下命令。

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_accumulate spp 256
/voxellight rt_accumulate on
/voxellight status
```

默认静止目标 64 spp，范围 4–4096。每帧追踪 1 spp，静止时求线性 HDR 平均；目标达到后保留画面。移动镜头、场景变化或 F3+T 会重置。提高目标保留已有样本。`rt_accumulate reset` 手动开始新快照，`off` 恢复实时 1 spp。静止期间天空、动画纹理、水波和手持灯参数冻结。

`rt_backend vulkan_poc` 查看法线，`vulkan_transport_test` 查看灰色材质输运，`raster` 返回光栅。`preset vulkan_quality` 开启 Vulkan 材质与累积，performance/balanced/quality 是光栅预设。设置界面：暂停/Options → VoxelLight 或 `/voxellight settings`。

旧 OptiX/CUDA 追踪器和旧命令已删除；安装包不含 native DLL/SO/PTX，不需要 CUDA/OptiX SDK。旧保存配置自动迁移。独立 OptiX 去噪尚未接入。

实机检查：先等附近地形稳定，观察 `accumulatedSpp=N/目标` 增长并停止；从 64 改为 256 应继续增长。再移动、转视角、放置/破坏方块、F3+T、缩放窗口，确认重新累积且无 miss 孔洞。切换 off 应恢复动态天空/水波。构建主机无 NVIDIA GPU，本版视觉与性能验收需要实机完成。
