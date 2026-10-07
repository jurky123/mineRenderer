# alpha.39 实机结果：修复有效，transport 获得小幅净收益

数据：`logs/rt-suite-1791359324298.zip`，schema 10，24/24 blocks valid，completed=true，295 个固定 terrain sections、签名 `-197341315052343012`、214×120、1 spp、RIS/COMPACT/QUERY、OMM/SER 关闭。所有 visibility replay mismatches=0、skippedQueries=0。报告与 alpha.39 新诊断格式一致；上传包不含明确 mod version 字段，不从 warmup.json 臆造版本证据。

## 1. 批次净耗时

下表 A/B 是四段 block median 的算术均值，提升与套件的两轮 ABBA 结论一致；不是所有帧的 pooled median，也不是置信区间。

| 模式 | FULL A（ms） | 候选 B（ms） | 两轮改善 | 套件结论 |
|---|---:|---:|---|---|
| CACHE | 7.2916 | 6.8960 | -0.006% / +10.905% | inconsistent |
| SPARSE | 7.6905 | 7.1682 | +6.368% / +7.207% | candidate_faster，合计约 6.79% |
| CACHE_SPARSE | 7.8088 | 7.3142 | +6.527% / +6.140% | candidate_faster，合计约 6.33% |

SPARSE 和组合的两轮方向一致，改善大于套件的描述性重复段变化（3.77% / 3.18%），支持这次场景中已出现净 transport 收益。不能把这些比例当成整体 FPS 改善，也不能认为 10ms→3–5ms 的原目标已经达到。

CACHE 第二轮不能归因为纯算法：A5 从太阳强度 0.2165 降到 0.0034；B6/B7 lightSource=NONE、强度为 0；A8 已切换 MOON，强度从约 0.000025 升到 0.0489。两段候选恰好处于无 sun/moon 的窗口，直接连接工作量、缓存噪声和光照稳定性都会变化。第一轮 CACHE 基本持平。当前自动 valid 标志只保证执行/地形等条件，并未排除这种光源类别切换；因此 5.45% 总数不作为 cache 单项收益证据。

SPARSE 第一轮 A9 仍经过月光强度 0.0737→0.11 的变化；其余 sparse 段和全部组合段 start/end 均为 MOON/0.11，方向仍随时间改变。组合两轮较稳，是本次更有说服力的证据。不同组发生在不同时间，6.79% 与 6.33% 不能直接当成 SPARSE vs CACHE_SPARSE 的配对对照。

## 2. 修复验证

- 所有采样段的 realtimeSceneResets 起止均为 1→1，未再次发生 committed terrain reset。
- 组合 B18 的 raw events 0→4、LIGHT-only 0→3，但 epoch 4→4，scene resets 1→1。原始 LIGHT/edit 事件不再误清 realtime 历史的路径已在实机得到支持。事件保留窗口 overflow 全部为 0。
- 四个组合候选训练：43,361 probes，43,106 写入，成功率 **99.41%**；train_locked=44、train_collision=0、train_invalid=150。另 61 次未写入，现有 counters 没有给出全部细分，不能据此把成功率宣称为 100%。
- 训练前拒绝重复 2,493 次、占用不匹配 1,210 次；这些不是已追完后缀后才丢弃的训练。旧训练结果不再主要浪费在发布锁与空间冲突。
- 组合 cache 查询 2,232,779，命中 484,238，**21.69%**；四段分别 22.47% / 21.13% / 22.30% / 20.84%，较一致。CACHE 单独为 16.23%，但跨昼夜不能简单与组合比较。
- 组合完整路径密度 **0.8460**，复用 **15.40%**；四段复用比例 15.39–15.42%。primary_fast_guide=589,294，与成功复用数相同，证明新准备旁路实际运行。
- history_better_neighbor=948,257，表明更成熟邻居的选择不是静态未用代码。不能把该数直接等同于这些路径都获得了额外复用。

alpha.38 上传中的组合 cache hit 2.74%、训练写入约 64.80%、复用 10.17%；本次有明显工作量改善。不过此前 296 sections、保护材质比例约 39.3%，本次 295 sections、保护材质约 29.3%，光照时间也不同，不能把跨版本百分比全部归因于某一个修复。

## 3. 剩余成本

| 组合 transport 分阶段（四段 median 均值） | FULL（ms） | CACHE_SPARSE（ms） | 差值（ms） |
|---|---:|---:|---:|
| primary | 1.3334 | 1.5175 | +0.1841 |
| bounce 1 | 2.9290 | 2.8135 | -0.1155 |
| bounce 2 | 2.1249 | 1.4799 | -0.6450 |
| sample resolve | 0.0207 | 0.0538 | +0.0331 |

Scope medians 不可当成同一帧直接相加；batch 的实测节省约 **0.495ms**。Primary 与历史 resolve 仍抵消部分后续射线收益。OptiX exchange 的对应 scope 均值 2.2068→2.3876ms，也没有随 transport 同比降低，需避免宣传整帧收益。

组合 cache 的 matching immature=1,129,045（查询的 50.57%）、mean error=440,506（19.73%）；geometry mismatch=88,890（3.98%），其中 hash mismatch=25,265、plane mismatch=63,625。新 immature 先经过 key/plane 匹配，终于能代表 matching 槽的训练成熟问题，不再混入另一平面。已有成熟槽的误差与训练覆盖成为更主要的 cache 限制。

组合选中历史的 confidence histogram：1–3 为 299,633，4–7 为 116,602，8–15 为 78,171，16+ 为 1,385,473。new_exposure=542,376（全部 primaries 的 14.17%），low confidence=299,633（7.83%），mean error=103,784（2.71%）。id mismatch/albedo mismatch 分别为 3,272,167/1,387,684，按候选检查计数，不能当成互斥路径比例；它们支持继续研究身份边界和粗糙漫反射纹理限制，但不足以直接证明每一种失配的主路径占比。

约 29.3% 材质/动态保护；其余未分类 primary 还包含天空/miss，不能一概称为计划刷新。渐变日光的二帧上限继续生效（gradual_limit=882,783），不通过降低保护条件强行追求全场景 0.25 密度。ROUGH_DIFFUSE 的 RGB/AOV 仍包含镜面能量，扩大 albedo 重调制需要真实 lobe 分解。

## 4. 结论与下一步

本轮确认的误失效、训练浪费与历史准备旁路已经体现到 GPU 工作量；第三轮从没有净收益变成约 6–7% 的 transport 改善。**修复有效，第三轮整体性能目标仍未达成，不能冻结。** Visibility replay 零 mismatch 不证明 cache/sparse 的能量或画质与 Reference 一致，仍需薄墙、楼梯、挖放块与动态转向的视觉对照。

下一步优先降低 primary history 分类/访存成本、以 matching cell 覆盖与真实有效观察数改善 cache 训练；要确认 cache 是否在 sparse 之上增加净收益，应在同一稳定光照条件做 SPARSE↔CACHE_SPARSE 的配对对照，而非跨独立组相减。自动验收还需识别 sun/moon/NONE 类别变化，避免把直接连接工作量变化归因于算法。上述是后续优化方向，本次仅记录分析，未改渲染代码或发布新包。
