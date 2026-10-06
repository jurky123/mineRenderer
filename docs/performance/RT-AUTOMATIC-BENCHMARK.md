# 自动 Vulkan PT 执行层 A/B 测试（alpha.33）

进入已正常运行的 Vulkan PT 世界，等区块加载稳定，站定并关闭菜单，执行：

```text
/voxellight rt_benchmark start
```

不需要逐项手动切换。默认每段采样 6 秒，至少预热 4 秒，随后留 2 秒接收延迟 GPU 数据。根据硬件支持运行 24–72 段，通常约 2–12 分钟，另加驱动管线初始化耗时；SER 首次创建在用户实测中约 52 秒，期间渲染线程可能暂停。也可 `start 15` 延长每段采样（允许 4–30 秒）。`/voxellight rt_benchmark` 等同默认 start。

```text
/voxellight rt_benchmark status
/voxellight rt_benchmark stop
```

测试期间保持镜头、分辨率、spp 和渲染模式不变；不要走动、打开菜单、切出窗口或修改设置。必须关闭显式 accumulation freeze。命令不自动启用 PT，也不改变世界、相机、spp、缩放和重建设置。

## 自动执行的对照

每组独立改变一个执行选项，两轮 A→B→B→A：

| 组 | A | B |
|---|---|---|
| visibility | legacy visibility | opaque TraceRay fast path |
| ray_query | opaque TraceRay | opaque Vulkan Ray Query |
| query_vs_legacy | legacy visibility | opaque Vulkan Ray Query |
| queue | fixed continuation | compact continuation |
| hybrid_queue | fixed continuation | 按 bounce 选择的 hybrid continuation |
| omm | OMM off | 保守 triangle OMM on |
| ser | SER off | SER on |

其余控制统一为 TraceRay / fixed / OMM off / SER off。未启用的设备能力、无可用静态 opacity coverage 会跳过相关组；没有把 fallback 当作 B 成功执行。每段记录实际执行状态，不只记录命令请求。

启动时从当前 GPU 驻留地形按相机距离选择完整 section，最多 60 MiB，留下 4 MiB 动态模型空间；保存其精确版本和原始几何（额外 CPU 内存最多约 60 MiB），整轮各段从同一快照初始化；不再用实时 miss 换页扩充地形，也不允许动态模型扩容挤掉固定地形。动态模型、粒子、材质动画和光照仍正常更新；快照不追随世界地形编辑，因此请勿在测试中修改世界。测试结束恢复正常实时地形更新。快照取不到精确版本会明确拒绝启动，避免静默混用其他版本。

配置切换先进入 INITIALIZING，旧执行 revision 的 context 不视为当前配置可用。实际管线、分辨率与 opaque 场景就绪，且静态 section 数量、版本签名与预期快照完全一致后才进入 WARMUP，至少运行 4 秒且地形工作集至少 1 秒稳定；最近 30 个无计数器 GPU batch 样本的前后两半中位数差距须不超过较小值的 10%，GPU 耗时 30 秒内无法稳定就中断并说明原因；初始化等待不计入这两个时钟。未就绪等待上限为 180 秒，地形持续变化的预热上限为就绪后 30 秒。同步驱动管线创建期间渲染线程暂停，诊断和停止命令要等调用返回才能执行，180 秒不是可中断驱动调用的硬期限。记录 GPU scope 的提交帧范围，仅收集属于该范围的延迟时间戳和 ray counters；预热、前一段及排空阶段的新帧不会混入当前段。alpha.31 仅每第八个 transport 帧启用 ray counters 和 visibility replay；GPU 时间戳仍每帧采集。汇总耗时排除启用 counters 的帧，避免每射线全局原子操作干扰 A/B 中位数和 p95。原始 CSV 保留全部帧，每段 `.counter_frames.json` 标明被排除的提交帧。诊断帧仍可能影响温度/缓存，因此并非零诊断干扰；alpha.30 的时间不能直接当同条件基线。

结束、停止、切换世界、资源重载、相机移动或窗口失焦会恢复测试前 visibility/queue/OMM/SER 和 profiling 设置。临时控制不写入用户偏好。中断仍导出已有完整段和当前已采样的部分段；部分段标为无效。

## 结果

自动导出到游戏目录：

```text
benchmark-results/voxellight/rt-suite-<时间戳>.zip
```

同名目录保留原始文件。把 ZIP 发回来即可。无需另外执行 Python 或手工收集 stats。

- `warmup.json`：每个可执行渲染帧按秒记录各段请求配置、实际状态、readiness、就绪后预热秒数、稳定等待时间及完整 renderer 状态。同步管线创建期间没有渲染帧，记录会有时间空档。超时分别注明初始化未就绪或就绪后地形持续变化。
- `summary.txt`：每组 verdict、transport batch 时间改善百分比、重复段波动。
- `summary.json`：设备、版本、跳过原因、原控制、实际工作集、两轮改善及有效性检查。
- 每段 `.passes.csv`：GPU scope 原始样本；包括 batch、primary、各 continuation、visibility replay。
- 每段 `.diagnostic_timings.json`：启用计数器的帧单独汇总，包括 opaque-only visibility replay 时间；不混入主性能判定。
- 每段 `.rays.csv`：真实 alive、any-hit、opaque visibility 和 replay mismatch 计数。
- 每段 `.json`：各 scope 中位数/p95、alive 比例、any-hit/primary、查询丢失数量。
- 每段 `.txt`：开始/结束完整渲染状态。
- `pipelines.csv`：驱动可提供的编译器内部统计；缺失字段保持缺失。
- `pipelines-status.json`：按 stage 明确列出捕获是否启用、驱动返回的 executable/statistics 数量和缺失原因；空 CSV 不表示 spill 为零。

正改善百分比表示 B 的 GPU batch 更快。两轮都超过 `max(3%, 重复 A/B 段最大相对波动)` 才标 `candidate_faster`；同样判定退步为 `candidate_slower`。处在此范围内为 `within_variation`，方向或幅度不一致为 `inconsistent`。这是描述性 ABBA 筛选，不是统计置信区间，也不代表更小收益不存在。

少于 30 个有效 batch GPU 样本、GPU 时间戳覆盖不足 80%、分辨率/spp/模式/静态地形版本变化、实际控制不匹配、缺少 alive counters 或 bounce alive 比例漂移超过 5 个百分点，标为 `not_comparable`。重复 A/B 段波动超过 10% 也标 `not_comparable`，保留每轮原始改善，避免把明显温度/频率漂移当作普通小收益。两轮不完整标 `incomplete`。opaque-only TraceRay/Query replay mismatch 标 `correctness_failed`，Query 组缺少 replay 同样不能通过比较。

活跃实体、粒子、世界时间和光照仍可能变化；alive 曲线与静态地形校验不能完全证明 estimator 工作相同。推荐选择稳定的代表性场景，重复运行多个场景。本命令比较固定地形快照条件下当前版本的执行控制，不会自动得到 alpha.26→alpha.28 的版本收益，也不能验证画面等价、实际 register spill、L1/L2 traffic；这些仍需要截图对照和独立 GPU profiler。当前构建机器没有 NVIDIA GPU，本地验证不替代 RTX 实测。

## alpha.31 执行路径变化

生产 TraceRay 不再先查 opaque-only TLAS 再查完整世界；先对完整 world 做一次 scalar solid-shadow first-hit 遍历：opaque range 无 any-hit，cutout range 使用轻量 alpha any-hit，transmission range 忽略命中。存在玻璃/水时保留原 closest-hit 多界面衰减，火把 source-shell 仍走原处理。Query 路径保持 opaque-only inline 查询。replay 仍对同一份 opaque-only TLAS 比较 TraceRay/Query，不能视为对新生产 solid-shadow 的完整画面验证。CPU Slang fixture 验证原 visibility 数值等价，RTX 成本待实测。

Compact 队列从每存活路径 Add(count)+Max(width) 改为单次 Add(width)；width 同时是 raygen 输入长度和 indirect dispatch 宽度。空队列 width=0，无虚假占位 invocation。两个 ping-pong bank、多 spp 路径索引及终止规则保持一致，CPU fixture 替代原子预约原语验证索引/容量边界，未验证 GPU 并发与性能。相关 Vulkan ABI 见 [VkTraceRaysIndirectCommandKHR](https://docs.vulkan.org/refpages/latest/refpages/source/VkTraceRaysIndirectCommandKHR.html)。

## alpha.32 补齐项

`rt_queue hybrid` 是新候选，AUTO 的原有整体 batch 标定保持不变。HYBRID 根据每八帧读回的真实 alive 曲线，仅在对应 bounce 至少有 2048 条路径且占 primary 的 5%–70% 时采用 compact；其他 bounce 使用 fixed dispatch。未观察到曲线或尺寸/spp 改变时回到全 fixed。生产者只 append 下一个需要 compact 的 bounce，消费者按同一个 mask 读取 ping-pong queue；stats 导出 `compactBounceMask`。此阈值针对已上传曲线提出，是否更快由新增 `hybrid_queue` ABBA 判定，未默认启用。

OMM 的 atlas 更新按区域失效：受影响静态 alpha 转为 unknown，其他区域继续保守分类；动画区域已经 unknown 的写入不会复制整个 opacity 网格。opacity epoch 改变时重新分类驻留 CUTOUT，只用所保留的原始 section 重建索引实际变化的 OMM BLAS，避免复用过期的 opaque/transparent 索引。stats 同时记录 `knownOpacityTexels` 和 opaque/transparent/unknown 三类 OMM 三角形数量；静态已知 texel 为零时明确视为 coverage 不可用。当前仍是保守 triangle special-index OMM，混合 alpha 仍走精确 any-hit；高细分 micromap 烘焙尚未实现。

本机没有 NVIDIA GPU。GPU warmup 与重复段漂移拒绝用于提高测试可信度，不能隔离世界光照/动态变化，也不能替代画面、寄存器 spill、L1/L2 流量和 GPU 并发验收。执行层能否冻结仍需新版 RTX 结果。

## alpha.33 动画 atlas 合同

26.2 原版 atlas 动画通过 `uploadAnimationFrames` 的 render pass 绘制动画 sprite。此精确调用作用域只更新预分类 unknown 的动画区域，保留其他静态覆盖；其他 render target 写入继续保守失效，并使用 attachment view 的实际 mip。纹理版本仍递增，Vulkan albedo 更新保持正常。测试包含实际原版方法的 bytecode 合同和异常恢复；第三方直接底层写入仍不在此合同内，兼容场景保持 OMM off。

alpha.32 实测的各段工作集一致，但 OMM coverage 全零、SER 大幅漂移、编译统计驱动零 executable，不能据此冻结；详细记录见 [当前阶段](../CURRENT.md)。

alpha.34 新增 `/voxellight rt_benchmark direct [seconds]`：仅测 direct lighting，8 段、默认 10 秒；固定 Query/Fixed/OMM off/SER off，仅比较 legacy/RIS。summary schema 6 和每段 `.direct_lighting.json` 提供实际 Direct mode 与 per-bounce 连接计数。完整 start 追加 direct lighting pair。见 [第二轮设计](RT-DIRECT-LIGHTING-ROUND-2.md)。

alpha.35：`rt_benchmark blas [seconds]` 只比较 scene-update legacy/optimized，8 段，默认 10 秒。summary schema 7 中 blas 比较目标为 scene commit，transport 保持 RIS；完整 start 追加 blas pair，最多 72 段。[BLAS 验收](RT-BLAS-OPTIMIZATION.md)。
