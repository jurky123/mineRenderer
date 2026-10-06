# alpha.34 direct lighting 实机结果

2026-10-06，RTX 4060 Laptop，原生 Vulkan，alpha.34 的 `rt_benchmark direct` 两轮 ABBA，共 8 段。内部 214×120、1 spp、realtime，同一 295-section 地形快照；两侧实际控制均为 Query、Fixed queue、OMM/SER off，只改变 LEGACY/RIS。8 段全部 valid，无中断、跳过 timestamp 或 opaque visibility replay mismatch；共 75,776 次 replay、0 mismatch。Replay 验证的是 opaque visibility 后端一致性，不是 legacy/RIS 成图一致性。

## 性能结论

各侧四段的 GPU median 取平均：

| GPU scope | legacy ms | RIS ms | 耗时下降 |
| --- | ---: | ---: | ---: |
| transport batch | 7.899 | 5.676 | 28.15% |
| primary | 1.183 | 0.873 | 26.22% |
| bounce 1 | 2.526 | 1.854 | 26.60% |
| bounce 2 | 2.568 | 2.102 | 18.15% |
| bounce 3 | 0.485 | 0.276 | 43.17% |
| bounce 4 | 0.311 | 0.118 | 61.97% |
| bounce 5 | 0.278 | 0.104 | 62.71% |

两轮独立 transport 下降分别为 26.72%、29.58%，重复波动 2.33%，自动判定 `candidate_faster`。新旧耗时比 **0.7185**，对应 transport 吞吐约 **1.39×**。这接近目标 0.5–0.7 的上界，但没有严格达到 0.7；不能报告为 2× 或整个游戏 FPS 增加 39%。这是描述性 ABBA 结果，不是统计置信区间，也不是跨场景性能保证。

batch 与子 scope 的 median 不可直接相加：它们是不同时间分布的中位数，scope 还有固定调度开销。数据未提供 production visibility 的独立耗时，不能从 bounce 下降反推精确 visibility ms。

## 工作量结论

按每侧所有 sampled counter frames 汇总，再取 connections/surfaceHits：

| bounce | legacy 连接/hit | RIS 连接/hit | 下降 |
| --- | ---: | ---: | ---: |
| 0 | 3.723 | 2.669 | 28.31% |
| 1 | 3.750 | 2.146 | 42.79% |
| 2 | 3.709 | 2.202 | 40.64% |
| 3 | 3.644 | 1.498 | 58.90% |
| 4 | 3.701 | 1.434 | 61.25% |
| 5 | 3.601 | 1.381 | 61.66% |

本场景旧路径实际约 3.7 个有效表面连接/hit，少于设计讨论中的 5–6；新路径实现了 1–3 的目标。实际 shadow rays/primary 从 8.956 降至 6.631（约 26%），any-hit/primary 从 1.480 降至 0.981（约 34%）。这些计数与表面连接数含义不同：有后续 bounce、介质及多透明界面，不能要求每个 primary 只有三条实际 TraceRay。

B1 alive fraction 约 0.89696/0.89697，B2 约 0.527575/0.527578，后续也基本一致。候选预算、选择数与深层 roulette 计数符合新执行链。耗时改善伴随连接/any-hit 减少，且没有依靠显著减少 alive paths 获得收益。

## 当前判断与下一步边界

第二轮在这一场景有稳定、合理的收益，保留 RIS；不因 0.7185 略高于 0.7 而继续盲调 candidate budgets 或牺牲能量。数值回归已检查积分期望，但本次日志没有同 spp reference 图像、方差/均方误差或移动场景数据，不能断言所有场景画质和性能都已验收。

独立于直接光的 scene commit 约 3.14/3.17 ms，其中 BLAS 约 2.99/3.02 ms，几乎不变；material assets 约 0.800/0.801 ms。这些现在更值得在后续任务中定位，先查实际 rebuild/refit 的对象、触发原因和收益，不能仅凭这个 scope 名称断定每个 section BLAS 都在重建。仍没有实机 register spill、L1/L2 traffic 计数。

本轮 A/B 的原控制为 Query、AUTO、OMM on、SER on、RIS，结束会恢复这些控制。这次结果只证明 OMM/SER off 下的 direct 收益；前一轮两项仍缺乏稳定收益证据，作为日常性能基线建议 Query、Fixed、RIS、OMM/SER off。

需要扩大适用范围时，在密集灯源室内、日间室外和玻璃/水场景分别运行同一专用测试，并对照 reference 的平均亮度、颜色和噪声。无需先跑完整执行层 64 段 suite。
