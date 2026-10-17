# alpha.76：radiance 最近命中 Query 未证实加速

2026-10-15。候选保持隔离，生产继续 alpha.73 画质修复与 alpha.67 着色器。没有重新测试 Caustica、改动安装客户端或提交推送。

## 依据与干预

alpha.68 的稀疏内部时钟记录 radiance TraceRay 约4.9µs、shadow最近命中Query约2.8µs。射线分布和调用环境不同，这不是同工作量速度比，更不能直接换算成可节省帧时间。alpha.67 已验收的阴影Query仅改变交接，保留了完整光学；本轮检查相同思路能否应用于剩余radiance。

在支持RT_RAY_QUERY的阶段，`traceSurface` 的radiance也调用已有 `traceSurfaceQuery`；不支持Query的阶段仍使用TraceRay。Query helper函数体与已接受版本逐字相同，保留TMin/TMax、flags/mask、cutout/OMM、triangle映射及distance/bary/triangle/instance。材质解码、解码前SER、RNG、Guide写入、介质和光照逻辑都未变。没有叠加alpha.74/.75。

Caustica参考提交仍为 `14770591086232e13a6b1d5320b0f2f8ef8731e3`；它的radiance仍使用TraceRay。本项是根据VL自身剩余计时提出的交接干预，不声称Caustica在radiance中采用该Query方案。Inline traversal增加代码和私有状态，不能预先承诺更快。

## 验证

一个multi_light客户端，先运行独立RUNTIME诊断block，再运行无捕获/无shader clocks的纯A–B–A。RUNTIME对当帧实际radiance射线限量记录，并在同一TLAS与OMM状态下以相同ray flags/mask分别执行TraceRay和Query，比较相对距离1e-5、绝对bary1e-5、严格triangle/instance ID。

133个工作计数帧共34048条射线，27946次命中，**零不匹配**。其中透明材质命中3次、cutout命中0次，所以不把它当作充分的植被专项验证。旧alpha.67的GPU对照仅作为相同Query helper既有支持证据，没有冒充本轮新测试。两个活跃SPIR-V/ABI验证通过；Primary增加4084B、Indirect增加4124B。

后测ENDPOINT/ENDPOINT_FAST两份捕获共26个浮点图层均有限，HDR/RR/transmission非零；这不是图像噪声或所有动态材质的质量评分。PID25624经Windows-MCP验证前台和创建身份，正常退出0。诊断block从纯计时结果中排除，整个含插桩容器仍为diagnosticOnly/profileWorkload=true，未伪装为完整生产发布包。

## 结果及限制

三个纯计时block各8秒，GPU P50单位ms：

| 阶段 | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Transport | 3.452240 | 3.486336 | 3.552352 |
| PT ready | 5.366592 | 5.409120 | 5.524416 |
| Primary + Guides | 1.670432 | 1.671712 | 1.736352 |

候选没有快过两端A；Transport/PT相对A均值下降仅0.46%/0.67%，而A自身漂移2.86%/2.90%。GPU时钟中位数2520/2505/2430MHz，最后一段最低2370MHz、温度约87℃，时钟比超出原定3%一致性条件。不能据此认定该候选一定变慢，也不能验收加速。**未通过、不保留，不追加客户端重复测试**。

几何签名1417246207890208711、40 sections、5453040 scene bytes、524×336、SPP1、FULL/EXACT/LATE及区间资源一致；主要射线工作量均值最大差约0.000115%。没有用降频归一化制造新收益数据。实际硬件register/spill/cache/occupancy仍未知。

额外审计确认A/B资源路径有效：`RtExecutionOptions.shader`改变revision，`VulkanPathTracer`随revision重建context；`VulkanRtPipeline.load`对GGX_INLINE追加基准别名，CLEAN使用候选本名。隔离JAR逐stage SHA与两种manifest条目对应；pipeline cache键对实际binaries及设备UUID做SHA-256，因此不是按同名缓存错误复用同一shader。这是代码与发布身份核验，未新增运行时逐次加载日志。

## 本轮结论

alpha.74精确span、alpha.75遮挡后太阳BSDF、alpha.76radianceQuery都没有建立净加速。它们分别验证了不同源码假设，不应继续叠加。alpha.67阴影交接的收益不能泛化到每种最近命中调用；“减少Load/计算/交接”也不等于稳定缩短GPU关键路径。

剩余优先级仍是前两层Transport的完整候选准备和FULL BSDF成本。下一轮更有价值的是一次有明确语义边界的成本上界隔离或可用的硬件shader profiler，而不是继续猜某个标量Load能解释数毫秒差距；这只是后续建议，本轮未实施该隔离或取得新硬件指标。历史Caustica多光源PT约2.214ms的目标仍未达到。

证据：工作区 `outputs/alpha.76` 的source-audit、candidate.patch、gpu-hit-parity、shader-validation、self-comparison、workload-comparison、sampling-limitations、e2e-validation、status、suite/CSV/telemetry、源文件和复现脚本。生产44个common/world源、293个class和默认110个SPIR-V保持本轮开始身份，安装alpha.73的哈希保持不变。未运行仅在性能通过后才允许执行的最终生产接受脚本。
