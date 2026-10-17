# alpha.75：遮挡后太阳 BSDF 求值未获得净加速

2026-10-15。保留 alpha.73 画质修复和 alpha.67 生产着色器，本候选不采纳。没有重新测量 Caustica。

对照固定 Caustica 提交 `14770591086232e13a6b1d5320b0f2f8ef8731e3`，`indirect.rgen.slang` 的太阳 NEE 在 visibility 非零后才求 BRDF。VL 在阴影遍历前执行完整 `evalBsdf`，即使完全遮挡，仍执行 Fresnel、各向异性和多重散射等计算。alpha.68 记录的 BSDF eval 含等待延迟约3.2µs，足以支持最小实验，但不能乘调用数当作帧成本。

候选只对 connection=太阳、ROUGH_DIFFUSE、无 coat、正 outgoing hemisphere、incoming normal cosine≥1e-7 的情况延后求值。该有限材质域的 specProbability≤0.5，保留严格正的 diffuse-mixture PDF，因此可以先追踪原来的阴影而不增加无效射线。只有 transmission 非零才执行原完整 BSDF；其他材质、手持光、legacy、RNG、proposal、visibility 及光学公式保持原路径。源码中有两个条件 eval 调用位置，这不是两个同时执行的求值，但会增加静态代码。

实际从新旧生产函数提取连接循环，替换的仅为硬件交点，在 Slang CPU target 执行262144组：十类材质、有/无coat、旋转框架、相反 outgoing hemisphere、各向异性粗糙度、清晰/不透光/吸收介质、关闭太阳、六层 bounce。20个输出分量包含辐射、三类 AOV、完整 lighting RNG 和阴影调用次数，全部逐位一致；正 PDF 判定反例为0。两个实际运行 SPIR-V/ABI 通过。Primary426216→447692B、Indirect440460→461936B，均增加21476B；未测得最终机器码的寄存器或指令缓存代价。

只做一次 multi_light 自身 A–B–A，各8秒；相同 alpha.73 Java/天空/RR、原生库、表和设置，无插桩计时，counter帧排除。GPU P50，单位ms：

| 阶段 | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Transport | 3.441664 | 3.476608 | 3.456128 |
| PT ready | 5.348192 | 5.374432 | 5.379872 |
| Primary + Guides | 1.668032 | 1.661184 | 1.678720 |

Transport相对A均值慢0.80%，PT慢0.19%；没有快于两端A，也没有超过max(2%,A漂移)的净改善。**不保留**。减少被遮挡求值的代码改动存在，但它没有转化为可验收帧收益；静态代码增长、分支和状态活跃范围可能抵消收益，尚无硬件计数区分原因，不能把其中一个假设写成已确诊。

场景签名1417246207890208711、40 sections、5453040 scene bytes、524×336、SPP1、FULL/EXACT/LATE及区间资源一致；主要射线工作量均值差小于0.000474%。GPU时钟中位数2520/2520/2505MHz，各7样本；A2有轻微降频。两个捕获的26个float图层全部有限、HDR/RR/transmission非零，PID31172通过Windows-MCP核验前台并正常退出0。捕获是健全性检查，不是噪声或拖影评分。

停止叠加这项延后策略。下一个不同干预是按alpha.68剩余radiance TraceRay调用检查最近命中交接，保留SER和完整命中语义；不能把不同射线分布的Query/TraceRay微秒差直接当作预期收益。

证据和复现脚本：工作区 `outputs/alpha.75`，包括source-audit、candidate.patch、module-validation、shader-validation、self-comparison、workload-comparison、sampling-limitations、e2e-validation、status、原始suite/CSV/telemetry及候选源码。生产44个common/world源、293个class、默认110个SPIR-V保持本轮开始身份；未替换安装游戏，未提交或推送。
