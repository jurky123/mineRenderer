# alpha.78：精确 Owen 类别提前判定未达验收门槛

2026-10-17。生产仍保留 alpha.73 质量修复和 alpha.67 shader。候选隔离，不采纳。

源码发现：RIS 每个候选先执行 24 轮 nested Owen hash，但 runtimeCandidateGeometry 仅用输出与环境概率比较。旧 proposal 内部探针从函数入口开始，未单独覆盖调用参数中的 directSequence 计算。Caustica 的此处 RIS 仅抽取发光物，没有相同的环境类别判定；不能借此直接删除 VoxelLight 环境采样。

候选按高位与严格阈值逐位比较，首个不同位就确定类别。0.2f 对 24 位离散序列对应整数阈值 3355444；完全相等走非环境类别。无发光物直接选择环境，SHIFT 保持原实现。原 directSequence 接口、RNG、候选数、完整材质和所有 PDF 均不变。

实际 Slang CPU 的 4194304 组类别测试零不匹配，含 262144 组逆构造阈值边界/端点，以及 OWEN/SHIFT、环境概率 0.2/1。完整 RIS 的 524288 组输出、reservoir 和完整 RNG 逐位一致。两个活跃 GPU stage 通过 SPIR-V/ABI 检查。

| GPU P50 ms | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Primary+Guides | 1.671008 | 1.658496 | 1.682080 |
| Transport | 3.472624 | 3.455104 | 3.498336 |
| PT | 5.395552 | 5.413248 | 5.459584 |

Transport 相对 A 均值改善 0.87%，PT 仅 0.26%，而 PT 基线漂移 1.18%；B 未快过两端 A，也未达到 3% 门槛。GPU 中位时钟 2520/2520/2505MHz，主要工作量差小于 0.001%。减少标量计算没有建立足够的阶段净收益；尚不能据此归因寄存器、缓存或 occupancy。

一次有效 A–B–A 客户端正常退出，两份捕获 26 个浮点层均有限；这只是基本端到端检查，不是移动噪声验收。此前误在打包完成前启动的进程已正常关闭，证据单独放在 `premature-start-excluded`，未纳入任何计时结论。启动脚本现要求 ready 标记及最终 JAR SHA 一致。

证据：工作区 `outputs/alpha.78`；候选仅在 `build/alpha78-owen-prefix`。下一步采用冻结实际输入/资源的完整工作块回放，避免继续叠加没有整帧收益的微调。
