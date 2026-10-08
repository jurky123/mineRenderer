# alpha.45 production FULL 基线验收

2026-10-08 收到 `rt-suite-1791458046169.zip`，SHA-256 `7f5c631671add6ce0608fec3e531a6829e627ad645aeb86fa7d6f9d9f41e23b8`。日志与原始 ZIP 不提交。production JSON 明确版本 0.39.0-alpha.45，RTX 4060 Laptop / NVIDIA 591.74 / Vulkan，DLSS RR Performance 427×240 → 854×480，1 spp。4 段各 20 秒，全部 valid，回放 mismatches / skippedQueries 为零。

## 配置与范围

全部为 FULL / MONOLITHIC / TAIL / OWEN / EXACT / WAVEFRONT / EXCLUSIVE / RIS。请求 queue=AUTO，实际 queue=FIXED；OMM/SER 开启。帧上限 260，VSync requested=false，presentation throttleReason=NONE。场景 live，有 314–319 个地形 sections；首段动态模型 217–218。

本次只有一个 production FULL 基线 ZIP，没有 alpha.45 material 专项、CACHE_SPARSE production 或对应画质截图。不能判定缓存覆盖、路径减少或缓存性能/画质通过，也不能与 alpha.44 不同配置的受控测试直接计算版本收益。

## 逐段测量（毫秒）

| 段 | Client interval P50 | Client interval P95 | GPU world P50 | Transport P50 | B1 P50 | B2 P50 | RR P50 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 15.500 | 25.888 | 13.598 | 11.428 | 3.896 | 3.178 | 0.835 |
| 2 | 15.127 | 35.041 | 13.525 | 11.417 | 3.923 | 3.198 | 0.836 |
| 3 | 15.332 | 31.916 | 13.583 | 11.450 | 3.942 | 3.207 | 0.836 |
| 4 | 15.671 | 36.748 | 13.586 | 11.441 | 3.939 | 3.198 | 0.836 |

以上来自 summary GPU timings 与各段 cpu_timings.json 的 client_frame_interval。不是合并帧分位数，不将各 pass 中位数相加当作总帧时间。Client interval P50 的倒数约 63.8–66.1 FPS，不能代替平均 FPS。

GPU world P95 为 15.529–15.745 ms，CPU scene commit P50 为 2.033–2.465 ms / P95 为 12.659–15.245 ms；dynamic capture P50 为 1.255–1.535 ms / P95 为 1.646–2.400 ms。CPU 范围可能嵌套，不能相加。Client P95 比 GPU world P95 更高，CPU 提交尾部值得继续定位，但现有分位数不能证明它就是慢帧的唯一原因，也不能把 Vulkan host API wall time 全部当作纯 CPU 工作。

## 缓存覆盖解释

FULL 运行不分配实时 policy state：首段 rendererStart/End 的 realtimeStateBytes=0。4 段 cache_queries / cache_hits / cache_trained 与新增 lobe counters 均为零，符合 FULL 配置，不是 ROUGH_DIFFUSE 覆盖失效证据。B1 alive fraction 约 0.795–0.796，B2 约 0.499–0.500，是待比较的基线。

## 待补验收

同场景、同窗口尺寸与限帧条件先执行 `rt_benchmark material`；再开启 `rt_realtime cache_sparse`、`rt_primary split`、`rt_cache primary`、`rt_sampling owen`，等待场景稳定后执行 `rt_benchmark production`。上传新 ZIP，并注明场景。室外、室内多光源、玻璃/水的 FULL 对照及截图仍待提供。

必须确认真实 query / training / hit、B1/B2 工作量变化、client P50/P95 和 GPU world 净收益，以及 GI 能量、漏光、镜面和运动质量。当前默认保持 FULL/MONOLITHIC，不据本次基线启用 Adaptive Execution。
