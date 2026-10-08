# alpha.44 实机专项验收：执行链运行，缓存覆盖未验收

来源：`logs/rt-suite-1791453252409.zip`，SHA-256 `c713cd84f6ae9ac0be941f383d12477a62cf04d88e80ce8dd4888307daf978d9`。版本 0.39.0-alpha.44，RTX 4060 Laptop / NVIDIA 591.74，Vulkan，427×240 PT → 854×480 DLSS RR，1 spp，固定 300 个地形 sections，24 段完整。12 秒采样，双轮 ABBA；FIXED / OMM off / SER off / EXACT / Wavefront。VSync=false，frameLimit=260，throttleReason=NONE。

## 结论

三组均为 `within_variation`，没有可靠净性能收益。Primary Split 与独立 cache train dispatch 已实际运行；DLSS RR 生效。**缓存组的查询、训练、命中与 history reuse 全部为零，本次不能用于验收 B0 cache 或提前 history 的算法收益。** 24/24 工作负载有效只是执行配置、采样和工作集检查通过，不等于算法覆盖或画质验收通过。

| 比较 | Transport GPU A → B（ms） | ABBA 改善 | 重复波动 | 判定 |
| --- | --- | --- | --- | --- |
| MONOLITHIC → SPLIT（FULL） | 24.090 → 24.664 | −2.38% | 2.86% | 波动内，未提速 |
| TAIL → PRIMARY（SPLIT/CACHE_SPARSE） | 25.255 → 24.848 | +1.59% | 2.91% | 波动内，且没有 cache queries |
| OWEN → SHIFT（FULL） | 23.722 → 23.078 | +2.68% | 4.72% | 波动内，不能替换默认 |

A/B 时间是每侧四段 median 的算术均值；改善/波动使用原报告的 ABBA 结果，不是统计置信区间。各组场景运行状态存在漂移，不能把跨组更快直接归因于对应算法，也不能把不同版本、太阳角度和场景的绝对时间混比。

## 缓存为何没有覆盖

08 个 CACHE_SPARSE 段均只报告 `eligible/full_paths` 非零，`cache_queries/cache_hits/cache_trained/probes/reused/history_updates` 全为零；所有对应路径密度为 1。status 显示 cache 请求已开启、policy allocation 为 48,323,072 B、每段仅 1 次初始 scene reset，无持续 epoch reset。故不能把零收益归因于本次缓存频繁失效。

本地代码核查找到明确的覆盖限制：

- `Material3.fromProfile()` 默认采用 `ROUGH_DIFFUSE`。
- 内置 `materials/vanilla/blocks.json` 有 942 个 `rough_diffuse` 条目、0 个纯 `diffuse`；其他材质为 transmission、coat、metal、glass、水和发光。
- `terrainPrimaryGuide()` 只接受纯 `DIFFUSE`，拒绝 `ROUGH_DIFFUSE`；SPLIT 的 shade 已跳过后置 sparse 检查，因此普通粗糙地形不能更新或复用该 history。
- `rtTail()` 同样只接受纯 `DIFFUSE`，普通粗糙地形在计数 query 前就返回。

这些代码限制与本次全零覆盖一致。报告没有逐像素材质分布，不能宣称逐个命中的材质已经从 GPU 读回确认；但是当前实现与默认材质配置不匹配，不能称为日常场景有效的 Cache 2.0。

此处不能直接去掉 `m.type != DIFFUSE` 保护：ROUGH_DIFFUSE 包含真实 GGX 镜面 lobe，用 diffuse cache 替换整个 continuation 会丢掉镜面贡献。后续应按 lobe 分解：保留直接光和精确 specular continuation，缓存仅承接 diffuse 间接项；训练对应 diffuse estimator 与 PDF，保证没有重复计入/遗漏 lobe。历史分类、counter 与验收也必须覆盖真实默认材质。不能通过修改默认材质为 DIFFUSE 来制造缓存命中或提速。

## GPU 热点与端到端

FULL/MONOLITHIC 组 B1 8.042 ms、B2 8.630 ms，约占 Transport 24.090 ms 的 69%。SPLIT 的 visibility 0.134 ms、shade 5.207 ms；MONOLITHIC primary 4.687 ms。完整拆分本身增加工作，尚未有提前复用抵消成本。不同 scope 的分位数不能当作同一帧直接求和。

DLSS RR 各组约 0.85–0.88 ms，场景 GPU commit 约 0.35 ms；主要瓶颈仍是 transport，继续优先分析 B1/B2。自动 driver executable 查询均没有返回统计，不能由此判断寄存器、spill、ALU 或 cache miss 已改善；仍需要 Nsight 实测。

| 比较 | Client frame P50 A → B（ms） | Client frame P95 A → B（ms） | GPU world median A → B（ms） |
| --- | --- | --- | --- |
| Primary Split | 26.842 → 27.415 | 33.253 → 33.288 | 26.446 → 26.978 |
| B0 cache | 27.792 → 27.487 | 32.633 → 32.356 | 27.602 → 27.211 |
| SHIFT | 26.507 → 25.726 | 31.786 → 33.312 | 25.955 → 25.350 |

上述也是各侧四段分位数的均值，不是全样本 pooled P50/P95。SHIFT 虽然 P50 较低，P95 较高；本次不足以判断尾延迟可靠退化，也不足以宣称整体体验改善。CPU wall 和 GPU timestamp 分开，counter 帧按现有规则剔除。

## CPU / 动态几何

状态中的每帧复用 mesh 为 58–67，重新转换为 152–161，动态总数约 219；缓存复用运行，但大多数动态模型仍须重算。保留数组 322,176–519,936 B。CPU dynamic capture 的组均值约 1.44–1.75 ms、scene commit 约 2.21–2.66 ms，两者可能嵌套，不相加。该专项没有动态复用开/关 A/B，不能用 alpha.42 的绝对 CPU 值证明本轮优化收益。

世界维护总 CPU median 约 0.020–0.022 ms，其中 compile 约 0.015–0.016 ms、upload 约 0.001–0.002 ms；仅代表固定视角与本场景，不能外推高速移动、区块加载或方块编辑。没有理由据此删掉必要维护逻辑。

## 验收边界与默认配置

- 文件中没有 production suite / production JSON，因此尚无 AUTO、OMM/SER、live inputs 的日常配置基线。
- 没有截图、视频、图像对照或移动回归；没有完成同画质、玻璃/水/动态实体、DLSS motion 与 world lifecycle 验收。
- opaque visibility replay 的 24 段 mismatches 均为 0、skippedQueries 均为 0；只验证该采样的遮挡查询一致性，不是完整图像或 GPU 并发正确性的证明。
- 本套使用 EXACT，未隔离测试 shadow fast decoder 的增量性能；不将历史 alpha.42 FAST 收益算作 alpha.44 decoder 的收益。

维持 MONOLITHIC / TAIL / OWEN 默认。保留实验 SPLIT/PRIMARY 开关，但在默认 ROUGH_DIFFUSE 材质下，缓存功能验收未完成。下一次算法修改必须先证明 queries/training/hits 与 lobe 保护真实执行，再以同画质世界帧时间及 P95 作结论。
