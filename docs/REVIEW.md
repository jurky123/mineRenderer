# VoxelLight v0.2 设计评审

评审日期：2026-10-01。结论：保留“扩展原生 renderer + 缓存世界空间光照”的方向，但现有文档是研究架构，尚未证明可实现性和性能收益。以下是后续实施建议，不代表已实现或已测量。

## 依据核验

| 原始判断 | 核验与边界 |
| --- | --- |
| 26.2 提供可选 Vulkan，应使用 Blaze3D | [Fabric 官方渲染文档](https://docs.fabricmc.net/develop/rendering/basic-concepts)支持该判断，并描述 extraction / RenderState。不能据此推断任意 compute、MRT 或 terrain 重绘接口已经公开。 |
| 26.3 改动 terrain multi-draw 和 OIT | [26.3 官方更新说明](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3)确认相关变化，同时明确 core shader override 属于不受支持的内部实现。 |
| 26.4 Snapshot 1 默认偏好 Vulkan | [官方快照说明](https://www.minecraft.net/da-dk/article/minecraft-26-4-snapshot-1)确认 Default 等同 Prefer Vulkan；不是稳定 mod API 承诺。 |
| Vitrail / Radiante 可作为参考 | [Vitrail 源仓库](https://github.com/avpbynf/Vitrail-Shaders)和 [Radiante 源仓库](https://github.com/Gabrieli2806/Radiante-26.3-Fabric)可访问。这里只核验项目存在，不将其 README 的能力或性能声明当成本项目证据；复用代码前核对具体提交和许可证。 |

## 必须提前解决的工程问题

### 1. 接入验证应先于完整 GBuffer

Blaze3D 的存在不等于能以低侵入方式获得未照明 albedo、MRT、motion vectors、任意相机的 terrain 提交或 Vulkan command recording。首先建立逐项能力表，记录具体版本、符号、线程、hook 和最小实验结果。

先做颜色/深度采样的最小 pass，再验证材质和 normal 输出。屏幕深度重建 normal 可用于诊断，不能作为复杂实体/材质正确性的最终证据。vanilla 已照明颜色不能直接当 albedo，否则会重复照明；必须明确 vanilla lightmap、顶点 AO、雾、emission、色彩空间、HDR 与 tonemap 的职责。透明物体、手持物、粒子和 UI 的合成顺序也需要单独验证。

Vulkan 句柄不够：native extension 必须证明命令插入时机、资源 layout、屏障、提交队列和 GPU 完成后的销毁规则。不创建第二套 device/queue lifecycle。无法安全挂接则停止该能力，不用每帧全局同步绕过问题。未知 backend 在注册 Vulkan 专用 hook 前退出增强路径，恢复 vanilla。

### 2. 阴影缓存的可行性取决于投影和遮挡范围

相机平移可通过 light-space texel/page 对齐保留缓存，但太阳旋转改变所有遮挡物投影；时间累积不能让旧 depth 在新投影下自动有效。页必须记录光照方向/投影 epoch。先固定太阳验证缓存，再测自然昼夜、低太阳角度和时间跳变。角度量化只能作为有视觉误差界限的实验，不能默认正确。

section 到页的失效映射要考虑沿太阳方向投射到远处接收面的影响，不能只用 section 与接收页的世界 AABB 相交。阴影相机的 caster 范围也不能仅取主相机可见 terrain。先用保守投影范围做正确性基线，再优化；记录 slab、stairs、cutout、动画方块和动态 BlockEntity 的处理方式。

缺页或过期页先标无效，使用确定的较粗有效层级/无阴影回退，禁止采样 atlas 被复用后的旧数据。动态层与静态层要统一光空间、bias 和深度定义，按深度/visibility 规则合成。

### 3. 统一脏区应包含卸载、背压和 generation

在阴影前建立 WorldSceneBridge，不必等待 GPU Voxel DB。事件至少含 section 加载/卸载/变化、光照变化、world generation、resource generation 和版本。worker 只读游戏线程生成的不可变快照；完成后校验 generation/version，旧结果不能写进新世界。

有界队列满时合并成 section 重建标记，不能静默丢失最终状态。以时间/字节预算排空；连续编辑不应让远处脏页永久饿死。卸载先撤销逻辑引用，GPU fence 完成后才能复用物理资源。先用简单有界队列，锁无关优化以 profile 为依据。

### 4. GI 必须有 visibility 数据和收敛目标

occupancy bitset 无法准确描述 slabs、stairs、leaves、glass 或自定义模型；“保守占满”会造成漏光相反的过度遮挡。明确 full/partial/cutout/transmissive/unknown 分类和质量限制，先实现完整方块场景。GPU DB 在 local-light 聚合和 Micro-AO 消费它之前建立。

SH 保存方向辐射，不能替代遮挡验证。首个 GI 原型就需要方向相关距离/距离二阶矩、validity 和插值权重，测试薄墙与洞穴漏光；probe relocation 只有在这些机制仍不足时再加。每次重用 clipmap 槽位清空旧位置历史。

3 × 32³ = 98,304 probes；512–2,048 probes/frame 对应均匀完整刷新约 192–48 帧。若每 probe 8–32 rays，则每帧 4,096–65,536 rays；这些是算术范围，不是预算达标证据。必须测运动时的响应延迟、最长更新年龄和新区域变有效所需时间。

### 5. 时间、显存和画质预算需要一起约束

原表额外 GPU 预算合计 6.0 ms；120 FPS 的 8.3 ms 目标仅剩约 2.3 ms 给 vanilla 和其他开销，不能承诺总帧率。报告 vanilla、增强总时间和增量时间；不能简单将可能重叠的 pass 时间相加当总帧时间。

4096 位 occupancy 为 512 B，emissive mask 另需 512 B，层级、材质、索引、staging 和历史还需额外空间。100,000 sections 的两套 mask 已约 97.7 MiB。1080p 下单个 4-byte target 约 7.9 MiB，8-byte target 约 15.8 MiB；历史和 frames-in-flight 会继续放大。锁定最大驻留 section、shadow atlas、probe 存储和上传预算，并对超限执行可观测的淘汰/降级。

Temporal validation 可共用代码和 reset 事件，但 AO、GI、reflection、volumetric 保留独立 history、参数和有效期；不能假设统一 filter 适合全部信号。先实现 camera-only reprojection，实体缺 motion vector 时拒绝 history，再按证据扩大支持。

## 范围决策

采用单工程/功能包，不按原建议立即拆 core、adapter、debug 三个发布模块或定义单实现 backend 接口。MVP 收敛为稳定接入、统一脏区、缓存太阳阴影、profiler 与可复现 benchmark。AO、local lights、GI 是后续阶段；vendor upscaler、RT、完整透明 relighting 最后评估。MineUI/MineAudio 等现有业务 API 不覆盖本客户端渲染职责，无跨项目改动需求。
