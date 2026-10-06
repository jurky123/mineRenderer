# alpha.34 BLAS 成本定位

基于同一份 alpha.34 direct ABBA 结果和当前代码，先区分已确认的工作量与仍需 native timestamp 验证的耗时来源。

## 确认：大量动态 BLAS refit，不是每帧重建全部地形

8 个 block 的 start/end 累计计数按 `Δ(tlasRefits+tlasBuilds)` 归一化，得到每次 scene commit 的主 BLAS refit 为 153.31–154.83，平均 **153.96**；主 BLAS build 为 0.625–0.675，平均 **0.653**。静态 terrain snapshot 始终 295 sections，动态分组始终 218。动态三角形数据约 0.46–0.48 MB，218 个分组平均每个约 18 个三角形。

`blasBuilds/blasRefits` 只统计主 BLAS，不含单独 opaque BLAS。本场景的动态 triangles 全部写入 cutout flag：`RtDynamicScene.triangles()` 使用 `128|1|(slot<<20)`。因此动态分组通常只有 CUTOUT geometry，不会同时为自身构建 opaque BLAS；不能把这次 3 ms 解释为两份动态 BLAS 重复更新。

`VulkanRtScene.update()` 对相同 version 只处理 instance transform/motion metadata，不构建 BLAS；refit 分支限定 dynamic key。当前数据支持“约 154 个动态分组持续变化并 refit”，不支持“295 个静态 section 每帧重建”。少量 build 的具体 owner/类型没有逐对象计数，不能仅凭累计数据认定全部来自动态粒子或地形。

## 确认：分组和 scratch 复用形成大量小更新

`RtDynamicScene` 将 `(owner, feature, hand)` 作为模型 key。`DynamicModelBuffer` 每个 draw 增加 feature，因而一个实体/方块实体可能有多个独立 BLAS，并非一个 entity 只对应一个 BLAS。它对完整 triangle bytes 求 hash，hash 改变就提交 section 更新。

主 BLAS 使用同一个 native command buffer，避免了每对象提交；但 `VulkanRtScratch.acquire()` 每次 build/update 都返回同一 scratch 起点，并插入 AS build read/write dependency barrier。约 154 次动态 refit 对应约 154 次 scratch 复用依赖。这里没有批量使用互不重叠 scratch slices。

这是真实的执行结构；它对 3 ms 的贡献比例尚未测量。不能仅依据 barrier 数就断言移除串行依赖可以获得某个倍数，也不能直接删除 barrier 后继续重叠复用 scratch。

## 确认：version 粒度大于 AS 几何变化粒度

动态 hash 包含 position、UV、tint 和 texture tile flags。`VulkanRtAccel` 的硬件 vertex input 读取 R32G32B32 position，stride 40；UV/tint/tile 等由 hit shader 从 attribute buffer 读取。由此，只有这些 shading attributes 改变时，也会触发当前的 BLAS refit。

代码存在属性变化引起 refit 的路径，但现有导出没有 position-only hash 或属性更新计数，因此不知道它占每帧 154 次 refit 的多少。不能称这 154 次全部冗余：动画形变、手持动作、粒子等位置变化仍需要更新 AS。

## 计时口径需要先校准

`vulkan_rt_blas` 使用 `RenderPassProfile.begin(profileEncoder, ...)`，开始/结束时间戳通过 MC encoder 提交；实际 AS 命令在另一个 native command buffer 中录制和执行。开始时间戳可能先于 CPU 完成该 command buffer 的录制，跨度可能包含提交空档、依赖等待，而不是仅有硬件 refit。

第一段的 BLAS CPU submission median 约 **0.626 ms**，GPU elapsed median 约 **2.983 ms**；第二段约 **0.654/3.000 ms**。不能简单从 GPU elapsed 减去 CPU submission 得到纯 AS 时间：两者可能重叠，等待和队列执行顺序也不同。

生产 transport 已使用 `beginNative` 在同一 command buffer 内写时间戳。BLAS 应采用相同方法，结束 timestamp 必须在 `vkEndCommandBuffer` 前录制；原有外层 scene-commit wall span 可继续保留，并额外报告 CPU recording span。

## 后续处理顺序

1. 在实际 AS command buffer 内测量 BLAS，分别导出每帧 static build、dynamic build、dynamic refit、opaque update 的次数和 triangle 数，并区分位置变化、属性变化、topology/geometry-layout 变化。用这些数据确定真实瓶颈。
2. 分离 AS geometry version 与 shader attribute version：属性更新仍上传、更新 shader geometry 和历史身份，但 position/AS layout 未变时不 refit。必须保留新建、大小/range/flags/topology 变化及真正形变的更新。
3. 根据对象计数考虑合并同一 owner 的 draw features；保留 per-triangle texture/material 与稳定 motion identity，不能把所有动态物体无差别合成一个巨大 BLAS。
4. 如果 native timing 仍表明小 AS 更新昂贵，再设计共享 arena 的不重叠 scratch slices 与批量 build recording，保留所需前后依赖；对照 serial baseline。不能以删除现有 hazard barrier 作为优化。

结论：方向已经定位到 **众多小型动态 BLAS refit + version 粒度 + scratch 串行复用**；先修正计时并补变化原因计数，才能确定最值得动的那一项。当前约 3 ms 是外部 scope elapsed，尚不是已隔离的纯硬件 refit 时间。
