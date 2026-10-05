# VoxelLight 设置与静止累积（alpha.13）

暂停/Options → VoxelLight 或 `/voxellight settings` 打开原版控件界面。命令树提供可搜索的选择项，`rt_accumulate spp` 有独立数字输入。界面底部显示静止累积的完成样本数和目标，每秒刷新。

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_accumulate spp 256
/voxellight rt_accumulate on
```

默认目标 64 spp，范围 4–4096。每帧 1 个独立路径样本，线性 HDR 运行平均；达到目标后停止追踪并保留结果。提高目标保留已有样本。移动、转视角、FOV/分辨率变化、世界/地形更新、方块编辑、资源重载会重置。`rt_accumulate reset` 手动开始新快照，`off` 逐帧更新并不复用历史。

每次快照冻结共享天空、动画纹理、水波和手持灯参数，避免混合不同时间的场景；移动镜头或手动 reset 更新它们。它是静止多帧采样，不是 TAA/去噪或运动重投影；法线调试不累积。Vulkan PT 关闭光栅 projection jitter，防止静止镜头不断重置。

设置原子写入 `config/voxellight/settings.json`，进入世界时按顺序回放。选择 preset 清除此前个别覆盖值。累积开关与目标保存，reset/诊断操作不保存。全新安装没有覆盖值时仍默认 effects off。

旧 `preset rtx_quality` 保存值迁移为 `vulkan_quality`；旧 OptiX/CUDA backend 保存值迁移为 `vulkan_pt`；旧 `rt_reference spp` 迁移为 `rt_accumulate spp`。旧参考模式/旧 pathtrace 和旧缓存、去噪开关丢弃；这些命令不再注册。OpenGL 上 Vulkan backend/preset 显示切换图形 API 的提示。
