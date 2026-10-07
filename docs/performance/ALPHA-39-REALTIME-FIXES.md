# alpha.39：失效契约、四路平面缓存与稳定历史

修复依据：[alpha.38 深入审计](ALPHA-38-DEEP-ANALYSIS.md)。FULL 继续作为默认，Reference 不启用 cache/sparse。尚无 alpha.39 RTX 实测，不能据此宣布第三轮通过。

## 已落实

- `RtInvalidationQueue` 明确使用 section 坐标和半开 long 边界，调用方先转 long 再 `max+1`。单 section 不额外覆盖邻居，整列包含正常/极限高度。新增保留 reason 的 `changesSince(cursor)`，多个消费者互不消费，1024 条保留窗口溢出明确报告；局部 snapshot revision 以 floor 保守退回，过期任务仍被拒绝。
- 原始事件不再直接清空 realtime cache/reconstruction。两者共同跟随 **实际提交的 RT terrain 内容**；同版本、未接受或驻留之外的事件不会直接触发全局重置。静态删除与实际 accepted 更新推进 epoch，动态删除不伪装成静态修改。Reference 仍保留原始事件的累积失效规则。stats 分开报告 raw events、LIGHT-only events 和 committed reset。
- 同一个 2-block cell 采用四路 bucket，key 增加 cell-local 平面量化，保持 65536 槽/10MiB。精确 plane/normal/support 校验仍保留。先匹配空间身份，再检查成熟数，原 immature 计数不再遮蔽错平面。
- 训练前认领 cell/plane 的当帧槽，再获取 bounded ticket，每槽每帧最多一条 suffix。发布锁和 frame claim 独立，pending 固定目标地址，resolve 不重新挑槽；减少追完 suffix 才发现重复/空间不匹配的浪费。
- 轴对齐面用 allocation owner、局部方块 tile、面方向与精细平面组成稳定 surface identity，同一 quad 的两三角形可以复用；非轴对齐保留 triangle。几何/拓扑提交仍推进 epoch，保留 material/position/normal/roughness 校验，不把薄墙或其他 owner 当成同面。
- 最近匹配历史成熟时保留；低置信度时在最多 2×2 匹配候选中比较真实更新数。保留实际样本均值，不把复用帧充当观察。
- 合格平坦 diffuse primary 延迟 UV tangent/normal frame 构造。复用成功跳过该准备；需追踪时补回完整原 frame。材质分类、纹理读取与首射线仍执行，没有宣称整套材质解码被省掉。
- schema 10 共 44 项 realtime counters：保留前 28 项，新增 claim 去重/占用、空间冲突类型、history 匹配失败、置信度区间、邻居选择与 fast guide。诊断仍每八帧采样，从 GPU timing 聚合排除；ABI/PathHot 64B 和 feedback 大小不变。

## 有意保留的正确性边界

没有使用只失效编辑 cell 的不完整局部策略。挖墙、移动遮挡或发光变化可影响远处阴影与间接光，训练还依赖未命中的空空间；在没有完整路径依赖追踪之前，**实际驻留静态 RT 内容变化仍全局重置 cache 与 reconstruction**。原始事件与实际内容的分离已消除已确认的误重置入口，不能为了提高 hit 率省掉真实遮挡变化的失效。

ROUGH_DIFFUSE 含镜面能量；现有 AOV 不是严格 lobe 分解，因此不扩大跨纹理反照率重调制，只对纯 DIFFUSE 采用原安全条件。日光渐变仍最多隔帧，约 39% 保护材质场景的 sparse 理论密度限制不靠降低安全条件强行突破。

四路查询和 frame claim 也有成本，最终以整个 transport batch 净时间验收，不能只看 bounce 减少。GPU 锁并发、耗时、视觉仍需实机；CPU fixture 验证数学与顺序语义，不能代替这些结论。

## 客户端验收

替换 alpha.39 jar 后运行 `/voxellight rt_benchmark realtime`，上传导出的完整 zip。优先检查组合 ABBA 的 batch 净收益、primary/B1/B2/resolve、matching immature、trained/probes、train_duplicate/locked/collision、confidence 分布与 raw events/committed resets。Mismatch 计数按候选检查，并非互斥主路径分类。

画质检查同曝光 Reference：薄墙两侧、楼梯/半砖不同高度、挖开再补回墙、雨湿变化、快速转向、新暴露区域、水/玻璃/镜面。发现残影或漏光需同时提供场景与完整日志；自动耗时测试不负责证明能量正确。

## 本地验证

`./gradlew build clientKit` 完成：337 项 Java 测试，0 failure/error/skipped；24 个生产 RT SPIR-V stage 校验且 source hashes 与当前源码一致；Minecraft GLSL pipeline 链接通过。实际 Slang CPU fixtures 覆盖同 cell 双平面红/蓝响应、quad diagonal/owner/薄平面 identity、训练 claim 去重且重复不占 ticket、空间匹配先于 maturity、邻居 confidence 保留，以及延期 frame 的完整回退；完整 terrain/native 32 cases 数值误差上限约 2.98e-8。原直接光、介质、BSDF、SH、MIS 数值回归均通过。CPU claim fixture 是顺序语义验证，GPU 并发仍以客户端结果为准。
