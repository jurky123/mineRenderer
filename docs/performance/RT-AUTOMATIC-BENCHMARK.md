# 自动 Vulkan PT 执行层 A/B 测试（alpha.30）

进入已正常运行的 Vulkan PT 世界，等区块加载稳定，站定并关闭菜单，执行：

```text
/voxellight rt_benchmark start
```

不需要逐项手动切换。默认每段采样 6 秒，至少预热 4 秒，随后留 2 秒接收延迟 GPU 数据。根据硬件支持运行 8–48 段，通常约 2–10 分钟，另加驱动管线初始化耗时；SER 首次创建在用户实测中约 52 秒，期间渲染线程可能暂停。也可 `start 15` 延长每段采样（允许 4–30 秒）。`/voxellight rt_benchmark` 等同默认 start。

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
| omm | OMM off | 保守 triangle OMM on |
| ser | SER off | SER on |

其余控制统一为 TraceRay / fixed / OMM off / SER off。未启用的设备能力、无可用静态 opacity coverage 会跳过相关组；没有把 fallback 当作 B 成功执行。每段记录实际执行状态，不只记录命令请求。

启动时保存当前 GPU 驻留地形的精确版本和原始几何（额外 CPU 内存最多约 64 MiB），整轮各段从同一快照初始化；不再用实时 miss 换页扩充地形，也不允许动态模型扩容挤掉固定地形。动态模型、粒子、材质动画和光照仍正常更新；快照不追随世界地形编辑，因此请勿在测试中修改世界。测试结束恢复正常实时地形更新。快照取不到精确版本会明确拒绝启动，避免静默混用其他版本。

配置切换先进入 INITIALIZING，旧执行 revision 的 context 不视为当前配置可用。实际管线、分辨率与 opaque 场景就绪后才进入 WARMUP，至少运行 4 秒且地形工作集至少 1 秒稳定；初始化等待不计入这两个时钟。未就绪等待上限为 180 秒，地形持续变化的预热上限为就绪后 30 秒。同步驱动管线创建期间渲染线程暂停，诊断和停止命令要等调用返回才能执行，180 秒不是可中断驱动调用的硬期限。记录 GPU scope 的提交帧范围，仅收集属于该范围的延迟时间戳和 ray counters；预热、前一段及排空阶段的新帧不会混入当前段。默认诊断的 visibility replay 位于 transport batch 之外，仍有额外 GPU 负载，因此这是受诊断条件下的执行层比较。

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
- 每段 `.rays.csv`：真实 alive、any-hit、opaque visibility 和 replay mismatch 计数。
- 每段 `.json`：各 scope 中位数/p95、alive 比例、any-hit/primary、查询丢失数量。
- 每段 `.txt`：开始/结束完整渲染状态。
- `pipelines.csv`：驱动可提供的编译器内部统计；缺失字段保持缺失。

正改善百分比表示 B 的 GPU batch 更快。两轮都超过 `max(3%, 重复 A/B 段最大相对波动)` 才标 `candidate_faster`；同样判定退步为 `candidate_slower`。处在此范围内为 `within_variation`，方向或幅度不一致为 `inconsistent`。这是描述性 ABBA 筛选，不是统计置信区间，也不代表更小收益不存在。

少于 30 个有效 batch GPU 样本、GPU 时间戳覆盖不足 80%、分辨率/spp/模式/静态地形版本变化、实际控制不匹配、缺少 alive counters 或 bounce alive 比例漂移超过 5 个百分点，标为 `not_comparable`。两轮不完整标 `incomplete`。TraceRay/Query replay mismatch 标 `correctness_failed`，Query 组缺少 replay 同样不能通过比较。

活跃实体、粒子、世界时间和光照仍可能变化；alive 曲线与静态地形校验不能完全证明 estimator 工作相同。推荐选择稳定的代表性场景，重复运行多个场景。本命令比较固定地形快照条件下当前版本的执行控制，不会自动得到 alpha.26→alpha.28 的版本收益，也不能验证画面等价、实际 register spill、L1/L2 traffic；这些仍需要截图对照和独立 GPU profiler。当前构建机器没有 NVIDIA GPU，本地验证不替代 RTX 实测。
