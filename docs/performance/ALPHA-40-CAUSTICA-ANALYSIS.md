# alpha.40：clean FULL 实测与 Caustica 差距审计

数据：用户上传 `logs/rt-suite-1791379714294.zip`，schema 11、alpha.40、RTX 4060 Laptop；8 段完成且各段 workload 校验通过。Caustica 源码审计固定上游 `330acd2d743bb6b2e27b53a4adb4a3c852b9e141`；没有用户实际 Caustica jar 版本或它的逐 pass 数据，不能把上游当前实现当作用户版本的完整配置证明。

用户补充：同窗口分辨率，本项目二十多 FPS、Caustica 170–180 FPS，Caustica DLSS 性能档、1 spp、4 反弹、无帧生成，画面明显更清晰。本项目的具体即时状态与 benchmark 不完全相同：suite 开始前是 CACHE_SPARSE、AUTO、QUERY、OMM/SER on；shader 对照临时使用 FULL、FIXED、QUERY、OMM/SER off，结束会恢复前者。

## 本次实验结论

| 段 | Shader | transport GPU median ms | primary | B1 | B2 | B3–B5 合计 |
|---|---|---:|---:|---:|---:|---:|
| 1 | Runtime | 6.322 | 1.627 | 1.879 | 2.110 | 0.623 |
| 2 | Clean | 6.266 | 1.672 | 1.841 | 2.015 | 0.612 |
| 3 | Clean | 6.333 | 1.692 | 1.858 | 2.042 | 0.611 |
| 4 | Runtime | 6.446 | 1.651 | 1.871 | 2.153 | 0.638 |
| 5 | Runtime | 6.481 | 1.662 | 1.905 | 2.155 | 0.634 |
| 6 | Clean | 6.304 | 1.661 | 1.865 | 2.024 | 0.608 |
| 7 | Clean | 3.576 | 0.854 | 1.105 | 1.185 | 0.342 |
| 8 | Runtime | 3.731 | 0.859 | 1.148 | 1.278 | 0.352 |

第一轮 ABBA 描述性改善约 1.326%，不足以成为稳定赢家。第二轮计算值 3.261%，但该轮 A/B 重复段波动达 55.216%，整个 suite 正确报告 `not_comparable`，不能汇总为 clean 的提速。

第 7–8 段发生广泛 GPU 时间变化：scene commit 从约 0.63 降至 0.27–0.28 ms、environment map 从约 0.26 降至 0.126 ms、OptiX exchange 从约 2.7 降至 1.59 ms，CPU scene commit 仍约 3.7 ms。terrain signature、尺寸、spp、冻结 lighting 与 alive 曲线一致。这支持运行环境/设备执行状态改变的推断，不能仅凭时间戳确认 GPU 时钟、功率或后台程序是哪一项。不存在实际时钟/功率记录。

SPIR-V 体积减少已经确认，但没有可用 register/spill 证据：pipeline statistics 启用后驱动仍返回 0 executables / 0 statistics。不得将缺失值视为零，也不应据此解释十倍 FPS。

## 帧时间不是 transport 时间

二十多 FPS 大约意味着每帧 33–50 ms；170–180 FPS 约 5.6–5.9 ms。当前导出覆盖本项目 RT、重建及少量原生 pass，不是完整 CPU frame / vanilla world / queue wait / present 时间线。

早期段 transport 约 6.3 ms、OptiX exchange 约 2.7 ms、material assets 约 1.0 ms、scene commit GPU 约 0.63 ms；后期段相应约 3.6、1.6、0.22、0.28 ms。scene commit CPU 约 3.7 ms。嵌套 scopes 不得重复相加，CPU 与 GPU 时间也不能直接相加当作 frame time。这些记录还不足以定位本项目 33–50 ms 的全部成本。

例如第 1 段 passes.csv 包含约 300 个 batch / 10 秒，第 7 段约 757 个 / 10 秒；同一 suite 帧率也发生明显变化。用户平时二十多 FPS 不能直接用测试尾段的 GPU 成本解释。

## 源码已经确认的差距

### 1. 本项目保留原版世界绘制，Caustica 接管世界输出

本项目 `GameRendererMixin` 在 `LevelRenderer.render()` AFTER 才调用 PT composite；`RenderProbe.renderNativeOpaque()` 在 FOUNDATION + RT active 时直接返回 false，原生 `renderGroup` 因而继续 `original.call`。这意味着“材质 capture bypassed”和“raster shadow passes bypassed”不等于取消了原版 terrain/entity/sky/world graph。动态模型捕获仍依赖原生 feature 提交流程。

Caustica 的 [LevelRendererMixin](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/mixin/LevelRendererMixin.java) 在 HEAD 有条件取消整个 render；[VanillaRenderController](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/client/VanillaRenderController.java) 处理 RT 就绪、projection、player-section callback、失败恢复与 before-hand seam。

这是确切的架构差异；没有 vanilla 全帧计时，尚不能声称取消这条链就能达到 180 FPS。也不能直接 ci.cancel 后交付：必须先迁出实体/方块实体/粒子捕获、保存 loading callback、保障首次加载、资源重载和 fallback，否则会失去动态场景与启动覆盖。

### 2. 降噪与超分辨率不是同一套能力

本次本项目实际输入为 **214×120、25,680 pixels、1 spp、scale 0**。scale 0 至少 4×线性缩放，约为输出像素数的 1/16；不能把窗口分辨率当作 path-tracing 分辨率，也不能仅凭“双方 1 spp”判断射线数相同。用户实际 Caustica internal size 未导出。

本项目：低分辨率 Vulkan radiance → OptiX temporal beauty denoise → 自研 3×3 几何权重 + 历史 clamp 的输出分辨率重建。native bridge 当前实际只调用一层 beauty，尽管生产 transport 有 diffuse/reflection/refraction AOV。

Caustica：[RtDlssRr](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java) 使用 noisy HDR、depth、normal/roughness、diffuse/specular albedo、motion、specular motion、jitter 和变换矩阵，在 Vulkan command 中联合降噪与放大；[RtComposite](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java) 通过 NGX optimal-settings 查询实际 tracing 尺寸。

因此 Caustica 更清晰有具体结构依据，不能以减少本项目输入像素作为画质达标。我们的 primary **已有随机 subpixel jitter**，不是“完全没有 jitter”；问题应检查低分辨率 guide、history confidence、denoiser 输入与反射运动，而不能误诊成遗漏 jitter。

OptiX exchange 的 1.6–2.8 ms 包含降噪、GPU依赖和传输等，不能全部称为互操作税；native invoke 使用异步 semaphore，并没有每帧 `cuStreamSynchronize`。不能凭跨 API 就断言存在每帧 CPU 同步。

### 3. 透明 visibility 的 estimator 和每个连接成本不同

本项目 `visibility()` 先走 opaque query；场景存在 non-opaque 时继续最多 **24** 个有序界面，反复 TraceRay、完整 terrainSurface/material decode、介质/距离衰减和 Fresnel。场景本次约有 89,042 transmission triangles；诊断帧约 105,000–106,000 shadow traces / 25,680 primary paths。此计数不是“每个 surface 五条 NEE”，它包括逐界面等内部追踪。

Caustica 的 [trace visibility](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/trace.slang) 使用一次 first-hit/skip-closest-hit traversal；[shadow any-hit](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/any_hit.rahit.slang) 对 cutout 测 alpha，对玻璃/水累积近似透射、IgnoreHit，opaque 阻断。这不等价于本项目的有序多界面 shadow estimator，不能把其优化直接当作不改变算法的替换。

适合 Realtime 的便宜透射阴影与 Reference/Quality 精确策略需要正式分开，并做同场景 transmission-heavy A/B。当前不能给出该项收益百分比。

### 4. 间接 continuation 调度与状态格式不同

本项目 primary 后每个 bounce 单独 dispatch、barrier、load/store PathHot/AOV；Caustica [indirect](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/indirect.rgen.slang) 在一次 indirect raygen 内运行剩余 bounce loop，[segment](https://github.com/ComfyFluffy/Caustica/blob/330acd2d743bb6b2e27b53a4adb4a3c852b9e141/shaders/pipelines/world/segment.slang) 的 packed continuation 为 48 B。本项目 PathHot 64 B，medium cold allocation 288 B，AOV 48 B；普通空气路径已经按 mediumCount 跳过 cold media 读写，不能把 288 B 分配容量误当作所有 bounce 的实际流量。

Caustica closest-hit 使用硬件纹理采样与 ray-cone LOD；本项目 terrain atlas/palette 为 ByteAddressBuffer word lookup。布局、采样成本和状态驻留均需实测，不能仅以 packed record 大小推导速度。

### 5. 反弹数不是主要解释

本次本项目 B3–B5 合计早期约 0.61–0.64 ms、后期约 0.34–0.35 ms。主要成本在 primary/B1/B2。就算删除后三段也不足以解释 7–9 倍帧率；“4 bounce”与“6 vertices”还需统一定义。没有理由先继续调深层 RR 来追十倍 FPS。

## 调整优先级

1. 记录完整 CPU frame、原版 world GPU、RT、动态 capture/scene commit、reconstruction、wait/present；补 actual output/internal sizes 和设备运行状态。保留 transport 对照，但不得将其改善等同 FPS。
2. RT world takeover：将动态捕获与原版绘制解耦，RT ready 时跳过原版 world graph，保留 loading callbacks、UI/hand、失败恢复和资源生命周期。
3. 联合重建与可信 guides：优先对齐 DLSS RR/正确 specular motion 和输入契约，实测可接受分辨率；NRD 可以作为另一条降噪路线，但自身不能等同 DLSS 超分辨率。
4. Realtime 便宜透明阴影 + 紧凑 continuation / iterative-indirect A/B；Reference 保留精确策略，量化速度与视觉代价。
5. 再进入 Primary split/B0 cache，先保住清晰度与全帧预算，避免“低分辨率仍低帧率”时继续依靠 cache 阈值掩盖基础链路成本。

本轮为日志与源码审计，不修改渲染算法或发布新 jar。没有完整客户端 frame trace，也没有 Caustica pass profile，故不宣称已量化全部 FPS 差距或承诺单项十倍收益。
