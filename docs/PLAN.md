# VoxelLight 实施计划

状态：P0a 的 26.2 原型已实现并通过构建/离线测试，实机 smoke 待验证；P0b 和后续阶段尚未通过。按用户要求开发基线改为 Minecraft 26.2，原始 v0.2 保留历史。本计划纳入 [评审意见](REVIEW.md)，实现证据见 [接入记录](INTEGRATION.md)。先证明接入和缓存收益，不提前搭完整视觉管线。

## 阶段与验收

| 阶段 | 交付 | 进入下一阶段的条件 |
| --- | --- | --- |
| P0a 版本/能力实验 | 单 Fabric 工程、版本锁定、颜色/深度 test pass、backend 检测、关闭恢复、能力表 | Vulkan 接入成功，OpenGL/缺能力安全恢复 vanilla；清楚记录公开 API 与内部 hook。 |
| P0b 场景与测量基础 | GPU timestamp、CPU 时间、资源统计、最小材质/normal 验证、WorldSceneBridge、有界队列 | 原生画面/透明/UI 次序正确；resize、reload、切世界无陈旧资源；generation 丢弃与背压可验证。 |
| P1a 阴影正确性基线 | 固定太阳、单层非缓存参考阴影、caster 提交、cutout、bias、动态层 | 非主相机可见 caster 正确投影；薄墙、远处遮挡与方块编辑正确；确立参考画质。 |
| P1b 缓存 MVP | 三层 clipmap、页调度、有效期、静态/动态合成、变化太阳、debug/benchmark | 达到下述正确性和性能门槛；否则修订投影/失效方案，暂停增加效果。 |
| P2 统一 GPU Voxel DB | 有界 section DB、分类/压缩、occupancy 层级、增量上传与回收 | 加载/卸载、快速编辑和长距离飞行无陈旧条目；显存稳定，记录每帧上传和更新时间。 |
| P3 AO / Local Lights | 半分辨率 GTAO、emissive 聚合、cluster cap/overflow、基础 temporal | 洞穴和火把压力测试质量合理，溢出按稳定重要性选择而非闪烁；分项预算实测。 |
| P4 Probe GI | 从单 cascade/完整方块开始，visibility moments、预算调度、clipmap | 薄墙漏光、运动响应和最长年龄达标后再扩展三 cascade；记录 quality/time Pareto 曲线。 |
| P5 可选画质 | volumetric、SSR、透明材质增强 | 分别用实测证明收益，明确 fallback 和 history。 |
| P6+ 后续研究 | DRS/空间 upscaling，然后可选 vendor temporal upscaler/RT | 能力、许可证、分发与质量有依据；保留无 RT 路径。 |

所有阶段优先保留 Mojang geometry、frame lifecycle 与 UI。版本 adapter 集中内部依赖，新增 native 能力逐项证明同步规则。当前不创建占位实现、无验证版本号的构建配置或泛化框架。

## P0 可直接执行的任务

1. 锁定一组实际可解析的 Minecraft 26.2、Java、Fabric Loader/API、Loom 和映射版本，记录坐标、开发 GPU/驱动和依赖来源；建立 `./gradlew build`，仅打包客户端入口。
2. 从生成源码列出所需 hook：world pass 边界、depth lifetime、颜色/材质来源、normal、terrain caster 提交、透明合成、UI、资源销毁。每项记录 exact symbol 和验证证据；不能把设计里的伪接口当实际 API。
3. 最小 pass 实验逐项验证 depth 约定、颜色空间、resize 和 disabled path，再扩展最小 GBuffer；完整 velocity 延后。缺能力时原生渲染继续运行，日志说明功能关闭原因。
4. Timestamp 采用延迟读取，不阻塞当前帧；记录 query 支持、有效位、单位和 unavailable 状态。CPU 计时不能伪称 GPU 时间。
5. 增加 section 快照、加载/卸载、世界和资源 generation、有界合并；只对有实际数据流的逻辑建立测试。
6. 形成 smoke checklist：启动/退出世界、维度切换、teleport、resize/fullscreen、resource reload、开关效果、backend/能力回退。需要图形环境的检查记录实际完成状态，不以构建通过替代。

## P1 比较方法与建议门槛

同一实现提供“每帧更新全部有效页”参考模式和缓存模式，保持投影、覆盖、分辨率、过滤、bias、caster 集一致，隔离缓存本身收益。vanilla 用于测集成开销；Iris/Vitrail 仅作额外外部比较，注明不同 backend/材质/效果造成的不可比因素。

建议初始门槛如下，首次基线后如需调整必须记录原因，不能为了通过而暗降画质：

- 固定太阳静止场景暖机后静态页 reuse ≥ 90%，缓存模式阴影 GPU p50 较全更新减少 ≥ 30%。
- 正常太阳运动、飞行和编辑场景总 GPU p95 较全更新不得恶化超过 10%；阴影画质不能靠使用无效页或减少覆盖换取。
- 近层可见脏页目标两帧内更新；超预算记录 deadline miss 并回退有效粗层，不伪报完成。远层设置有界最大年龄，数值由首个原型的误差测试确定。
- 低太阳角度、昼夜切换、破坏远处 caster、slab/cutout、动态实体均与全更新参考逐帧/截图检查；记录 shadow disagreement 和最大滞后，未定阈值前人工验收。
- 30 分钟 fly/edit/load/unload 压力运行不超过配置资源上限；资源 reload 后回到可解释的稳态，无旧 generation 写回。

## Benchmark 记录与预算

固定世界存档/seed、路线、时间/天气、视距、分辨率、render scale、质量配置、GPU/驱动、游戏/依赖版本和 Git commit。至少覆盖平原、森林、城市、洞穴、火把、快速编辑、飞行，并分别测试固定太阳和运行昼夜。每种模式预热 30 秒、采样 120 秒、重复三次；随机或交替运行顺序。

记录 CPU/GPU frame p50/p95/p99、平均 FPS、1% low（定义为最慢 1% 帧平均时间的倒数）、各 pass 时间、驻留资源/分配字节、上传字节、页 reuse/失效原因/截止超时、probe 更新/年龄和 temporal rejection。保存原始 CSV/JSON 到被忽略的 `benchmark-results/`；可分享的摘要和复现实验说明经人工检查后提交 docs。

6.0 ms 效果预算是原文目标总额，不能承诺 120 FPS。为每一阶段记录 total frame、vanilla baseline 和增量；DRS 不得掩盖算法回归，性能比较时锁定 render scale。先固定质量测量，再引入带滞回的自动控制。

P0 首次实测后锁定资源表：每个 target 的格式/分辨率/历史/frames-in-flight，shadow atlas 上限，驻留 section 数和每条目字节，probe 数与 radiance/visibility 字节，staging 和每帧 upload 上限。每项有 owner、淘汰条件和显存不足的降级动作；在该表完成前不进入大规模 voxel/GI。

## 验证与交付

文档阶段检查相对链接、源文档提取和 Git 忽略规则，无可构建代码。实现阶段运行 `./gradlew build` 和相关逻辑测试；render 正确性通过实机 smoke/对比截图验证。客户端交付文件名含游戏和 mod 版本，只包含本 mod 产物。

仓库远程 URL 尚未提供，提交保留在本地。汇总仓库索引后续需登记 mineRenderer；不修改其他仓库或子模块指针。
