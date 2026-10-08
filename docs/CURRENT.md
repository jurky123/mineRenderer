## 0.39.0-alpha.48：RR Guide 与 Transport 对照

独立反射 motion、平滑透射终点近似、方向半球 specular albedo、roughness 编码 A/B、HDR/Guide/AOV/RR 导出已加入。新增 SIMPLE 材质与 48B TWO_PASS 实验以及有界异步 CPU 场景准备 P95 对照。默认仍 FULL Material 3 / Wavefront；完整后台 GPU AS、硬件纹理/ray-cone mip 和复杂折射/动态镜面正确性尚未完成。[实现与验收](performance/RECONSTRUCTION-TRANSPORT-BASELINE.md)。

完整 `build clientKit` 通过：368 项 Java 测试零失败、原生 Slang/GLSL 数值回归通过；109 个 SPIR-V 和 59 个源码哈希核对通过。1024 组打包方向最大向量误差 6.05405e-05。

[alpha.48 安装包](https://temp.sh/MjIkV/voxellight-client-kit-26.2-0.39.0-alpha.48.zip)；SHA-256：`4ca4764ecf4062389fd1957834ccffed56a462e736553d92e2a8e74ec8a57642`。

## 0.39.0-alpha.47：Cache Cost Reduction

ROUGH_DIFFUSE 预积分核、截断/材质范围回退、Primary 已验证分类和 guide 复用、四项 kernel 覆盖诊断已实现。`rt_benchmark cost`：16 段两轮 ABBA，隔离 kernel transport 成本，再比较 FULL/MONOLITHIC 与 SPLIT/PRIMARY/CACHE_SPARSE 的 GPU world。默认仍 FULL/MONOLITHIC；实机收益、画质和长停顿根因尚待验收。[实现与命令](performance/CACHE-COST-REDUCTION.md)。

完整 `build clientKit` 通过：363 项 Java 测试零失败、Slang/native 数值回归通过；256 组预积分测试最大相对误差 0.406%，截断回退与原求值一致。包内 103 shader / 55 源码哈希和持久 LUT 资产核对通过。

[alpha.47 安装包](https://temp.sh/ouqkP/voxellight-client-kit-26.2-0.39.0-alpha.47.zip)；SHA-256：`82cc0783d5aa900ff71286800534106d43b0496fe21ddd46f8e282bc72c8b3a5`。

## 0.39.0-alpha.46：修正 benchmark GPU 首次执行门禁

等待首个 GPU 样本、收集样本与 GPU 耗时稳定性分开计时；断线/世界变化不再标为用户取消。默认仍 FULL/MONOLITHIC，缓存净性能和长停顿根因未宣称解决。[修复与剩余问题](performance/ALPHA-46-BENCHMARK-FIXES.md)。

[alpha.46 安装包](https://temp.sh/GPvcV/voxellight-client-kit-26.2-0.39.0-alpha.46.zip)。358 项 Java 测试零失败，完整构建通过，包内 103 shader / 53 源码哈希核对通过。SHA-256：`048575e1bb30342916eea08a69f60c93ee4bbd95055977138ad65a2b06c6995e`。

alpha.46 Material 实测 24 段全部 valid：首次样本等待超过 30 秒后仍能完成；PRIMARY 相对 TAIL transport 改善 8.62%，FULL Split 退化 7.07%，SHIFT 波动内。真实缓存 query/train/hit 与 B1/B2 路径减少已确认，FULL 对缓存 production 和画质仍待验收。[专项分析](performance/ALPHA-46-MATERIAL-ANALYSIS.md)。

## 0.39.0-alpha.45：Material-Aware Cache 2.1

默认 ROUGH_DIFFUSE 的 diffuse/specular lobe 分解、B0/B1/B2 diffuse cache 与精确 specular continuation、半球方向矩求值、源 bounce 隔离、diffuse history 与 56 列 GPU counter 已实现。训练由 accepted request counter 间接调度；动态 model slot 回收带 generation。默认仍 FULL/MONOLITHIC/TAIL/OWEN/EXACT/Wavefront。

[实现、数值验证与验收门禁](performance/MATERIAL-AWARE-CACHE-21.md)。alpha.45 尚无 RTX 同画质、Nsight 或 production 实测，不宣称 FPS 提升；Adaptive Execution 自动选择与跨实体共享 BLAS 仍须完成后续实机阶段。

[alpha.45 安装包](https://temp.sh/PkwTT/voxellight-client-kit-26.2-0.39.0-alpha.45.zip)；SHA-256：`281ccb998271de0b07240735b2923ed51f484f543b34cabfd62711b1aeb71c4b`。完整 `build clientKit` 通过：356 项 Java 测试零失败、真实 Slang/native 数值回归通过；最终 ZIP 内 103 个 SPIR-V stages / 53 份源码哈希与当前源码核对一致。

alpha.45 已收到首份 production FULL 基线：4 段有效，GPU world P50 13.525–13.598 ms，client interval P95 25.888–36.748 ms。全部 FULL/MONOLITHIC，缓存未开启；material 专项、缓存对照和画质尚待验收。[基线分析](performance/ALPHA-45-PRODUCTION-BASELINE.md)。

## 0.39.0-alpha.44：Material/Light 与 Primary Split / Cache 2.0

实机专项：24 段完整，三组均为波动内；Primary Split −2.38%、B0 +1.59%、SHIFT +2.68% 均未证明可靠收益。B0/history 查询、训练与复用全零；默认 ROUGH_DIFFUSE 被 pure DIFFUSE 入口排除，算法覆盖未验收。[分析与实现缺口](performance/ALPHA-44-MATERIAL-ANALYSIS.md)。

[alpha.44 安装包](https://temp.sh/aPVgK/voxellight-client-kit-26.2-0.39.0-alpha.44.zip)；SHA-256：`c13f42a7931b2c4f612799975de11ee2058e45418bb1c82745a50051f8a66e3f`。353 项 Java 测试、真实 Slang/native 数值回归与完整构建通过；包内 103 个 SPIR-V stages / 52 份源码哈希核对通过。

Shadow 快速材质读取、local alias PMF、可切换 digital shift、32 B hit record + active shading queue、B0 pure diffuse cache、紧凑训练请求和动态几何复用已实现。新增保留日常配置的 production benchmark 与长期帧时间/CPU/GPU/内存报告。默认仍 FULL/MONOLITHIC/TAIL/OWEN/EXACT/Wavefront；实机性能和同画质验收待完成。[设计、限制与命令](performance/PRIMARY-CACHE-2.md)。

## 0.39.0-alpha.43：输运与场景提交热路径

[alpha.43 安装包](https://temp.sh/cBoiG/voxellight-client-kit-26.2-0.39.0-alpha.43.zip)；SHA-256：`785703bbc4145b357b34864b84d3ffdab783e896e26394175b9d1656bf022f3c`。350 项 Java 测试与数值回归通过，包内 80 个 SPIR-V stages / 48 份源码哈希核对通过。

减少非发光 MIS 查询、RIS 完整材质解码、动态更新驻留表与重复排序。保留 EXACT / Wavefront 默认，FAST 按场景验收。[实测及验收](performance/ALPHA-42-FRAME-ANALYSIS.md)。

## 0.39.0-alpha.42：frame 实测与接管验收修正

[alpha.42 安装包](https://temp.sh/vLvRh/voxellight-client-kit-26.2-0.39.0-alpha.42.zip)；SHA-256：`50d373ffc00e309208a6e5bebe15bee413d6847ca32d482d147903f210721924`。

alpha.41 最新测试实际仍是 OptiX / 214×120，exclusive 请求未接管；FAST 慢约4%，Iterative 慢约24%。默认回到 EXACT + Wavefront。世界生命周期改为 GameRenderer 调用 wrapper，benchmark 记录实际 world 状态并拒绝未执行的 EXCLUSIVE；新增 rtWorldReason / requested reconstruction 与 acquire/extract/present/limiter 墙钟 scope。首段53.6 FPS、后续29.9–30.0 FPS与60秒AFK限30帧高度吻合，自动测试期间刷新输入计时器防止AFK；349项回归通过。GPU 生效与收益仍待客户端验收。[完整分析](performance/ALPHA-41-FRAME-ANALYSIS.md)。

## 0.39.0-alpha.41：Caustica 式帧执行链

[alpha.41 安装包](https://temp.sh/AVRLK/voxellight-client-kit-26.2-0.39.0-alpha.41.zip)；SHA-256：`f1be056bead3f14f0d4afc51f22e1feca1a7f30b9a408cf06bb718435c28083a`。

- RT 输出成功后跳过原版世界 frame graph，保留 section 编译/上传/occlusion/加载维护；失败同帧继续原版。
- Vulkan 原生 DLSS RR Performance、SDK 输入尺寸、8 平面 AOV/guide、Halton jitter；正常帧无 CUDA/OptiX exchange，无帧生成，失败回退原重建。
- 实时单次遍历透明阴影；Reference 保留 EXACT。Iterative B1–B5 一次 dispatch 可 A/B，Wavefront 默认待实测。
- `rt_benchmark frame` 三组双轮 ABBA，schema 12；世界总 GPU 时间与客户端墙钟耗时分开导出。
- `build clientKit` 通过：348 项测试零失败，80 个 SPIR-V stages / 48 份 shader source hashes 核对；真实 GLSL RR motion/depth 数值 fixture 和打包的 Linux JNI extension query 通过。
- RR 镜面运动向量、透射 endpoint guide 尚不完整；性能与画质待 RTX 实机确认。[实现、构建与验收](performance/CAUSTICA-FRAME-PIPELINE.md)。

## alpha.40 上传结果与 Caustica 差距

8 段有效完成，但第二轮 GPU 执行状态广泛改变、重复波动 55.22%，clean FULL 对照为 `not_comparable`；第一轮描述性改善约 1.33%，未证明大 footprint 收益。用户报告本项目二十多 FPS、Caustica 170–180 FPS，无 FG、DLSS 性能档、1 spp/4 bounce。

源码确认本项目尚未取消原版 LevelRenderer，实际 PT 输入仅 214×120，OptiX beauty + 自研放大与 DLSS RR 不同；透明 visibility 有序最多 24 界面，而 Caustica 实时 shadow 使用单 traversal 透射近似；间接 dispatch/状态组织也不同。下一阶段应先全帧测量、RT world takeover 与 reconstruction 契约，再推进 cache，而非继续用 transport 小收益解释 FPS。[实测、源码依据与优先级](performance/ALPHA-40-CAUSTICA-ANALYSIS.md)。本次只写分析，不发布新 jar。

## alpha.40 Hot Shader Cleanup

[alpha.40 安装包](https://temp.sh/JZalv/voxellight-client-kit-26.2-0.39.0-alpha.40.zip)；SHA-256：`4a42d0b2888539ccdfc1be6ec354ac1d3694c6cc6682fd143e5588184fac8303`。最终 jar 的 58 个 stage / 46 份 shader 源文件哈希已核对。

实现独立 FULL/Realtime、RIS/Legacy SPIR-V 变体（58 stages），默认 CLEAN；原 runtime shader 专用于 footprint 对照。HYBRID profile off 只采 alive，八条有效曲线后冻结生产掩码。`rt_benchmark shader` 比较同场景 runtime FULL / clean FULL；`hot` 先按算法测队列后用稳定赢家比较 SPARSE/CACHE_SPARSE。Realtime 不再强制 COMPACT，所有 suite 固定整轮 renderer 光照/天气/手持灯/水面时钟，schema 11 导出快照和队列选择。

本轮不改算法，不实现 Primary split / B0 cache / NRD。342 项 Java 测试、58 个 RT SPIR-V stage、Minecraft GLSL 链接和生产 Slang 数值回归通过；CPU/SPIR-V 通过不能替代 GPU 实测。[设计与操作](performance/HOT-SHADER-CLEANUP.md)。

## alpha.39 最新实机结论

最新 `rt-suite-1791359324298.zip` 完成 24 段：SPARSE 两轮 transport 改善约 6.79%，CACHE_SPARSE 约 6.33%；组合 cache 命中 21.69%、训练写入成功 99.41%、复用 15.40%，原始 LIGHT 事件未再触发 scene reset。CACHE 单独跨 SUN→NONE→MOON，不采用其总收益作为算法证据。修复有效，但原第三轮大幅提速与 Reference 画质目标尚未验收，FULL 默认继续保留。[完整分析](performance/ALPHA-39-REALTIME-ANALYSIS.md)。

## alpha.39 修复与复测

[安装包](https://temp.sh/CxmJq/voxellight-client-kit-26.2-0.39.0-alpha.39.zip)。337 项 Java 测试、24 个 RT SPIR-V stage、生产 Slang 数值回归与最终 jar hash 校验通过；安装包 SHA-256：`d02043484ea3d6ca8e242b56eef8ffdcbe9775bb7852e9ddf3a94cb0d4064cd8`。

已落实 alpha.38 审计中的失效边界、原始事件误重置、同 cell 多平面争用、训练后才拒绝重复候选、quad 三角形历史失配与低置信度邻居选择。新增平坦 diffuse primary 的延迟 frame 准备和 schema 10 细分统计。实际驻留静态 RT 内容改变仍协调清空 cache/reconstruction，保留遮挡/间接光正确性；不是只失效被编辑 cell。FULL 默认不变，第三轮仍待 RTX 净收益和 Reference 画质验收。[实现、边界与操作](performance/ALPHA-39-REALTIME-FIXES.md)。

## alpha.38 深入审计

确认失效入口重新合并了 LOAD/GEOMETRY/LIGHT/RESOURCE，甚至对未跟踪 section 也 record；缓存与重建同时使用全局 revision。实际 Java 审计确认 max+1 / <= 的边界不一致，以及整列 MAX_VALUE+1 溢出。局部失效之前必须先修复这些契约。

Cache key 缺同 cell 多平面身份，训练结束后才丢弃约三成多提议；immature 在空间匹配前检查，不能把其 55% 全部解释成正确 cell 样本不足。Sparse 更主要卡在置信度和身份保留；受保护路径约39%，渐变时隔帧复用的理想全局密度下限约0.70，不能靠 sparse 单独兑现0.25–0.6。[深入定位、修复顺序与验收边界](performance/ALPHA-38-DEEP-ANALYSIS.md)。本轮没有修改渲染代码。

## alpha.38 复测：工作量改善，但尚无净提速

24 段有效。CACHE / SPARSE 重复波动约 36%，不可比较；组合组两轮稳定，整体 -0.356%，处于 0.36% 波动范围内。组合 cache 命中约 2.74%、sparse 复用约 10.17%，完整路径密度仍约 0.898；primary 与历史成本抵消 B1/B2 节省。

细分计数指向缓存未成熟、均值误差、训练锁/空间冲突与 sparse 置信度不足；TTL 拒绝已很少。多数剩余全局失效来自 scene/edit，当前任意区域 revision 都会清空全局缓存，需要局部失效和训练覆盖调整。第三轮尚未验收通过，保持 FULL，无需重复同一版本。[完整分析](performance/ALPHA-38-REALTIME-ANALYSIS.md)。

## alpha.38 调整 realtime 缓存与历史

根据 alpha.37 的低命中/低复用结果：realtime 光照失效与 Reference 分离，日光渐变持续刷新，约 6°累计漂移/强度 25% 漂移、突变、编辑、手持灯/介质切换仍 hard reset；统计重置来源。缓存按均值误差准入、保留 24 次成熟门槛，TTL 128/256 帧，固定/渐变窗口 128/64 次；冷/未成熟 cells 刷新优先级提高，probe 仍最多 1024/帧，同生命周期锚点不漂移。

Sparse 先保存真实 path 的颜色/AOV 均值，再用均值误差和真实更新数调度；渐变更新帧最多隔帧。镜面、动态、介质保留完整路径，不写无用 sparse 历史；几何距离检查去掉开方。Reference 的完整路径、原 RtLightingChange 和累积不变，默认仍 FULL。

Benchmark schema 9、28 个兼容追加计数、具名 reasonCounters，区分成熟/过期/空间/误差、训练失败/锁/冲突、sparse 置信度/误差。`build clientKit --offline` 通过：332 项 Java 回归、24 个最终 RT stages 与生产 Slang fixture 通过，安装包所有 stage/source 哈希已核对；GPU 净收益和 reference 画质对照仍待新版实测。[设计与边界](REALTIME-PT-ROUND3.md)。

## alpha.37 复测：缓存入口已修复，第三轮仍未达标

24 段有效完成。CACHE / SPARSE / CACHE_SPARSE transport 改善分别 -0.23% / -1.44% / -3.31%，均 within_variation，没有可确认净收益。缓存查询已发生，但单独缓存命中率约 0.197%、组合约 0.55%；sparse 完整路径仅减少约 1.25%、组合约 4.31%。primary/历史成本仍抵消省下的追踪工作。

单个测试段 runtime epoch 增加 2–7 次；普通日光推进能触发约 1° 阈值的全局失效，与 24 次成熟观察/短 TTL 的策略形成冲突。还需拆分拒绝原因，区分单样本方差与均值误差、置信度不足与高方差，改进渐变光照刷新和训练覆盖。当前建议 FULL，无须重跑同一版本。[完整定位与下一步边界](performance/ALPHA-37-REALTIME-ANALYSIS.md)。

## alpha.37 第三轮未生效定位与修复

alpha.36 实机 24 段完成：cache 约慢 0.40%，sparse 约慢 2.05%，均判定 within_variation；组合组重复波动 17.68%，不可比较。缓存所有查询/训练/命中为零，稀疏仅复用 0.39–1.40% 的路径，第三轮未达到目标。

默认 RG8 法线 (128,128) 解码后的法线点积约 0.9999846，原准入要求 >0.999999，导致普通平面被全部排除。改为量化容差 >0.9999，BSDF 解码保持原样。历史先检查最近像素，失败时查重投影附近 2×2 texels，身份/平面/epoch 校验保持；只有纯 diffuse 使用反照率去调制的亮度统计与颜色重调制，暗通道变化回退，ROUGH_DIFFUSE 保持原颜色检查和镜面能量。

新增每段 `.realtime.json` 和 summary 的 `realtimeCoverage`，直接报告路径密度、复用率、缓存命中率及查询/终止是否发生，零分母为 unavailable。缓存候选段零查询时提示算法未得到验收。生产 shader 回归覆盖准入、邻域匹配、纹理 diffuse 和 rough specular 保护。当前没有 NVIDIA GPU 提速证据，必须复测 `rt_benchmark realtime` 并单独检查画质。[完整分析](performance/ALPHA-36-REALTIME-ANALYSIS.md)。

本轮 `build clientKit --offline` 通过：328 项 Java 测试零失败；24 个最终 RT stages 与全部源码哈希已核对，生产 Slang 回归通过。安装包只含本 mod。

## alpha.36 第三轮 realtime PT

已接入 B1/B2 diffuse tail 的世界 L0/L1 SH 缓存，成熟/方差/平面/法线/epoch/TTL 校验，精确后缀训练与 bounded probe budget；加入首命中重投影校验后按置信度与方差进行 1/2/4 帧完整路径调度。Reference 强制旁路，镜面、玻璃、水、动态和不稳定表面继续 exact。默认 FULL，`rt_realtime cache_sparse` 显式开启；`rt_benchmark realtime` 自动测试三项独立 ABBA、24 段，schema 8 导出算法工作量。

`build clientKit --offline` 最终通过：327 项测试零失败；24 个 RT stages、ABI 与生产 shader 数值测试通过，安装包 stage/source hashes 已核对。

当前编辑/光照采取全局逻辑失效，粗糙镜面不会直接用 diffuse SH 替代，高方差每帧采样但不擅自增加 spp。首命中仍每帧检查，多 spp 不启用 sparse。构建/CPU 数值验证不代表 RTX 目标已实现；本版 GPU 性能和 reference 画质对照尚待客户端。[完整设计、近似边界与验收](REALTIME-PT-ROUND3.md)。

## alpha.35 BLAS 更新与 scratch 优化

实机 BLAS ABBA：8 段有效，scene commit 3.155→0.377 ms，native BLAS 2.979→0.208 ms；refit 数不变、scratch barriers 每 commit 约157→3，属性-only 跳过未命中。保留 optimized。其他 scope 存在轮间变化，不能把 BLAS 的88%降幅当作整帧收益。[完整结果与限制](performance/ALPHA-35-BLAS-ANALYSIS.md)。

动态位置/geometry ranges 不变时，shader 属性继续上传但跳过 BLAS refit；独立 AS 更新使用共享 arena 的对齐、不重叠 scratch slices，保留跨提交和空间复用依赖。校准 native BLAS 计时，保留外部 submit span；新增按类别/原因的累计 AS 与 scratch 计数。`/voxellight rt_benchmark blas` 8 段专用 ABBA，比较 scene commit、保持 RIS/Query/Fixed/OMM off/SER off，默认 optimized，可回退 legacy。具体收益与客户端视觉待实测。[实施与验收](performance/RT-BLAS-OPTIMIZATION.md)。

## alpha.34 统一直接光 RIS

BLAS 定位：218 个动态分组，每次 commit 平均 153.96 次主 BLAS refit、0.653 次 build；并非每帧重建全部地形。需要校准 native 计时，区分位置/属性变化，检查小型动态分组与 scratch 串行复用。[定位报告](performance/ALPHA-34-BLAS-DIAGNOSIS.md)。

实机 direct A/B：8 段全部有效，transport 7.899→5.676 ms（下降 28.15%，两轮波动 2.33%），新旧比 0.7185；连接数减少且 alive 曲线基本一致。本场景收益合理，保留 RIS；其他场景画质/方差尚未验收。[完整分析](performance/ALPHA-34-DIRECT-ANALYSIS.md)。

原生 flame、emitter 与环境光合并到 section-local / spatial hierarchical alias 和 fresh RIS；Sun/Moon 与可选手持灯独立连接。每次 surface hit 最多三个直接光连接，深层采用带补偿 NEE roulette；primary/secondary/deep 预算 8/4/1。时间自适应只改变 proposal，保留全局支持；同步修改 BSDF-hit / miss MIS，PathHot 保持 64B。新增 `/voxellight rt_direct legacy|ris`（默认 RIS）和 `/voxellight rt_benchmark direct` 两轮 ABBA、8 段专用测试，导出 per-bounce 连接/候选计数。实际提速和视觉待 RTX 验收，0.5–0.7 transport ratio 尚未实测。[详细设计与验收](performance/RT-DIRECT-LIGHTING-ROUND-2.md)。

## alpha.33 原版 atlas 动画误伤 OMM 修复

`build clientKit --offline` 通过，309 项测试零失败；24 个 RT stage/ABI 与输运数值验证保持通过。新动画 hook 的实际 GPU/Mixin 启动及 OMM 效果仍待 RTX 实测。

alpha.32 用户 RTX 结果完整运行 48 段，296 个静态 section 和版本签名全程一致，所有段 workload 校验通过；Query 相对 TraceRay 22.58% 更快，相对 Legacy 9.64% 更快；全 Compact 8.29% 更慢；HYBRID 改善 2.71%，仍在 3% 判定阈值内。SER 重复段波动 46.34%，不可比较。上述只代表该场景/设备的当前版本控制 A/B，不是版本总加速。

OMM 仍因 `knownOpacityTexels=0` 被跳过。根因是 26.2 `TextureAtlas.uploadAnimationFrames` 使用 atlas render pass 仅绘制动画 sprite，而旧 render-target hook 按整张 mip0 失效，导致任一动画更新清空静态 coverage。现在仅在精确原版动画上传作用域内保留预先已知的静态分类；动画 sprite 本来就是 unknown。非原版 atlas render pass、直接纹理上传、清屏和其他纹理仍保守失效；mip>0 render target 不再误写 mip0。纹理版本计数照常推进，实际动态 albedo 不冻结。作用域异常退出后恢复，CPU tests 和实际 26.2 bytecode 合同校验覆盖该行为。

驱动已启用编译统计捕获，但返回零 executable；无法从此结果得到 register spill、L1/L2 流量。OMM 修复需要新版 RTX 对照验证，执行层仍未冻结。当前建议该场景使用 Query / Fixed / OMM off / SER off，HYBRID 为测试候选，不将约 2.7% 宣布为稳定收益。

## alpha.32 快照完整性、HYBRID 后段调度与 OMM 有效性

本地 `build clientKit --offline` 通过，306 项 Java 测试零失败；24 个 RT SPIR-V stage/ABI 与真实 Slang 输运、混合 mask queue fixture 验证通过。CPU fixture 不验证 GPU 原子并发。

- 自动测试固定选择最近的完整静态 section，预算 60 MiB，预留 4 MiB 动态几何空间；数量和精确版本签名未全部装入就不开始采样，修复 alpha.31 首段多一个 section 导致 visibility 对照无效。
- 增加 `rt_queue hybrid` 与独立 `hybrid_queue` 两轮 ABBA：只对中等规模的 bounce 压缩，接近全屏和极小队列保持 fixed。由真实 alive 曲线决定 mask，AUTO 与默认控制不变。
- OMM atlas 写入只失效受影响区域；动画 unknown 写入不复制整张 CPU 网格。静态 opacity epoch 变化后比较保守索引，仅重建分类变化的 BLAS，防止过期的硬件覆盖分类；混合 alpha 保留 any-hit。
- 预热要求最近 30 个无计数器 GPU batch 的两半中位数在 10% 内；重复 A/B 波动大于 10% 标为不可比较，避免将 alpha.31 的 SER 约 59% 漂移称作普通噪声。
- `pipelines-status.json` 明确驱动编译统计为空的原因，缺失 spill/缓存数据不作零值。本机无 NVIDIA GPU，HYBRID、OMM、SER、画面等价及硬件统计仍待新版实机验收，不能承诺 1.3–2× 或冻结。

## alpha.31 TraceRay/Compact 执行修复与测量干扰控制

针对 alpha.30 单场景数据，TraceRay 采用 scalar solid-shadow 单次阻挡查询，cutout alpha 与 transmission 过滤使用专用 hit records；Compact 删除冗余 Max，计数和 indirect width 合并为同一原子字段。计数与 replay 只每八帧启用，A/B 汇总排除这些帧，raw CSV 与 counter_frames.json 保留追溯依据。未改变原 estimator 的材质/介质处理，CPU 数值与队列索引检查纳入构建。RTX 加速效果仍未测量，也不能直接与 alpha.30 受每帧计数干扰的数据推算版本加速。

## alpha.30 SER 初始化与直接收益验收

根据 alpha.29 实测，前三组工作负载一致且完成两轮：TraceRay 较 Legacy 慢约 19.3%，Query 较 TraceRay 快约 18.9%，Compact 较 Fixed 慢约 18.6%。这些是用户单场景、214×120、1 spp、带诊断计数条件下的描述性 transport 数据，不能推出全局默认或总体 FPS 收益。SER 初始化耗时 51.9 秒触发了错误预热超时。

修复实际执行 revision 可用性判断，初始化等待与就绪后预热计时独立；增加 Query 对 Legacy 的直接两轮 ABBA，保留其他组及固定地形快照。SER 性能、Query 对 Legacy 的直接收益仍待新包 RTX 实测。

## alpha.29 自动验收工作集修复

针对用户 alpha.28 结果包及日志中 visibility B 场景重建后预热 30 秒超时，测试整轮保存并复用当前驻留地形快照，阻止 miss 页和动态扩容替换固定地形；动态模型/光照继续实时更新。新增逐秒 warmup.json 和细分超时原因。保留严格工作负载检查，未把无效采样放宽为成功。完整 RTX 跑完验收待用户实测。

## alpha.28 自动 A/B 验收

新增 `rt_benchmark start/status/stop`，按设备能力执行两轮 ABBA，隔离采样帧并接收延迟 GPU 数据，导出原始 CSV、状态、汇总与 ZIP；工作负载变化或波动范围内不宣称收益。完整说明见 [自动验收](performance/RT-AUTOMATIC-BENCHMARK.md)。执行层算法保持 alpha.27，RTX 实测待用户运行。

# 0.39.0-alpha.27 — Vulkan RT execution round 1

Material section BLAS now contains OPAQUE/CUTOUT/TRANSMISSION ranges with matching geometry-index SBT records. Opaque traversal bypasses any-hit; a separate opaque visibility AS supports a 4-byte first-hit TraceRay payload and an optional Ray Query variant. PathHot is 64B (was 144B), radiance/AOV accumulate outside continuation, and one scene scratch arena replaces per-AS scratch. Queue AUTO joins delayed GPU timings with real alive curves, with forced fixed/compact A/B controls. OMM uses conservative triangle special indices and exact unknown any-hit fallback; mixed-alpha subtriangle baking is not implemented. SER is an explicit, feature-gated final A/B, default off.

Profiling replays a bounded set of real visibility rays with alternating TraceRay/Query order and mismatch counters. Driver compiler statistics export when available; L1/L2/runtime spills require external GPU profiling. Numerical/unit/shader validation does not replace RTX visual/performance acceptance; 1.3–2× and execution-layer freeze are not claimed. See [implementation and acceptance protocol](performance/RT-EXECUTION-ROUND-1.md).

# 0.39.0-alpha.26 — recover fragmented scene geometry ranges

Alpha.25 cleared output history successfully in the reported run, then fell back to raster when allocating shader geometry ranges. Allocation now releases all deleted and resized ranges before allocating any incoming/replacement range, preventing a growing mesh from exhausting capacity while later shrinking meshes still occupy their old ranges. If final geometry fits the admitted budget but holes cannot fit an incoming range, the attribute arena is repacked under the existing GPU read-to-write dependency. This exceptional path recopies attributes/normals, updates TLAS custom indices, rebuilds emitter proposals and invalidates reconstruction/previous-pose history. Ordinary updates retain stable offsets; surviving surface identities are preserved. Stats exposes geometryCompactions.

Regressions exercise growth/shrink at full capacity, fragmentation with sufficient total space and rejected oversized layouts without mutation. This addresses the reported allocator exception; NVIDIA driver/visual/performance acceptance remains pending.

# 0.39.0-alpha.25 — allow clearing temporal upscale history

Alpha.24 successfully initialized Vulkan RT on the reported RTX 4060 Laptop GPU, then failed at the first output-history clear: Minecraft 26.2 requires both RENDER_ATTACHMENT and COPY_DST for clearColorTexture. Output HDR history textures now include COPY_DST, preserving sampling/rendering flags and RGBA32F history. This fixes the reported validation exception that forced raster fallback. A regression executes the actual Minecraft CommandEncoder validation with a stub backend, and verifies the old flags fail before reaching the backend. Driver rendering and visual acceptance remain pending.

# 0.39.0-alpha.24 — persistent scene, GPU continuation queues and motion reconstruction

Terrain and dynamic changes are collected before one scene commit. Shader geometry uses stable free-list ranges; dynamic object identities and local mesh translations replace texture-only grouping. Same-layout BLAS/TLAS updates reuse AS storage, scratch and vertex/instance buffers. Native Vulkan texture-write versions avoid unchanged crop/albedo uploads; terrain-generation caching avoids rebuilding static emitter proposals for dynamic motion. Persistent fenced descriptor slots replace per-frame pools.

Continuation storage separates 144-byte hot state from 288-byte cold media, retaining the 432-byte/path capacity. On devices supporting indirect tracing, workloads of at least 65,536 paths use two bounded GPU active queues with original path indices; smaller workloads keep fixed dispatches. This threshold is provisional. Primary guides use the actual jittered hit, eliminating a second visibility ray. Compatible dynamic surfaces map barycentrics through previous geometry/translation; stable identities reject replaced topology. View models appear in primary visibility and are excluded from world continuation/shadow rays.

Realtime beauty denoising now feeds a separate output-resolution RGBA32F temporal upscaler. Pass exports contain real frame/scope/parent IDs, dimensions, spp and scene generation; sampled ray counters export separately. Buffer allocation/retirement counters cover owned Vulkan RT buffers only. See [implementation and remaining design work](architecture/IMPLEMENTATION.md) for exact ABI/memory contracts and limits.

Build, shader-link, SPIR-V and CPU transport validation passed. No NVIDIA GPU is available on this host: driver synchronization, visual motion/upscale quality and matched-workload performance remain pending. NRD/DLSS RR, ReSTIR, complete separated-signal reconstruction, render-origin rebasing, advanced coverage/LOD/cache and caustics are not implemented by this release.

# 0.39.0-alpha.23 — decouple cropped textures from dynamic BLAS groups

Alpha.22 fixed sprite detail and terrain relocation but incorrectly used each cropped texture tile as a geometry group identity. The new profile showed 86 dynamic groups versus 7 previously, with BLAS CPU median rising from 1.296 to 8.874 ms. Dynamic groups now use the original native texture view plus hand/world category; each triangle retains its independent crop tile in its material flags. Animated source views keep stable group IDs, inactive IDs are reclaimed, and accepted triangle texture slots are tracked separately from geometry groups for uploads. Cropped sprite detail, HUD projection and terrain-first packing remain intact.

The submitted alpha.22 profile confirms geometry copy GPU median 0.034 ms versus alpha.21 2.017 ms; last copied bytes 369,360 versus 63,688,080. It also shows regressions: whole-world GPU median 33.117 ms versus 16.710 ms; RT batch 23.718 versus 10.717 ms; OptiX exchange 3.958 versus 2.135 ms; cropped texture upload 1.593 versus 0.178 ms. These are observed timings, not controlled causal comparisons: both final statuses report 214x120 and 1 spp, but alpha.21 retains an earlier 8-spp diagnostic and per-pass exports lack per-dispatch spp/clock metadata. More groups directly explain increased BLAS work; the full GPU slowdown needs matched scene/sampling and GPU clock measurements after regrouping.

The selected fix reduces BLAS grouping without reverting the visual correction. It does not yet eliminate per-frame crop tile uploads, add transform-only instances, integrate NRD/DLSS or replace scene packing with an allocator. New status `rtDynamicTextureTiles` is independent from `rtDynamicGroups` so future profiles can distinguish texture and geometry workloads.

Validation includes two distinct crop/material slots sharing a world BLAS identity while hand geometry remains separate, existing sprite/FOV tests, native GLSL binding checks and transport regressions. RTX quality/performance acceptance remains pending; this build host has no NVIDIA GPU.

# 0.39.0-alpha.22 — held texture and projection corrections

Dynamic atlas textures now crop the captured model UV region before resampling and remap triangle UVs to that region. A 16-pixel item sprite in a 4096-wide atlas retains its own 128-pixel tile instead of collapsing below one pixel. Small entity textures retain whole-texture slots. First-person native geometry compensates for HUD versus world FOV before native item transforms, preserving its screen footprint when the world FOV changes.

Packed geometry orders static terrain before animated groups, with the same order used for normals, TLAS custom indices and emitter references. Dynamic size changes consequently relocate dynamic groups rather than the terrain suffix. Terrain eviction/edit size changes can still relocate terrain; a free-list allocator and per-object instancing remain future work.

The uploaded alpha.21 profile contains 611 GPU batch samples: median 10.717 ms, P95 13.986 ms; OptiX exchange median 2.135 ms, P95 2.607 ms. These are pass timings from the uploaded run, not isolated fixed-spp comparisons: the exported status says 214x120, requested 1 spp, while its retained diagnostic reports an earlier 8-spp frame. Scene CPU median 7.260 ms includes two scene records per rendered frame; it must not be interpreted as full-frame time. Last geometry copy was 63,688,080 bytes. Further performance comparisons require matched resolution and spp.

Validation adds atlas sprite footprint, HUD/world projection equivalence and actual native GLSL pipeline binding checks. No NVIDIA GPU is available on the build host; held appearance and reduced geometry transfer need RTX acceptance. Glint, full dynamic LabPBR and first-person hurt/view bob parity remain pending.

# 0.39.0-alpha.21 — remove redundant work and stabilize reconstruction

PT bypasses raster material/entity captures, cascade shadow preparation, AO, water reflection, volumetric and raster composites; world light parameters, native PBR assets and HDR sky remain prepared. Vanilla rendering remains available during RT warmup/failure. OptiX and Vulkan reconstruction are mutually exclusive per frame; the temporal AOV model now denoises only the beauty layer that is actually displayed. External staging is six float4 planes rather than twelve, and only one previous output plane is retained.

Dynamic native quads are grouped by stable texture slot and hand/world category. Same-count dynamic geometry uses out-of-place BLAS UPDATE from an ALLOW_UPDATE build; topology/count changes build a replacement. Packed shader geometry/normal buffers persist, copying only changed or relocated sections. Queue barriers preserve previous-frame shader reads before buffer overwrites. Texture uploads copy only active 128² tiles directly into packed asset cells, avoiding a full 2048² atlas copy. Stats expose `rtDynamicGroups`, `blasRefits`, `geometryCopyBytes` and `rtDynamicTextureCopyBytes`. This is grouping/refit, not per-object transform-only instancing; animated CPU extraction and TLAS rebuilding remain.

Realtime guides use one stable pixel-center visibility ray independent of noisy sample jitter. That extra ray is disabled in reference mode. Camera-space normal, previous-to-current pixel flow and confidence are produced in one three-target pass. Reprojection validates surface-plane distance and pixel footprint, materials and albedo; sky history follows rotation. Dynamic objects conservatively reject history until true object motion is available. Specular/transmission motion, temporal upscaling and NRD/DLSS integration remain pending; low internal resolution is still visible after denoising.

`profile on` now records primary, bounces 1–5 and sample resolve directly inside the same Vulkan command buffer, plus geometry copying, dynamic capture, guide conversion and OptiX exchange. No added RT dispatch/submission is needed for timestamps. Export `.passes.csv` after a fixed-resolution run; the OptiX exchange timing includes the external wait, not a pure CUDA kernel-only measurement.

Validation includes actual GLSL-to-Slang CPU fixtures for pixel flow direction, surface-footprint acceptance, plane/disocclusion rejection, dynamic rejection, normal/material/albedo validation and sky rotation, plus actual Minecraft MRT output-order validation and existing transport tests. Driver-level BLAS refit, native synchronization, visual quality and performance remain RTX acceptance gates; the build host has no NVIDIA GPU.

References: [Vulkan AS update constraints](https://docs.vulkan.org/spec/latest/chapters/accelstructures.html), [OptiX denoiser layer contract](https://raytracing-docs.nvidia.com/optix8/api/optix__host_8h.html). No driver performance gain is claimed from CPU tests.

# 0.39.0-alpha.20 — realtime reconstruction and batched Vulkan transport

Vulkan retains all tracing. The independent OptiX Temporal AOV helper consumes GPU beauty/albedo/camera-normal/flow/trust and diffuse/reflection/refraction inputs through external memory and binary semaphores; failure retains Vulkan temporal/spatial filtering. Moving-camera history validates RT positions, normals, materials and light changes. Reference progressive accumulation is separate (`rt_mode reference`), with explicit freeze.

Multi-spp uses one camera upload, batched primary and five continuation passes, one sample resolve and one radiance copy. Separate path banks obey the device storage-buffer range; stats report actual internal resolution and memory caps. Flame connections retain nearest two plus one visibility-tested RIS selection, independently of held lighting. A compressed CPU page cache and asynchronous miss requests prioritize loaded terrain within the bounded GPU working set.

Native entity/block entity, first-person hands/items, custom quad and cutout quad particle geometry now enters the Vulkan scene using native texture/tint diffuse materials. Native hand overlay is suppressed only after successful PT display and hand capture. Transparent/additive particles, glint and full dynamic LabPBR parity remain pending. Responsibilities are split into VulkanPathTracer, RtDynamicScene, RtReconstruction, RtDiagnostics and scene/cache owners.

Validation: 254 Java tests, 14 SPIR-V stages, Minecraft GLSL linking, actual Slang numerical parity and 200,000 flame RIS samples with occlusion passed. Both host-only denoiser bridges build. This host has no NVIDIA GPU: interop, visual quality, dynamic coverage and frame-time acceptance still need RTX testing. DLSS RR and optional OMM/SER remain deferred until reconstruction profiling, as requested by the review.

# 0.39.0-alpha.15 — independent held-light connection

Alpha.14 held lighting still failed user visual acceptance. Its delta point light shared stochastic selection with sun/sky, allowing high environment power to starve held connections. It now has a separate deterministic inverse-square connection at eligible surfaces and medium events; sky/sun distribution and complementary miss MIS exclude the delta source. Visibility and material shading remain intact; no light leaks or unconditional full-screen brightness are substituted. Status adds main/offhand item IDs and virtual source position to distinguish recognition from transport failures. Visual acceptance remains pending; no RTX GPU is available on the build host.

Regression executes actual Slang with held on/off, black and million-unit sky maps and verifies discrete PDF=1 and analytic Lambertian held irradiance independent of sky power. Existing Material 3, terrain, environment MIS, invalid accumulation and emitter sampling checks remain required.

# 0.39.0-alpha.14 — live accumulation, numerical rejection and emissive NEE

Default history now continues tracing at its sample target with bounded EMA and live sky/held/water/albedo assets. Lighting signatures invalidate large changes; explicit `rt_accumulate freeze on|off` preserves opt-in frozen snapshots. Held native BlockItem lights are independent of raster local-light enablement and use level/15 × 20 scene-linear point intensity.

Equal-IOR dielectric transmission is a straight-through delta; grazing Fresnel and large-PDF MIS avoid 0/0 and overflow. Nonfinite path contributions flag invalid alpha; the HDR mean rejects bad samples per pixel and clears corrupt history. Filmic mapping uses stable large-HDR arithmetic; invalid display fallback no longer paints red.

Native emissive triangle metadata is cached per section and rebuilt in the TLAS ordering, with nearest-first 8192 table admission, global triangle/instance IDs, world transforms and area×emission CDF. GPU Material 3 emission and cutout are evaluated at the sampled point, visibility handles transmissive interfaces, and emitter hit PDFs provide complementary surface MIS. Table uploads follow scene generation; no full geometry CPU duplicate or image readback is added. LabPBR-only emitters without native emission levels, dynamic geometry, full medium free-flight and AOV reconstruction remain pending. RTX visual acceptance is still required.

# 0.39.0-alpha.13 — Vulkan-only tracing and stationary accumulation

User authorized deletion of old tracing after alpha.12 acceptance. The current runtime and kit contain no OptiX/CUDA tracer, JNI tracing bridge or runtime PTX compiler. Historical legacy-removal gates below are superseded by this authorization. Canonical BSDF/material/environment mathematics remain parity fixtures; unchanged water math is extracted into `native/rt/water_surface.h`. Independent `native/denoiser/temporal_aov.h` preserves temporal diffuse/reflection/refraction AOV invocation (SDK syntax-checked), but no denoiser is connected to Vulkan output yet.

Stationary linear HDR averaging defaults to 64 spp, configurable 4–4096 with `rt_accumulate spp`; `on/off/reset` control history. Moving/rotating, projection/size changes, scene generation/block invalidations and resource/world/backend resets reject history. Increasing target retains samples. Two RGBA32F ping-pong attachments keep history entirely on GPU; sample zero never reads stale history. Sky/light/animated albedo/water assets freeze for each snapshot. Scene updates/warmup continue after convergence, and accepted changes restart tracing. Normal view bypasses accumulation; no motion reprojection/denoising is claimed. Old preferences migrate, UI progress and kit instructions reflect the new path.

Check stationary convergence, raising target, movement, edits, F3+T, resize, disable/re-enable on RTX. Remaining emitter NEE, dynamic geometry, reconstruction and performance gates still apply.

# VoxelLight current state

## 0.39.0-alpha.12 — shared environment and direct-light transport

[Download alpha.12 client kit](https://temp.sh/porVb/voxellight-client-kit-26.2-0.39.0-alpha.12.zip) (temporary link; select `rt_backend vulkan_pt` on Vulkan).

Alpha.11 terrain admission passed user acceptance, including previously missing coverage. Alpha.12 advances the explicit `rt_backend vulkan_pt` experiment; production RTX Quality still uses the accepted legacy path.

- Shared 256×128 RGBA32F HDR sky: the existing `rt_environment` shader and `LightingEnvironment.polished` palette supply world time, sky/horizon/sunset, weather, stars and bounded cloud radiance. The fixed test sky/sun is removed from the material path. Geometry-only `vulkan_transport_test` retains its intentional test environment.
- GPU luminance × exact lat-long solid-angle CDFs, with conditional row/column binary sampling and uniform-cos(theta) cell sampling. Black maps fall back to uniform sphere. All map/CDF generation and transfer remain on GPU; no new frame-image readback or OptiX/CUDA call. Two bounded fragment reduction passes rebuild the distribution each frame; profile `vulkan_rt_environment_map` and `vulkan_rt_environment_distribution` separately.
- One power-weighted direct-light choice per nonsingular surface: current sun/moon finite cone (6.793e-5 sr), importance-sampled HDR environment, or enabled held virtual point light with inverse-square intensity. RGB visibility retains cutout/transmissive interfaces and now stops at finite point-light distance. Power-heuristic MIS pairs environment/sun NEE with BSDF misses; delta events and discrete points retain unit weights. The terminal sixth vertex uses unit NEE weight because there is no competing BSDF continuation. Emissive terrain is still BSDF-hit-only and is not counted by this light distribution.
- Camera-underwater transport initializes the same quantized water absorption/scattering/IOR/phase coefficients and section-independent identity as water surfaces. Finite-segment first-order scattering samples the same sun/environment/held distribution; it uses no phase MIS because this estimator has no competing phase continuation. An unbounded medium miss is limited to the existing reference policy of 128 blocks. Eight-medium stack and six-bounce/1 spp/recursion-1 contracts remain.

The CPU asset header grows from 96 to 192 bytes; camera and continuation ABIs stay 96/368 bytes. Environment map/cell/row data occupy 1,050,624 bytes in the existing descriptor-6 storage buffer, plus 1,050,624 bytes of GPU textures and a 64-byte palette uniform. No new RT descriptor binding. The overall packed asset cap remains 256 MiB/device storage-buffer range. Scene budgeting/admission is unchanged from accepted alpha.11.

Build/clientKit passed with 267 tests and no failures. Numerical validation runs the actual Slang CPU target against canonical native `environment.h`: 300,000 samples (max normalized native/Slang error 2.31713e-06) cover PDF normalization/poles, conditional histograms, black fallback, finite sun cone, held-light discrete PDF/inverse-square falloff, complementary miss MIS, paired white furnace, terminal-depth furnace and camera-water initialization. The existing Material 3/terrain parity suites remain required. Shipped GLSL environment/CDF pipelines are compiled and linked against Minecraft's actual bind-group contract; twelve SPIR-V stages and descriptor/continuation reflection are validated. RTX visual and performance acceptance for this version is pending.

Test `/voxellight rt_backend vulkan_pt` and `/voxellight stats`: day/night/rain transitions, indoor environment shadowing, metal/glass sky reflections, held torch moving near surfaces, entering/exiting water, terrain edits, F3+T and window resize. Reconstruction remains NONE and 1 spp is noisy. Remaining: emissive-triangle NEE/MIS, exact local cloud shadow transmittance, radiance/caustic caches, dynamic entities, temporal AOV/reconstruction/OptiX-denoiser/DLSS RR, full reference and RTX 4060 timing gates. Shared sky is the existing approximate model, not full atmospheric multiple scattering. No default switch or legacy tracing removal is authorized by this milestone.


## 0.39.0-alpha.11 — camera-prioritized terrain admission

Build/clientKit validation passed: 267 tests, zero failures. [Download alpha.11 client kit](https://temp.sh/UyKdk/voxellight-client-kit-26.2-0.39.0-alpha.11.zip). User RTX coverage acceptance passed (alpha.11).

Alpha.10 user GPU logs confirm material transport produces finite radiance (cold pipeline startup 4,377 ms), but resident input geometry reaches 67,108,080 bytes and some areas never appear. New sections previously could not evict residents at the 64 MiB limit; warmup retries therefore remained rejected. Alpha.11 sorts edits first and new arrivals by camera distance, and admits nearer sections by evicting strictly farther unprotected residents. Eviction is planned atomically; infeasible admission preserves the existing scene. Existing edited BLAS remains until its replacement is built. Camera movement changes admission priority; the existing two-second warmup retry discovers nonresident loaded sections again.

The 64 MiB / 512-section experiment still has finite coverage: this fixes first-arrival starvation, not unlimited world residency. Full scene paging, production environment/light sampling, reconstruction and performance gates remain pending. Validate missing nearby areas, walking/flying into new terrain, placement/destruction, and resource reload with `rt_backend vulkan_pt`.



## 0.39.0-alpha.10 — native terrain Material 3 binding

Alpha.9 primary/indirect geometry transport passed user GPU testing. Alpha.10 adds `/voxellight rt_backend vulkan_pt`, an explicit material transport experiment. `vulkan_poc` (accepted normals) and `vulkan_transport_test` (accepted grey geometry) remain available; production RTX Quality stays legacy OptiX.

- Reuses the 40-byte compiled terrain vertex snapshot: positions, barycentric UV, interpolated normal, tint and flags. Instance custom indices and packed shader geometry share the exact resident-section order. A separate device-local shader-geometry buffer is concatenated with GPU copies on scene changes; static sections still do no AS builds. Edit replacement admission remains the alpha.8 policy.
- GPU albedo, existing PbrAtlas IDs/normals and the exact five-plane Material 3 palette feed the canonical Slang decoder. IDs/palette/normals copy once per resource generation; native albedo animations are copied each frame entirely on GPU. No image is downloaded or mapped. The 96-byte atlas/weather header is CPU control data. Both resource reload and world change release old owners through MC submission retirement.
- Independent nonrecursive closest-hit and cutout any-hit. Closest-hit only reports distance, barycentrics, triangle and instance identity. Any-hit matches native cutout/tint-alpha thresholds and preserves glass/water transmission alpha. GGX/conductor/coating/dielectric/thin-sheet/diffuse-transmission sampling, tangent-space normals, linear albedo/emission, wet coating and original animated water normals run in raygen transport.
- Eight nested media persist across primary/indirect dispatches. Continuous Minecraft water uses a section-independent medium ID so crossing a BLAS boundary does not invalidate an exit. RGB extinction, eta-aware entry/exit, TIR, Russian roulette eta scaling, finite-segment first-order HG sun scattering and bounded 24-interface shadow transmission are active. Environment and emissive surfaces remain BSDF-sampled only, so they are not double counted. Fixed test sky/sun are still explicit; their production distributions/NEE/MIS are the next stage.
- Linear HDR radiance uses the existing reference filmic/sRGB curve at fixed 0.75 EV. One path per pixel/frame, six-bounce limit, at most 640×360; no temporal accumulation or reconstruction. Noise at 1 spp is expected. This is experimental transport, not completed PT parity.

Budgets: shader geometry adds at most 64 MiB to the existing 64 MiB AS inputs; geometry is copied across resident sections on an edit, not rebuilt as extra BLAS. The 368-byte continuation costs at most 80.86 MiB; radiance buffer/texture add 7.04 MiB. Packed atlas limit is 256 MiB and also checked against the device's maxStorageBufferRange, with a native-resolution RGBA8 albedo-copy texture. Profile `vulkan_rt_material_assets`, primary and indirect separately; existing scene/BLAS/TLAS timings remain. These are acceptance-stage budgets; memory/bandwidth optimization and RTX 4060 p50/p95 gates are pending.

Host validation executes the actual Slang CPU target against canonical native Material 3 formulas: existing 57,600 BSDF/medium cases and 32 new raw terrain/atlas cases (1,408 components), including high palette IDs, tint, normal mapping, cutout/transmission exemptions, emission, conductor data and original native water-wave functions. Shader build validates twelve SPIR-V stages, descriptor bindings, the 96-byte camera and both 64/368-byte continuation layouts. GLSL tests link the actual albedo-copy and material-display pipelines. Gradle build/clientKit passed; 265 Java/native contract tests passed. Terrain/water binding comparison max normalized error is 2.98023e-08; BSDF/medium max remains 0.000381917. Actual Vulkan material rendering requires user RTX validation.

[Download alpha.10 kit](https://temp.sh/vKVnP/voxellight-client-kit-26.2-0.39.0-alpha.10.zip). Test after installing on Vulkan:

```text
/voxellight rt_backend vulkan_pt
/voxellight stats
```

Check textures/tints, leaf holes, glass blocks, metal profiles, water reflections/transmission and emissive blocks; place/break terrain and try F3+T/resource reload and window resize. Return to `vulkan_poc` for geometry comparison or `rt_backend raster` for raster. Remaining migration: actual sky/sun/cloud environment distributions, emissive/point/held-light NEE/MIS/RIS, cross-section volume identity validation, radiance/caustic cache, entities, motion/AOV guides, denoising/RR, full reference, optional acceleration features and performance gates. Default switch and legacy trace removal remain gated by parity and RTX 4060 performance acceptance.


## 0.39.0-alpha.9 — geometry transport dispatch acceptance

The alpha.8 edit fix passed user RTX 4060 validation for placement and destruction. Alpha.9 adds `/voxellight rt_backend vulkan_transport_test` without changing that scene update policy or production RTX Quality. Separate primary and indirect RT pipelines advance one bounce per dispatch through a 64-byte, GPU-only continuation record. One jittered path per pixel per frame, six-bounce limit, grey diffuse BSDF, sun visibility rays and Russian roulette exercise the runtime transport/synchronization path. Recursion remains 1; closest-hit/miss never trace rays. Normal view remains available through `vulkan_poc`.

This is explicitly a geometry acceptance test, not Material 3 PT parity. It uses a fixed test sky/sun and grey material; transparent/cutout terrain is forced opaque. No reconstruction, temporal history, material/environment parity, RR/OptiX denoiser or performance gate has passed. At most 640×360 pixels: continuation costs 14.06 MiB, plus existing output buffer/texture; retired on resize/close through MC submission ownership. Profiles separate `vulkan_rt_primary` and aggregate `vulkan_rt_indirect` (five dispatches); do not add those to aggregate scene timings. Runtime performs no CPU image transfer except the existing one-time 32-byte diagnostic.

Validation: Gradle build/clientKit passed, 264 tests passed, seven packaged SPIR-V execution models and reflection ABIs validated. Canonical Slang/native transport parity still passes 57,600 cases / 3,225,600 scalar components (max normalized error 0.000381917). [Alpha.9 test kit](https://temp.sh/MrRxn/voxellight-client-kit-26.2-0.39.0-alpha.9.zip).

Next: bind native vertex UV/tint/flags and GPU albedo/Material 3 palette/normal/environment assets; add cutout any-hit and correct media/transmission, emission NEE/MIS, guides and independent reconstruction. This host has no RTX device; the new transport view needs GPU validation. Default switch and legacy tracing removal remain gated by visual parity and the requested RTX 4060 p50/p95 budgets.


## Unreleased — portable Material 3 transport

The Slang library now ports native BSDF, GGX energy compensation, all eight authored conductor eta/k presets, Material 3 five-plane decoding, dielectric/thin-sheet/water semantics, HG and finite-segment medium sampling, medium identity stacks, cutout thresholds and representable ray-origin offsets. `check` executes the actual Slang CPU target against canonical native code for 57,600 cases / 3,225,600 scalar components; max normalized error is 0.000381917 (gate 0.003). SPIR-V validation passes for the exercised transport functions. Provenance hashes reject stale ports after native semantics change. This is numeric kernel parity, not GPU/image parity: runtime Vulkan still displays terrain normals, primary/indirect integration and reconstruction remain unfinished. This evidence does not satisfy the default backend switch or OptiX tracing deletion gates.

## 0.39.0-alpha.8 — retain edited sections under the scene budget

User confirmed alpha.7 terrain normals and center hit alpha=1. The scene had reached 67,108,800 bytes of its 64 MiB vertex budget. Previously replacing an edited section removed its old BLAS before rejecting a slightly larger replacement, leaving a permanent miss hole. Alpha.8 prioritizes existing-section changes, accounts their net size, evicts distant unaffected resident sections when necessary to fit an edit, and replaces/releases the old BLAS only after building its successor. Oversized/unadmitted updates retain the prior geometry. New admissions do not consume edited-section reservation; unchanged versions still skip builds. The budget counts vertex storage, not total AS/scratch allocations. Tests cover full byte/section limits and replacement net-size behavior; User confirmed alpha.8 placement and destruction remain visible without section-wide misses. Static zero-rebuild and performance gates still need separate acceptance.


## 0.39.0-alpha.7 — nonempty AS geometry builds

Alpha.6 GPU telemetry verified the raygen marker (1, .2, .8, 2), finite direction colors, and center alpha 0; the user saw only miss background. BLAS/TLAS build geometryCount was zero because LWJGL pGeometries sets only its pointer (it shares the count with alternative ppGeometries). Alpha.7 explicitly sets the geometry count before both size queries and build recording. A regression invokes the production build-info builder for triangle BLAS and instance TLAS and verifies count, type, geometry pointer/type and BUILD mode. The GPU diagnostics remain until actual terrain hits and static/edit behavior are accepted.


## 0.39.0-alpha.6 — diagnose black Vulkan RT output

Alpha.5 still showed black despite status reporting 183 section BLAS builds and a completed debug display path. The cause remains unconfirmed. This diagnostic candidate makes ray misses a direction gradient, adds a known magenta raygen output marker and an independent cyan fullscreen border, and logs a one-time asynchronous readback of only two RGBA32F pixels (32 bytes per context, no wait). The marker verifies dispatch/output copying; center alpha distinguishes hit (1) from miss (0); red denotes nonfinite sampled output. This is POC-only diagnostic telemetry, not a reconstruction readback or performance acceptance. No denoising path is changed. Request screenshot plus `Vulkan RT POC GPU diagnostic` log before further conclusions.


## 0.39.0-alpha.5 — RT buffer descriptor writes

Alpha.4 created the Vulkan pipeline and ran the display path without the previous exception, but the user reported a black screen. The RT output SSBO, normals SSBO and camera UBO writes had descriptorCount zero: LWJGL pBufferInfo only sets the pointer, not the count. Alpha.5 explicitly writes the supplied buffer count; a native-struct regression invokes the actual production descriptor builder and checks each of the three bindings, descriptor counts, buffer handles and ranges. GPU output still needs confirmation; pipeline creation alone does not validate tracing.


## 0.39.0-alpha.4 — Vulkan normal display render area

The alpha.3 RTX 4060 log confirms mandatory Vulkan RT extensions enabled and POC pipeline creation in 39 ms, then 6 ms on retry. Display failed with `RenderPassDescriptor.renderArea must be provided`, causing raster fallback. Alpha.4 supplies the full destination viewport to the debug composite descriptor. These pipeline timings are not full renderer startup or PT performance acceptance. Terrain normal output and static/edit BLAS behavior still require GPU confirmation.


## 0.39.0-alpha.3 — preserve terrain on backend switch

The alpha.2 user log confirms Minecraft reset the graphics API after the previous crash and selected OpenGL; Vulkan RT never initialized. The Vulkan POC command now rejects OpenGL with an explicit switch-API-and-restart message before changing mode or geometry admission. Both RT backends stop calling LevelRenderer.invalidateCompiledGeometry (which releases native terrain buffers); independent RT warmup admits already loaded sections without discarding raster geometry. The diagnostic view runs once after world rendering and before camera projection reset, independently of foundation material/shadow readiness. Host regression/build checks cover call placement and absence of terrain invalidation; RTX rendering remains unverified on this host.


## 0.39.0-alpha.2 — native-stack startup allocation fix

Alpha.1 crashed at extension enumeration on the Windows RTX 4060 driver: the driver-sized VkExtensionProperties array overflowed LWJGL MemoryStack during device creation. Alpha.2 uses explicitly freed native heap allocations for extension arrays and up to 512 TLAS instances, and resets temporary BLAS geometry stack allocation per section. A regression allocates both 512-entry arrays with only 1 KiB of thread stack left and verifies that neither consumes stack space. Windows startup/RT execution remain GPU acceptance items.

## 0.39.0-alpha.1 — Vulkan RT migration stage 1

Legacy OptiX remains production. An explicit Vulkan terrain normal POC borrows MC device/queue, enables RT features, builds section BLAS/world TLAS, consumes independent build-time Slang/SPIR-V stages, caches pipelines and records delayed GPU timings. Static section versions skip rebuild; viewport resize preserves pipeline/scene. Build/layout/stage/lifecycle contracts are host-verified; this host has no RTX GPU. Phase A GPU acceptance is pending; full PT, Material 3 parity, compaction/entity refit, RIS, reconstruction, DLSS RR, OMM/SER and Vulkan reference are not complete. Do not resume OptiX compiler tuning as the migration objective. [Authoritative migration ledger and checks](VULKAN-RT-MIGRATION.md).

## 0.38.0-alpha.8 — resize callback correction (GPU candidate)

Alpha.7 user correctly enabled reference, but 214×120 → 640×338 still destroyed the native context and recompiled. The common GameRenderer resize/reset hook bypassed alpha.7 viewport reuse. Alpha.8 splits resize from world/data/shutdown reset and adds a bytecode lifecycle regression test. Alpha.7 initialization took ~13 s; cache remained zero/missing. Visual correctness, reference convergence and warm cache remain unaccepted. [Evidence and checks](REFERENCE-0.38-ALPHA8.md).

## 0.38.0-alpha.7 — convergence / viewport follow-up (GPU candidate)

Alpha.6 user GPU cold startup completed in ~16 s, but resizing recompiled and identical keys missed a zero-sized disk cache. Alpha.7 retains compiled pipelines/world geometry on viewport resize, preserves reference accumulation when changing positive target SPP, explains reference versus realtime SPP in command feedback/status, exposes reset count and raises the adaptive reference ceiling to 8192 pixels. Native/Java host verification does not establish visual correctness. SDK disk-cache persistence, warm startup and the reported noisy/incorrect image remain GPU acceptance items. P0 stays open. [Changes and GPU checks](REFERENCE-0.38-ALPHA7.md).

## 0.38.0-alpha.6 — callable compiler boundaries (GPU acceptance candidate)

Alpha.5 failed: diffuse expanded to 62,994 driver instructions and canceled only after 267.3 s. Alpha.6 moves iterative transport and visibility into separate continuation callable modules and BSDF evaluation/sampling into direct callable programs, with explicit SBT and stack ownership. Strict PTX/default remains the baseline; full-reference transport stays separately compiled and lazy. Successful driver graph/cache-hit feedback is logged. Timeout now reports fallback and pending safe cleanup; cooperative driver cancellation is still not a proven hard deadline. Windows/Linux build and host tests pass; cold/warm startup, callable GPU correctness/performance and reference isolation still require the user GPU gate. P0 is open; P1–P7 remain deferred. [Evidence, architecture and acceptance](OPTIX-COMPILATION-0.38-ALPHA6.md).

## 0.38.0-alpha.5 — failed GPU compilation gate

**Alpha.5 also failed:** hit compiled in 1.143 s; diffuse task 3 stayed active beyond 240 s despite successful cancellation at 120 s. Cooperative cancellation does not enforce a hard deadline. Later modules/link/runtime were not reached. **Alpha.4 failed user GPU acceptance:** 591.74 realtime task 2 did not finish within 120 seconds; the process exited immediately after the cancellation request. Exact native fault remains unconfirmed. Alpha.5 splits realtime signal/probe raygens into separate constant-signal modules, adds non-inline glossy BSDF boundaries, retains pending compiler ownership across render resets, skips abandoned queued work, makes full reference session-only, bridges native diagnostics to latest.log plus a flushed dedicated file, and binds CUDA on the watchdog before targeting a published module. Windows/Linux and host regression verification do not establish crash recovery or startup targets. See [GPU evidence and follow-up](OPTIX-COMPILATION-0.38-ALPHA5.md). P0 remains open; P1–P7 remain deferred.

## 0.38.0-alpha.4 — OptiX Compilation Architecture (GPU acceptance candidate)

P0 compiler ownership split: hit/realtime/full-reference/caustic modules, independent caustic pipeline, non-tracing CUDA utilities, lazy isolated full-reference creation retaining realtime during compilation, task-level telemetry and per-module/group/link times, stable SDK disk cache with status readback, and cooperative OptiX 9.1 creation cancellation watchdog. Strict/fast PTX/IR artifacts support four-way A/B; strict PTX remains the baseline until numerical/visual acceptance. **Startup improvement, cache cold/warm behavior and cancellation are not yet validated on RTX 4060 Laptop.** The alpha.3 591.74 baseline remains 1185+ seconds at tasks=2/3. Synchronous link cancellation remains a stated limitation. P1–P7 are deferred. See [architecture and GPU acceptance](OPTIX-COMPILATION-0.38.md).

## 0.38.0-alpha.3 — OptiX startup compatibility

Alpha 2 failed OptiX-IR compilation on the user's NVIDIA 591.74 driver (error 7251), retaining raster fallback. Strict-math PTX is now the default; IR remains opt-in with a logged single PTX retry on compilation error 7251. Device errors do not retry. Native compiler callbacks retain errors/warnings, and compiler buffers grow from 8 KiB to heap-backed 1 MiB; module creation/program-group/link errors include diagnostics. Debug level is explicitly NONE for release modules. Default optimization remains optimized, not O0. GPU recovery is pending user verification.

## 0.38.0-alpha.2 — environment and asynchronous full reference

Shared GPU HDR sky/cloud environment and exact solid-angle importance CDF; OptiX primary-camera progressive reference; nonblocking reference completion/display ownership; stale-camera rejection; coordinate-aware ray origins and shading hemisphere checks; matched GGX reflection multiple-scattering sampling; water free-flight and full-reference medium events; build-time OptiX-IR/PTX and optimization A/B. See [0.38 ledger](RTX-QUALITY-0.38.md), [environment](RT-ENVIRONMENT.md), [reference](FULL-REFERENCE.md). This is still a checkpoint: adaptive full-resolution RTX rays, canonical calibration, RT texture mips/animation, primary diffuse ownership, hierarchical lights, layered reference, caustic clipmaps and signal-guide improvements remain unfinished. NVIDIA acceptance has not been run on this host.

## 0.38.0-alpha.1 — first integration increment

Primary opaque glossy NEE/MIS, RT coated-substrate semantics and UV tangent frames implemented. User confirmed 0.37.6 responsiveness; night iron/indirect quality remains unaccepted. The full 0.38 review is **not complete**. Implementation/acceptance ledger: [RTX Quality 0.38](RTX-QUALITY-0.38.md). No NVIDIA baseline available on this host.


Previous stable release: **0.37.6 — Reference progressive frame budget**. The 0.36 raster-primary/OptiX GAS/IAS/zero-copy/separate-AOV/world-cache architecture is retained; the user confirmed those paths run in game. This release changes material scattering and transport rather than rebuilding RTX architecture.

User measured 0.37.5 compilation/linking at ~134s, then reported unusable reference frame rate. 0.37.6 replaces full-image per-frame reference work with adaptive 8–1024-pixel windows (initially 64), complete-sweep spp accounting and persistent guide snapshots. Untouched surfaces retain raster. Normal RTX Quality scheduling is unchanged. See [reference frame scheduling](REFERENCE-0.37.6.md). NVIDIA FPS/convergence acceptance is pending.

0.37.4 still compiled for at least 3m51s in the supplied log. 0.37.5 uses task-based compilation on up to four workers, optimization level 0 and 15-second task/elapsed heartbeat logs. GPU execution may be slower; NVIDIA startup and runtime measurements are pending. See [compiler follow-up](REFERENCE-0.37.5.md).

User confirmed 0.37.3 stays responsive, but compilation took ~37m28s and the first output transfer failed with invalid mip. 0.37.4 corrects every RT buffer/texture copy and limits heavy CUDA inlining/compiler optimization. See [follow-up](REFERENCE-0.37.4.md). New packaged transport PTX is 1168476 bytes (previous 4,077,162); driver-time improvement remains unmeasured.

0.37.3 addresses the supplied initialization freeze boundary: native context/module/pipeline startup runs on a daemon worker while raster continues, with live phase/elapsed status and safe late-result disposal. See [startup diagnostics](REFERENCE-0.37.3.md). In-game NVIDIA validation is pending.

0.37.2 fixes rectangular depth-pyramid zero-height mip views, removes CUDA collection from GUI telemetry and tiles one-spp reference launches. The supplied crash log has no fatal tail, so the original termination cause remains unconfirmed. See [reference crash hardening](REFERENCE-0.37.2.md).

0.37.1 fixes per-frame reference resets, suppresses reference projection jitter/adaptive budget changes and freezes RT dynamic animation while converging. A searchable vanilla settings screen in Pause/Options and `/voxellight settings` shares validated command actions and atomically persists normal preferences. See [Settings](SETTINGS.md). In-game UI/reference acceptance remains pending.

Implemented: Material 3 class/LUT and 1,269 Vanilla block texture presets, LabPBR 1.3 channel priority, exact RGB conductor Fresnel, matched GGX VNDF/eval/PDF, coating/wetness and thin foliage, full textured secondary BSDFs, power-CDF emissive/environment/sun/held-light NEE with MIS, rough/thin dielectric identity-aware media, shared participating-water single scattering, refracted-sun photon caustic cache, signal-specific edge rejection and conservative raster fallback, 256-spp configurable reference accumulation, expanded debug and operation telemetry. See [Material 3](MATERIAL-3.md) and [RT light transport](RT-LIGHT-TRANSPORT.md).

**0.37 visual acceptance is pending.** This Linux host has no NVIDIA GPU/display. **246 tests pass**, including production CPU BSDF Monte Carlo/regressions and actual GLSL/SPIR-V/pipeline bindings; Windows/Linux native builds pass. These are verification; they do not establish edge quality, transport convergence or FPS. Remaining model/coverage approximations and all acceptance scenes are listed in the transport document. The earlier 0.36 user acceptance does not imply 0.37 acceptance.

Minecraft26.2 / Java25 / Fabric Loader0.19.5 / Fabric API0.160.0+26.2. Native Vulkan only, client only; effects off on a fresh installation, saved settings restored on world join. Build with `./gradlew build clientKit`; install the resulting mod from the kit and run `/voxellight mode foundation`. Detailed commands and checks: [INSTALL.md](INSTALL.md).

| Stage | State |
| --- | --- |
| B1 material diagnostics; B2 separated terrain HDR lighting | User confirmed corrected inputs and the foliage fix. |
| B3a block-entity shadows; B3b light-aware volume; B3c opaque model material lighting | User confirmed0.13/0.14/0.15; bounded supported streams, unsupported/blended/custom models remain native. |
| D1 directional visibility temporal history | Implemented0.16; user cannot distinguish the improvement, so no claim of full temporal acceptance. Ghosting/cascade/resize checks remain pending. |
| 0.16.1 stability follow-up | Retain surface until verified replacement; LIGHT-only history resets removed; per-frame CPU transform inverses. User confirmed the0.16.1 stability release. |
| D2 basic terrain AO | Implemented0.17: half-resolution horizon AO, spatial bilateral filter/upsample, ambient-only composition. User confirmed it works, with modest visual benefit; no AO history. |
| 0.18 lighting/color polish | Implemented: filmic/manual exposure, hemisphere sky palette, emissive terrain bloom, bounded material-distance blend. User confirmed working. |
| 0.19 local-light polish | Implemented: exact-ID resource-pack colors and one player held-emissive-block source within16 combined lights. User confirmed working. |
| 0.20 atmosphere foundation | Implemented analytic height/distance aerial perspective and directional glow; local supported receivers only; user confirmed working and prefers density0.002.0.20.1 adopts that default. |
| 0.21 Water Foundation | VisualComposite ownership split; native-stream HDR water with Fresnel/absorption/sky reflection/refraction. Screenshot showed blue water grid/dashes;0.21.1 fixes derivative evaluation order and UV rounding. User confirmed seam fix. |
| 0.22 native material coverage | Implemented native-compile inline attributes and a borrowed visible-terrain material pass. Default foundation/material diagnostics no longer use the local mesh store or 24–32-block fade. 0.22.0 user log exposed Fabric Indigo bypass of the vanilla output callback;0.22.1 captures pre-lighting Indigo quads and transfers metadata at actual buffer emission. User confirmed0.22.1 working. |
| 0.23 extended directional shadows | 128-block receiver default; unchanged2048 near map, coarser middle/far maps, borrowed compiled terrain outside the near bridge. No duplicate distant geometry or new image memory. User confirmed0.23 working; detailed offscreen/low-sun/performance checks remain pending. |
| 0.24 shadowed volumetric lighting | Quarter-resolution16-step current-frame sun/moon scattering through terrain/dynamic shadow maps, HDR extinction/composition and depth-guided upsampling. Preferred density0.001 retained; user confirmed0.24 working. Detailed visual/performance checks remain pending. |
| 0.25 phase1 — water reflections | Bounded HDR screen-space trace over existing pre-tone scene/depth; binary refinement and edge/distance/validity fade to sky. No new reflection image. Pending combined in-game test. |
| 0.25 phase2 — volumetric filtering | Two depth-aware quarter-resolution5-tap filter passes; no volume history or RGB ghosting. Pending combined in-game test. |
| 0.25 phase3 — animated water / quality controls | Three moving normal-wave harmonics, bounded phase and modulo64 continuity; waves/reflections/filter toggles and fixed fast/balanced/high sampling budgets. Pending combined in-game test. |
| 0.25.1 review follow-up | Direct36-byte writer; cached caster admission outside allocation lock; smaller filtered water ripples; optional delayed per-pass profiler. 178 tests passed; actual native shader compilation and direct vanilla/Indigo startup writer checks passed (graphics startup stops at missing DISPLAY); in-game motion and measured performance pending. |
| 0.26 celestial cache | Two fixed-angle terrain epochs with visibility interpolation,8-page future construction budget and current dynamic shadow reprojection. Static cutout meshes now cache; animated/unknown emitters remain conservative.189 tests pass; native shader contracts and actual startup classification/writer checks pass (graphics stops at missing DISPLAY). In-game quality/performance pending. |
| 0.26.1 epoch default | User reports higher FPS with epochs disabled. Epoch blending is now opt-in; static cutout caching remains enabled independently. No measured pass-level diagnosis yet. |
| 0.27 performance foundation | Three RGBA8 material targets + D32 (16 bytes/pixel). Explicit signed-normal and validity/native-fade encoding across terrain/entity capture, lighting, AO, bloom, temporal and diagnostics. Temporal shadow defaults off; opt-in history remains available.191 tests pass; GPU acceptance and timings pending. |
| 0.27 acceptance | User reports temporal off is15 FPS faster; keep it off. Packed-material precision/distant-fade checks are not separately confirmed. |
| 0.28 surface shadow sampling | Independent shadow_filter fast/balanced/high budgets4/16/36 taps per cascade; default balanced. Continuous texel-phase weighting, existing world bias, cascades and dynamic blocker union retained.194 tests pass; visual/FPS acceptance pending. |
| 0.29 HZB water tracing | Half-res R32 max-depth pyramid with conservative odd-tail reductions and hierarchical screen-space reflection traversal. Opt-in water_hzb,16 MiB cap, separate profiler stage;199 tests pass. GPU quality/performance acceptance pending. |
| 0.30 experimental hybrid PT | Runnable CUDA voxel diffuse secondary paths + real OptiX HDR denoiser, optional stationary-camera preview. Native Windows/Linux binaries bundled; 205 Java tests and native CPU traversal/sampling checks pass. GPU acceptance pending. [Scope/test instructions](PATH-TRACING.md). |
| 0.30.3 hybrid GI stability | Persistent per-pixel radiance/confidence/moments with reprojection/clamping before OptiX HDR; eight fresh samples per batch, view-depth validation, linear albedo, emissive ownership, proxy hysteresis and freeze/rejection/upload diagnostics. 209 Java tests and native CPU checks pass; GPU motion/ghosting acceptance pending. |
| 0.31 Material 2.0 | Packed ID/LUT, GGX direct/specular sky, static LabPBR normal/spec maps and rain wetness. 216 tests pass, including actual shader/binding compilation; GPU appearance/performance acceptance pending. [Contract](MATERIAL-2.md). |
| 0.31.0 acceptance | User confirmed Material 2.0 working; detailed map/wetness/FPS comparisons remain unmeasured. |
| 0.31.1 single-raster MRT | Native color/depth + four material outputs in one terrain submission; shared filtering and copied depth, comparison/fallback retained. 219 tests pass; GPU acceptance pending. [Contract](SINGLE-RASTER.md). |
| 0.31.1 acceptance | User confirmed single-raster native MRT working; measured FPS remains pending. |
| 0.35 raster review bundle | Sky/cloud/weather, cloud shadows; shared compact static motion/guides, optional opaque HDR TAA; temporal4/6/8-step volume; underwater/caustics/rain/foam; half-res solid PBR HZB SSR; optional measured adaptive world-region budget. 221 tests pass; joint GPU acceptance pending. [Full scope, budgets, checks and later RTX track](REVIEW-COMPLETION.md). |
| Next acceptance | Test the raster bundle in sky/weather, reflective materials and water; capture fixed-quality A/B world/pass timings. Later RT-core reflections/interop/full PT stay planned, not implemented. |

New 0.35 allocations and per-effect fallbacks are authoritative in [REVIEW-COMPLETION](REVIEW-COMPLETION.md); the historical atmosphere/volume description below is superseded there. Actual GPU timings remain unmeasured on this host.

Current budgets: material targets use20 bytes/pixel (1440p70.31 MiB,4K158.20 MiB), including the new packed PBR attachment. PBR atlases/LUT add at most33.25 MiB; HDR lighting remains8 bytes/pixel. Temporal shadows and celestial epochs default off. Optional dual-angle terrain shadows add at most48 MiBD32 (102 MiB total shadow textures); shared resolve transforms1344 bytes. Native material mode borrows visible native terrain geometry, adding8 bytes/vertex (BLOCK stride28→36) and, by default in0.31.1, one native color/material MRT raster with a private depth copy; `single_raster off` restores the extra material raster. No duplicate material mesh store. `/voxellight native_material off` selects the old125-section/16 MiB/one-build-per-frame local reference. Native compiler light updates still rebuild native section buffers. Light-aware scene cap384 loaded sections; independent near shadow terrain32 MiB; distant shadows borrow native allocations under a bounded128-block receiver +96-block light extrusion, with pending native compilation reported; local-light reference16 combined sources (one slot reserved while a held source exists). When explicitly enabled, D1 adds32 bytes/pixel with128 MiB cap (1440p112.5 MiB;4K falls back to current shadows).

AO uses two half-resolutionRGBA16F targets (32 MiB cap,1440p14.1 MiB,4K31.6 MiB), plus a16-byte neutral texture and32-byte settings. It is terrain-only and bounded by existing material coverage.

Polish adds two quarter-resolutionRGBA16F bloom targets (16MiB cap,1440p3.52MiB,4K7.91MiB). `look reference` restores0.17 lighting/output; polished is the foundation default. Native material mode disables the global24–32-block fade; local reference coverage blending fades24–32 blocks within the guaranteed material window; `coverage_blend off` restores the sharp window for comparison. See[polish contract](POLISH.md).

Atmosphere defaults to density0.001.0.25 uses one quarter-resolutionRGBA16F scattering/transmittance target and an optional equal-size spatial-filter scratch (8MiB combined cap;filtered1440p3.52MiB,4K7.91MiB), one8-byte neutral texel,32-byte controls and16-byte filter settings. 0.35 opaque medium uses4/6/8 jittered steps with quarter-res shared-guide history (four quarter targets including filter/history,32MiB cap); temporal off restores8/16/32 current-frame steps; ambient scattering retains receiver-skylight approximation. Water retains analytic atmosphere. 0.35 adds underwater direct scattering and shared volume history; non-overworld/reference look retain the previous bypass. See[volumetric scope](VOLUMETRIC.md).

Opt-in HZB adds a half-resR32 mip chain (16 MiB cap,1440p4.69 MiB,4K10.55 MiB) and grows water controls to80 bytes. Water adds oneRGBA16F HDR background + oneD32 immutable depth image (96MiB cap;1440p42.19MiB,4K94.92MiB), no duplicate water meshes or reflection image. Screen reflections reuse these two resources with16/24/32 trace steps. Supported Fast/Fancy water follows valid native HDR background coverage; Fabulous/underwater/uncaptured backgrounds stay native. See[water contract](WATER.md).

0.35 supplies opt-in opaque HDR TAA and static camera motion guides; no dynamic model velocity, complete transparent HDR rendering or full-scene transparent volumetrics, production GPU voxel DB or full-scene GI yet. 0.30 adds bounded experimental hybrid diffuse GI; it is not full primary-ray PT. Performance results are unmeasured. Continuous world-sun invalidation remains in the default path; experimental dual-angle epochs reduce redraws but regress FPS in the user scene and are opt-in; static cutouts cache while animated/unknown cutouts refresh. Duplicate shadow meshes, DDA cost, and history compression remain future work; do not increase local-light count or extend temporal scope before evidence warrants it.

Review decision: preserve the accepted material/lighting architecture; material refresh is confirmed and basic AO is confirmed operational with modest benefit. Lighting/color polish is confirmed working; local-light materials and held source are confirmed working; analytic atmosphere is confirmed working, with preferred density0.001 adopted as the0.23 default. Water, native material coverage and0.23 extended shadows are confirmed;0.24 is user-confirmed working;0.25 was reported working with modest visual benefit; detailed combined acceptance remains pending.0.26 epoch cache/static cutout changes require the new A/B checks. See[local-light scope](LOCAL-LIGHTS.md). See[AO contract](AO.md). See [0.16 review decision](REVIEW-0.16.0.md) and [release history](CHANGELOG.md). This environment has no graphics device/display; automated native shader checks do not replace in-game acceptance.

0.30.2: local material content replaces global/LIGHT task-version admission. Tiny camera noise is tolerated; celestial reseeds retain surface-valid images during warm-up. 205 tests pass. User GPU log confirms native work executes; corrected in-game accumulation/flicker acceptance pending.

0.30.2 removes stationary-only rendering and the sample-count intensity ramp. Valid surface lighting is camera-reprojected while motion batches refresh it; resets trace eight samples before denoising. 206 tests pass; in-game motion/flicker and worker cost pending.

0.30.3 supersedes the 0.30.1/0.30.2 accumulation policy above. The display receives a persistent worker-filtered GI field, not independent reset batches. Native history adds six float4 images (~21.1 MiB maximum); sun/weather bins and proxy-origin shifts no longer invalidate unchanged world surfaces. Actual 26.2 upload staging copies were verified. See [stability contract and diagnostics](PT-STABILITY.md).

0.30.4: user reports reduced but remaining flicker in 0.30.3. Validated bilinear native history, removal of grazing radial rejection, sparse compatible-surface composite fallback and observation-aware clamping address remaining discontinuities. Same resource/sample budgets; 209 Java tests and expanded native CPU regression checks pass. GPU verification pending.

0.30.5: user reports remaining flicker and freeze resembling raster-only. Display lifetime is separated from accumulation/material revision; freeze waits for one valid observation. Explicit world/resource resets prevent cross-world display reuse. A post-OptiX guide-validated EMA stabilizes the actual denoised field (+two low-res float4 images, ~7.0 MiB maximum). 210 Java tests pass; GPU acceptance pending.

0.30.6: freeze user-confirmed working, continued updates still flicker. Two separately surface-validated observations interpolate in the Vulkan composite over 150 ms, replacing abrupt batch swaps. Freeze holds/resume continues the blend. +three low-res float4 images (10.55 MiB maximum), no new full-res target/rays. Admission waits for transition completion. 212 Java tests pass; GPU stability/performance pending.

0.30.7: user confirms 0.30.6 no flicker on Vulkan, but new-view buildup remains. Remove new-only zero-to-full fade; bootstrap missing-history surfaces with 32 samples, keep established surfaces at eight; overlap next-job compute with display transition while deferring slot replacement until safe. No new images. 212 Java tests and native bootstrap regressions pass; GPU latency/quality acceptance pending.

0.31.0: prioritize Material 2.0 per the latest review. Keep current diffuse GI behavior; the user still sees new-view refinement after 0.30.7. Implement static-map LabPBR/PBR/wetness with bounded lookup storage; future environment/temporal/RTX tracks remain planned. See [material contract](MATERIAL-2.md).

Alpha.13 validation: build/clientKit passed, 241 tests with zero failures; actual accumulation GLSL links against Minecraft bindings, packaged-artifact regression rejects legacy tracer/native compiler payloads. Material/terrain/environment Slang-native parity remains unchanged (57,600 cases / 32 cases / 300,000 samples). Independent temporal AOV header syntax-check passed against the installed OptiX/CUDA SDK. RTX visual acceptance remains pending.
