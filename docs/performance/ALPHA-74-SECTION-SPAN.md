# alpha.74：精确 section span 未获得净加速

2026-10-15。性能基线是 alpha.67 着色器，画质基线是用户已验证的 alpha.73。候选只在隔离目录，未改生产源代码、默认 bank 或安装游戏。

alpha.68 的 proposal 调用延迟约 4.6µs、前两层 Transport 约占 94%，支持检查发光候选读取链，但这些不是可相加的帧时间。alpha.71/.72 的叶级改动均失败，本轮验证另一级结构差异。

Caustica 固定参考提交 `14770591086232e13a6b1d5320b0f2f8ef8731e3` 的 `selectLightGridSpanLight` 在 span 中保存 self/alternate first/count。VL 原先选择 section 后还读取独立 node 的叶表范围与 bucket。本候选新增 36B section span，保留精确 threshold，以及两个结果各自的 section、原 float32 PMF、叶表字节偏移、count/bucket；叶级 alias 和按 triangle 排序的记录不变。绕过 chosen-column 回查及 node 读取，保持 local/global mixture、adaptive bucket、RNG、候选数、材质和介质语义。

header 64→80B，旧表全部保留，老 Shader 可按原 offsets 读取，便于有效 A/B。附加 span 上限 256KiB，且不得突破现有 2MiB buffer；空表或容量超限回退旧算法。实际 160-section fixture 从 473024→712080B；1024-section fixture 只增加 header 的 16B 并回退。新增内存和上传是真实代价，不能用减少源码 Load 数直接推断收益。

模块检查：实际 Java writer 的原记录/alias/node/PMF 逻辑字节一致，新增 self/alternate 元数据逐项相符。实际 Slang CPU 的 230808 组 section 阈值/邻接浮点/端点、local/global PMF、leaf ID、bucket，以及 524288 组 FULL RIS 输出和完整 RNG，均逐位一致。两个运行阶段 SPIR-V/ABI 验证通过。Indirect 中四处内联选择保留 `OpSelect +4/+20` 后直接读取结果元数据，热分支不再追读 node；回退分支仍存在。两阶段各增加 8988B，未测得硬件 cache、寄存器或 stall。

仅运行一次 multi_light 自身 A–B–A，每段 8 秒，两边使用相同新增 Java 表和 alpha.73 画质代码，只切换着色器。GPU P50，单位 ms：

| 阶段 | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Transport | 3.471488 | 3.481120 | 3.499904 |
| PT ready | 5.407360 | 5.447056 | 5.424848 |
| Primary + Guides | 1.674784 | 1.701600 | 1.683856 |

Transport 相对 A 均值仅改善 0.13%，A 漂移 0.82%；PT 反而增加 0.57%。均不满足“快于两端 A，且改善超过 max(2%, A 漂移)”条件。结论：**拒绝，不保留**。

三段场景签名 `1417246207890208711`、40 sections、5453040 scene bytes、524×336、SPP1、FULL/EXACT/LATE 及区间资源一致；主要工作量均值最大差约 0.000578%。GPU 时钟中位数 2520/2520/2505MHz，各 7 个样本，A2 存在轻微降频，不外推持续 FPS。两个后测捕获的 26 个 float layer 全部有限，HDR/RR/transmission 非零，PID33260 经 Windows-MCP 核验前台，正常退出 0。首次冷 pipeline 创建约31.49秒在预热前，不当作稳态 GPU 时间或同条件启动成本比较。

这关闭了“只绕过这一层 node 能带来显著净收益”的具体假设，不能证明整个 proposal 都便宜，也未区分额外 footprint、代码尺寸和执行延迟的贡献。停止继续堆叠 alias 布局小改，转向检查遮挡后的无用 BSDF 求值。

原始证据和复现脚本在工作区 `outputs/alpha.74`，包含 candidate.patch、module-validation、span-compiler-evidence、self-comparison、workload-comparison、sampling-limitations、e2e-validation、status、suite/CSV/telemetry、源码与 Java 快照。生产 44 个 common/world 源、293 个 class、默认 110 个 SPIR-V 保持本轮开始身份；alpha.73 的 RR/天空修复继续保留。没有重跑 Caustica，也没有提交或推送。
