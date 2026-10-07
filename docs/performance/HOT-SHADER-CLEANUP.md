# alpha.40：Hot Shader Cleanup 与同场景验收

本轮只做第一步：验证第三轮 policy 和直接光 A/B 代码是否增加了 hot shader 成本。保持原 estimator、材质、MIS、六个路径顶点和重建后端。不实施 Primary Visibility/Shading Split、B0 cache、Cache 2.0 或 NRD。

## 编译期执行变体

默认使用 CLEAN shader。构建生成独立 FULL / Realtime、RIS / Legacy、Trace / Query、SER off / on 组合，所有 SPIR-V 预编译和校验，运行时只选择 pipeline，不编译 PT shader。

- `material_primary_full[...].spv`、`material_indirect_full[...].spv`、`material_resolve_full.spv` 使用 FULL 的恒定 bypass hooks，不 include `realtime_policy.slang`，不包含 history、cache、训练实现。
- `material_*_realtime[...].spv` 包含原 cache/sparse 策略。本轮仍保留 Realtime 内部运行时策略选择；这不是新的缓存算法。
- RIS/Legacy 用编译期常量，Slang `-O2` 删除另一套分支。默认 RIS，Legacy 独立二进制仅供对照/兼容回退。
- 原 `material_primary[...].spv` / `material_indirect[...].spv` / `material_resolve.spv` 保留为 RUNTIME 组：原动态 direct/policy 分支，仅由 footprint benchmark 临时选择。
- Query/SER 特性矩阵保留；本轮不把未测赢家设为默认，不改变 OMM、visibility estimator。
- Reference 总是选择 FULL；切换实际 render mode、shader family 或 direct/policy 选项会重建 context。stats 报告实际 stage 名称。

共 58 个 stage（原 24 + 32 个 primary/indirect 组合 + 两个 resolve）。PathHot 64 B、medium 288 B、AOV 48 B 和第三轮内存布局保持原状。二进制体积减少不等于寄存器、spill 或 GPU 时间下降。

## 生产 HYBRID 与诊断解耦

完整 any-hit/direct/visibility/realtime 计数仅在 profile 开启的抽样帧执行。profile off 的 HYBRID 校准使用独立 alive-only flag，仅对每个活跃路径顶点执行 alive 原子计数，不开启全套诊断或 replay。每八帧尝试采样，取得八条有效曲线后冻结生产调度；分辨率/spp 或 context 改变后重新校准。异步回读失败/未完成不会被算作有效观测。

profile on 仍允许诊断和学习；灯光 proposal feedback 的原 32 帧节奏、page feedback 回读仍保留，不宣称完全没有原子计数或固定开销。生产校准帧不被性能汇总偷偷排除；完整诊断/proposal feedback 帧仍按既有方式标记。

## 最先执行：clean FULL 对照

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_spp 1
/voxellight rt_benchmark shader
```

两轮 ABBA，8 段，每段默认 10 秒采样、至少 4 秒预热、2 秒排空；约 2–3 分钟，另加驱动 pipeline 创建耗时。A 为 RUNTIME FULL + RIS，B 为 CLEAN FULL + RIS；二者使用相同能力门控 visibility、FIXED queue、OMM off、SER off、OPTIMIZED scene update。可用 `shader 15` 延长采样。

首要结果 `shader_clean_full`：batch GPU median、P95、primary/B1/B2/resolve、两轮改善与重复波动。只有两轮均超过 `max(3%, repeat variation)` 才是 `candidate_faster`；没有直接把二进制大小当作收益。alive 曲线需匹配，图像正确性另看实际场景/Reference。

## 完整调度与算法对照

```text
/voxellight rt_benchmark hot
```

支持间接 tracing 时总共 72 段：先 8 段 shader 对照，再为 FULL、SPARSE、CACHE_SPARSE 各测 FIXED→COMPACT 和 FIXED→HYBRID（各 8 段，共 48），最后 16 段 FULL→SPARSE / FULL→CACHE_SPARSE。约 19 分钟，另加初始化。队列 winner 必须是 `candidate_faster`；无稳定赢家保留 FIXED。若 COMPACT/HYBRID 都稳定胜出，选择相对 FIXED 改善较大的那个。这是被测候选中的保守选择，不是全局最优证明，不持久化覆盖玩家设置。

最后算法对照为每种算法使用各自测得的 queue，summary 导出 `selectedQueues`。队列对照仍要求 alive 曲线匹配；算法对照允许预期路径数量减少。间接 tracing 不支持时跳过队列扫描，运行 24 段并使用 FIXED。

也可单独 `rt_benchmark queues`（48 段）。原 `rt_benchmark realtime`（24 段）使用启动时的 queue；AUTO 采用 FIXED，**不再强制 COMPACT**。要公平选择每种算法的队列，请使用 `hot`。

## 整轮稳定输入与结果导出

所有自动 A/B suite 在启动后的首次使用捕获 renderer 输入，整轮和 context 重建期间固定：sun/moon 方向与强度、天空 palette 和环境设置、雨/云、手持灯位置/强度、介质、波浪/水面/风时钟。不会发送服务端 `/time`、`/weather`，不会更改服务器世界。Reference 累积规则和正常游戏照明不变。

地形继续使用同一精确驻留 section 快照。动态实体、粒子和材质动画仍实时更新，不能视为绝对静态场景。选择实体少的场景，保持窗口前台、镜头/尺寸/spp 不变，停止或结束后立即释放快照并恢复原执行控制/profile。

导出位置仍是 `benchmark-results/voxellight/rt-suite-<时间戳>.zip`。schema 11 新增 `frozenRendererInputs`、`selectedQueues` 和 Config/State 的 `shader` 字段；各段 txt 记录实际 stage；保留原始 passes/rays、完整诊断帧与 pipeline compiler statistics。`status` 看进度，`stop` 恢复设置并导出已有段。

## 判断下一步

- CLEAN FULL 明显胜出：确认 mixed shader footprint 有实测成本，再实施 policy/integrator 分离。
- 差距在波动内：确认单纯拆分不能兑现大幅目标，进入 Primary split + B0 cache，而非继续调 footprint。
- CLEAN FULL 回退或不稳定：先检查实际 stage、配置/输入、alive 曲线和驱动统计，不把“编译期”自动视为更快。

OptiX exchange 保持原后端，包含降噪等工作，不能把整段耗时都归为互操作税。NRD/DLSS RR 和外部 L1/L2、运行时 register spill 采样不属于本轮；CPU 数值回归及 SPIR-V 校验不能替代 RTX 实测。

## 本地验证

`./gradlew build clientKit` 通过：342 项 Java 测试零失败，58 个 SPIR-V stage 校验、96 B camera / 64 B PathHot / 288 B media / 48 B AOV ABI 和既有 Minecraft GLSL 链接测试通过。新增四组实际 Slang CPU target 的 compile-time direct/policy 选择与 FULL bypass 检查；clean RIS 再执行生产统一直接光的每 case 200,000 次能量/PDF 数值回归。没有 NVIDIA GPU 实测结果。

当前编译器输出（Trace、SER off）primary RUNTIME 821,448 B / clean FULL 420,592 B，indirect 852,420 B / 439,424 B，resolve 21,180 B / 4,188 B；这是文件体积，不是 register pressure 或性能指标。
