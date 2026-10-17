# 2026-10-17：当前差距、完整工作块证据与优化处置

目标为五个固定场景 PT P50 不超过历史 Caustica 的 120%，保留 FULL/EXACT、金属和已验证的移动画质。当前尚未达到目标。本轮完成五场景基线、实际 GPU 输入/输出捕获、冻结资源回放、独立离线执行器和两项隔离优化实验；没有新增通过验收的生产优化。

## 热点闭环

| 差异／假设 | 当前证据 | 最小干预与结果 | 处置 |
| --- | --- | --- | --- |
| 多光源/建筑主要落后在 PT 照明工作 | 当前 PT 5.569/5.293ms；历史 Caustica 2.214/1.890ms；近期稳定自身基线多光源约 5.4ms。后段降频与历史照明差异单列 | alpha.77 更新全部场景，不重新启动 Caustica | 继续以 PT 和前两层 Transport 为首要范围，不能把旧 alpha.50 时间当成当前实现 |
| 候选准备包含高频序列和资源访问 | 386 条实际普通表面回放，完整候选准备约 0.041ms、完整 RIS 约 0.068ms；Caustica 此处不做相同环境类别计算，记录也更紧凑 | alpha.78 精确提前结束 Owen 类别判定：419万分类、52万完整 RIS 对照通过，但 PT 改善仅 0.26% | 不保留；停止将“少算很多 hash”直接视为整帧收益 |
| FULL 材质准备/求值比 Caustica 普通材质承担更多数学 | prepare/sample 实际回放约 0.022/0.0145ms；FULL 保留精确 Fresnel、各向异性、粗糙漫反射、多重散射和 MIS。prepared 参数已经复用 | 独立 Compute 回放与原输出对照：离散/RNG 一致，唯一超过严格 1e-5 的分量为 PDF 相差1.27e-5 | 保留测量设施；不能用 RIS 与 candidate 时间相减宣称 BSDF 帧占比；不重复已失败的整循环特化/平均能量预积分 |
| Caustica 内循环可能减少 VL 的状态往返 | VL 前两层约占 Transport 94%；源码确认阶段间 store/load，Caustica 使用 Indirect 内循环 | alpha.80 仅融合首两次 continuation，保持光学和 RNG；同2520MHz，PT退化16.31%、Transport退化25.48% | 不保留。只有“这项融合有代价”被证实，寄存器/spill/cache具体原因仍未知 |
| RR/后处理或静态 Scene 是主要落后原因 | 当前 RR约1.175–1.243ms，接近历史参考；主要 PT 差距仍在 | 无针对这些模块的无依据重写 | 维持低优先级；动态场景更新单列，不能由静态结果外推 |

回放是固定小样本的独立工作块，缓存与线程分组不同，含记录读写/校验。不能将这些数值乘调用频率、相减分摊或直接预测生产加速。普通材质样本不能代表金属、玻璃、水和体积。Nsight 寄存器、spill、cache、occupancy 尚未取得，仍是具体硬件归因的证据缺口。

## 可复用交付

- alpha.77：五场景原始 suite、P50/P95、CPU/GPU 范围、工作量和频率；报告 `ALPHA-77-CURRENT-BASELINE.md`。
- alpha.79：1158 条实际函数输入/输出、全部相关 GPU 资源、schema、raygeneration 和独立 Compute 回放；报告 `ALPHA-79-FROZEN-BLOCK-REPLAY.md`。
- `tools/replay_lighting.py` + `tools/native/OfflineLightingReplay.java`：独立 Vulkan GPU 回放，不启动游戏；`tools/validate_lighting_replay.py` 输出误差明细和严格/用户容差两种结论。
- 启动器识别 diagnosticOnly manifest，防止无计数诊断包被误标成正式整帧基准。

独立执行器第一次通用入口验证被 RTSS Vulkan overlay DLL 触发 native crash；保留 crash 文件，随后将与隔离客户端相同的禁用 implicit overlay 环境设置限定在子进程，重试正常完成。没有修改全局驱动/注册表。该故障未混入性能结论。

## 生产和后续边界

44 个 common/world shader 源、293 个生产 class 和默认 110-stage bank 与本轮前身份一致。安装 alpha.73 SHA-256 仍为 `43fc01de70d255021ea1246b86b11a00e714447247c5fc1f84baffc57e6dbdff`。实验均隔离，没有安装、提交或推送；状态校验在 `outputs/alpha.77/production-integrity.json`。

当前有效进展是补齐定位/复现实验能力与排除两项假设，不是达到 Caustica 或已有新加速。后续候选应先在冻结输入上检查完整工作块成本和数值，再进入一次游戏 A–B–A；仅离线回放更快不能验收。需要新的完整候选数据路径/编译后活跃值证据，再决定布局或 BSDF 改动；不继续叠加本轮失败候选，不通过降低 SPP、候选数、路径上限或材质效果完成预算。

## 后续 alpha.81–85：完整候选和 FULL BSDF 已分拆

新增2217个实际候选的几何、emission、f/PDF/MIS和RNG参考表，以及18入口离线回放。完整RIS单项替代支持优先调查候选准备，尤其是Caustica emitter RIS不存在的环境CDF分支；不再将差距笼统归因于FULL材质。共享编译后重复D、96B完整发光热记录都没有完整RIS净收益，因此没有进游戏。

精确CDF索引离线完整RIS约改善4%，进入唯一一次有效游戏A–B–A。同帧GPU构建/复制全部计入候选，2520MHz三段同频、工作量一致，PT5.402336/5.290784/5.409280ms（改善2.13%），Primary约改善5.57%，World约改善1.09%。收益超过本轮漂移，但低于3%门槛，依约仍不保留。没有补跑有效实验，没有替换生产或安装包。

完整证据、数值边界、便携工具和撤回原因见 [工作块报告](ALPHA-81-CANDIDATE-BLOCKS.md) 和 [CDF索引实验](ALPHA-85-CDF-GUIDE.md)。新的汇总入口为 [VOXELLIGHT-VS-CAUSTICA-PROFILING.md](VOXELLIGHT-VS-CAUSTICA-PROFILING.md)。原61项默认源、293个class、110个默认stage身份和安装SHA再次一致；具体硬件和生产wave归因仍未取得。

## 后续 alpha.86：CDF 增量通过保留门槛

继续对照Caustica，并将完整RIS中的行搜索、列搜索、方向PDF分别干预后，确认列搜索值得优先处理。新索引直接使用原RNG高8位，精确上下界由同帧GPU CDF构建；浮点单调性保证搜索仍包含原结果，不需要alpha.85的端点回退访问。数值契约及百万GPU种子逐位通过，离线RIS约改善10.23%。

唯一一次新性能客户端PT为5.405552/5.202816/5.421216ms，改善3.89%；Primary7.60%、Transport2.50%、含构建World2.45%，PT/World P95下降。频率中位数2520/2520/2505MHz，差约0.60%；B比同频A1也快。工作量、几何及预算一致。画质第一次因流入区块不同无效，仅补一次；排除补测中最初不完整静止配对后，6份移动/恢复捕获几何及恢复相机匹配，没有异常reset，噪声/细节代理无明显退化。限制和失败检查均保留，不声称全部图像逐像素等价。

保留源码及完整110-stage bank，8项相关检查通过，完整包alpha.86已生成，未安装、提交或推送。目标仍未达到：5.202816ms到2.6568ms预算还需约48.94%。新基线的完整RIS干预将下一优先项指向完整发光候选选择及精确emission读取；不再盲调CDF、单个node或算术式。详见 [alpha.86](ALPHA-86-RNG-CDF-GUIDE.md)。
