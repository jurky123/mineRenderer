# alpha.46 Material 专项验收

2026-10-08 收到 rt-suite-1791462736946.zip，SHA-256 `6e1bd05d52b8e2a71f1aa0bac405cdbff480c7a9e2d71d719eecda0185567fab`。schema 13，版本 alpha.46，24 段全部完成且 valid，无 interruption。295 个 pinned terrain sections，427×240、1 spp、EXACT/Wavefront，受控 FIXED / OMM off / SER off。结果仅代表该场景；不是生产门禁或图像验收。

## 预热门禁

第 12/14 段在约 31.75/30.75 秒仍是 WAITING_FIRST_SAMPLE、0 个 warm GPU samples，随后继续并成功完成。alpha.46 对首次样本等待与实际耗时稳定性分离的修复通过这轮实测；不是证明首次执行长停顿已经消除。

## 描述性双轮 ABBA

| 比较 | Transport 改善 | 重复波动 | 结果 |
|---|---:|---:|---|
| FULL Monolithic → Split | −7.07% | 3.38% | candidate_slower |
| Split CACHE_SPARSE TAIL → PRIMARY | +8.62% | 6.08% | candidate_faster |
| FULL OWEN → SHIFT | +2.85% | 5.89% | within_variation |

PRIMARY 两轮分别改善 6.55% / 10.70%。该比较只证明相对 TAIL 的增量收益，不证明相对 FULL/MONOLITHIC 有净收益。跨组时间有漂移，不能把不同组拼成 FULL 对 PRIMARY 的 A/B。

各侧四段 GPU pass 中位数的均值：TAIL transport 14.987 ms / world 16.823 ms，PRIMARY transport 13.689 ms / world 15.480 ms；这不是合并帧分位数。RR 约 0.81–0.82 ms。

## 实际算法覆盖

4 个 PRIMARY 段各有 787–855 万 query、273–292 万 hit、9.3–9.9 万训练观察、217–233 万 diffuse history 定位复用。TAIL 同样有真实 query/train/hit。默认 ROUGH_DIFFUSE 不再被全部排除。

TAIL B1 alive fraction 约 0.788，PRIMARY 0.506–0.537；TAIL B2 0.441–0.444，PRIMARY 0.281–0.304。primary fullPathDensity 仍为 1 符合 rough 材质保留直接光/精确镜面的设计，不能据此否认 diffuse 缓存有效，也不能将 hit 数直接称为整条路径终止。

## 后续门禁

保留 FULL/MONOLITHIC/OWEN 默认。仍需相同日常配置的 FULL/MONOLITHIC 对 SPLIT/PRIMARY/CACHE_SPARSE production 配对，检查 client P50/P95、CPU/GPU world 和内存；室外、室内多光源、玻璃/水的图像、能量与运动对照缺失。当前不启用自动策略，不宣称 FPS 提升或画质通过。
