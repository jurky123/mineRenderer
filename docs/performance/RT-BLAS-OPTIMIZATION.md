# alpha.35：BLAS 属性更新、scratch slices 与 native 计时

针对 alpha.34 场景的 218 个动态分组、约 154 次主 BLAS refit/commit，先优化属性更新和 scratch 复用，并提供保持直接光不变的专用 A/B。

## 执行变化

动态 Section 保存排序后 AS 输入的精确 position bytes。仅当旧 vertex buffer 仍被复用、三个 geometry range counts 相同且所有 position bytes 相同，才跳过 AS 更新。UV、tint、纹理槽、normal 等 shader 属性仍按原机制上传并复制到 shader geometry，更新 version 和 motion identity 校验；TLAS/pose/历史保持原机制。真实形变、位置改变、新对象、buffer/range 大小变化仍执行必要的 build/refit。不使用位置近似阈值或位置 hash，避免漏掉微小形变或 hash collision。静态地形的材质/OMM 更新仍保留原处理。

原先 shared scratch 每个 build 都复用同一起点并放置 barrier。现在单个 native submission 内分配按设备要求对齐、互不重叠的 slice，独立 AS 更新之间不再仅因为 scratch 重叠而同步。每个 submission 第一次 acquire 保留 AS read/write dependency；`submitted()` 后才重置分配 cursor。arena 起始 1MiB、增长至通常最多 16MiB，达到上限则通过 barrier 等待既有 AS 读写后安全复用，而不是继续无界增长。单个 build 若需要更多 scratch，仍按真实需求增长。旧 buffers 在 submission 执行后进入 MC 的延迟销毁，scene close 同时修复旧实现残留在 retiring list 的当前 buffer。

这保持 scene-owned shared arena，不恢复每 BLAS 永久 scratch。此处尚未合并同 owner 的多个 draw features，也没有把多个 AS 塞入一次 `vkCmdBuildAccelerationStructuresKHR` 调用；先评估消除 scratch 依赖本身的收益。

Vulkan 对 scratch 的访问范围和 AS 构建之间的依赖有明确要求，不能直接删除 barrier 并让构建同时复用重叠 scratch：[官方 AS 构建规范](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBuildAccelerationStructuresKHR.html)。本实现使用不重叠区间，保留跨提交/重复使用时的依赖。

## 计时与工作量

`vulkan_rt_blas` 改为在真正 AS command buffer 内的 native TOP/BOTTOM timestamps，结束 timestamp 在 `vkEndCommandBuffer` 前录制。原外部 span 改名 `vulkan_rt_blas_submit`，用来对照 native 时间与外部跨度；`vulkan_rt_scene_commit` 保留完整场景提交的成本。

status/start/end 导出新增累计计数：

- `blasAttributeOnly`：仅属性变化、跳过 AS 的动态更新。
- `blasStaticBuilds`、`blasDynamicBuilds`、`blasDynamicRefits`：主 BLAS 按对象类别/操作拆分。
- `blasPositionUpdates`、`blasLayoutUpdates`：动态旧对象的变化原因，可能同时增加。
- `blasOpaqueUpdates`：单独 opaque BLAS 的 build/refit 次数。
- `blasUpdatedTriangles`：实际提交主 BLAS 更新的 triangle 数。
- `asScratchBarriers`、`asScratchSlices`、`asScratchBytes`：scratch 同步次数、acquire 次数与当前常驻容量。

对 block start/end 计数求差，再按 scene commit/TLAS 更新数或采样帧数归一化。start/end 的计数区间包含预热和 drain，不能把它们直接与只含采样段的 timestamp 总和配对。精确 scope timings 使用 `.passes.csv`/block summary。

## 客户端一条命令验收

替换 alpha.35 jar 后进入与上次相同的有动态模型的 Vulkan PT 场景，站定关闭菜单：

```text
/voxellight rt_benchmark blas
```

共 8 段，两轮 ABBA，默认每段 10 秒采样；`blas 15` 延长到 15 秒。A=legacy scene update（完整 version 变化 refit、串行 scratch），B=optimized（属性更新跳过 AS、scratch slices）。两侧保持 RIS、Query（支持时）、Fixed queue、OMM/SER off。真实模式加入实际配置校验，结束或 stop 自动恢复原设置；不要在测试中手动切控制或移动。

summary schema 7 的 `blas` 自动判定比较 **`vulkan_rt_scene_commit`**，而不是本来不应变化的 transport。同时检查 native `vulkan_rt_blas`、外部 `vulkan_rt_blas_submit`、TLAS、geometry copy、transport 和计数差值。若属性跳过数很少，不意味着实现失败，可能这个场景主要是真实动画位置变化；此时仍观察 scratch barriers 与 native AS 时间是否下降。需要超过两轮波动才能认定提速。

通常约 2–4 分钟，导出仍在 `benchmark-results/voxellight/rt-suite-<时间戳>.zip`。上传整份 zip，并观察实体动作、手持物、粒子、纹理切换和快速移动是否正常。快速移动视觉检查应放在自动测试结束后。没有必要为这轮重跑完整 suite（现在最多 72 段）。

手动诊断控制为 `/voxellight rt_scene_update legacy|optimized`，默认 optimized，会话控制不保存到 settings.json。不能把 alpha.34 的外部 `vulkan_rt_blas` 与 alpha.35 的同名 native scope 直接比较，使用同一 alpha.35 中的 A/B。

本地回归覆盖精确 position snapshot、属性变化与各顶点位置变化、geometry category counts、scratch 地址对齐/非重叠/溢出、BLAS ABBA 控制恢复及判定指标选择；shader/输运逻辑保持 alpha.34。本机没有 NVIDIA GPU，实际 AS 时间、异步同步和动态视觉仍需客户端验收。
