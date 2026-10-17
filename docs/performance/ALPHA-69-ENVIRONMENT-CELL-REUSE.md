# alpha.69：环境 texel 复用未证实净加速

日期：2026-10-15。生产基准继续为 alpha.67。本轮只在隔离源码／Shader bank 实施，没有更改安装游戏，也没有重测 Caustica。

## 假设与最小干预

alpha.68 已从接受版 Indirect SPIR-V 数据流确认：同一个环境候选先为 PDF、再为 radiance 执行方向到 texel 的 atan2/acos 映射。候选实现让 PDF 将实际方向映射得到的 texel+1 写入已有 RuntimeCandidate.record，后续直接加载对应 RGB；零功率仍走原回退。没有使用原始 CDF cell 替代实际映射，避免接缝和舍入边界改变结果。其他 kind 保持 record 地址语义，RNG、提议数、材质和介质规则保持一致。

Caustica 参考 commit 为 `14770591086232e13a6b1d5320b0f2f8ef8731e3`。它的 emitter RIS 没有相同环境候选阶段，天空由 continuation miss 收集；本实验针对 VL 额外工作的重复计算，并非声称复制了 Caustica 的同功能实现。

候选两运行用阶段编译通过 SPIR-V／ABI 校验。编译结果保留 token 非零时的直接 RGB 加载路径；零 token 分支仍包含原角度转换，不能将全模块静态 Atan2/Acos 数量宣称为已减少。Primary／Indirect 各增加 1048B，可能有额外状态／控制流代价；没有硬件计数证明其造成寄存器或缓存瓶颈。

## 验证与结果

实际 Slang CPU 环境路径 262144 组，以及完整 RIS 524288 组，输出和完整 32 位 RNG 均逐位一致，最大归一化误差 0。覆盖接缝、极点、边界舍入、稀疏／HDR、零功率但非零贴图，以及 FULL 金属、玻璃／水、各向异性、多散射、terminal/cache/volume、OWEN/SHIFT。

同一隔离客户端 multi_light 做一次 A–B–A，各采样 8 秒，A 是接受版 alpha.67 的逐字节相同别名，B 是候选。以下为排除 counter frames 的 GPU P50，单位 ms：

| 阶段 | A1 | B | A2 | 相对两端 A 均值下降 | A 自身漂移 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Transport | 3.476608 | 3.462496 | 3.504800 | 0.81% | 0.81% |
| PT ready | 5.418944 | 5.404192 | 5.447008 | 0.53% | 0.52% |
| World | 7.449152 | 7.441312 | 7.519232 | 0.57% | 0.94% |

Transport P95 为 3.955450／3.977792／3.951664ms，候选没有改善。事先采用的保留条件要求 Transport 与 PT 同时超过两端 A，下降幅度超过 `max(2%, A 漂移)`；本次未通过，不把亚百分比变化当成已验证收益，也不宣称证明候选完全没有收益。

三个 block 有效且 GPU 范围覆盖完整，geometry signature 均为 1417246207890208711，40 sections、5453040 bytes，FULL/EXACT/LATE、524×336、SPP 1 与场景资源区间抽样一致。主要 workload 均值最大变化 0.000432%。三个窗口各有 7 个遥测样本，时钟中位数 2520／2520／2505MHz；边界存在约一个遥测周期的不确定性。

独立于计时的 ENDPOINT／ENDPOINT_FAST 捕获共 26 个 float layer 均有限，HDR、RR 输出与 transmission 非零；客户端正常退出 0。这是端到端健全性检查，不是逐像素画质等价证明。Driver 起止实际属性均 focused=true、相机相同；单独的 windows-mcp-focus.json 在客户端已退出后才执行，因此其中 false 不代表计时期间失焦。

## 决定与后续

不采纳该候选；44 个 common/world 源文件、293 个生产 Java class 和默认 110-stage bank 均验证仍为 alpha.67。源码冗余是真实证据，但被执行次数、与其他工作重叠及新增状态的共同作用尚未分离，不能据此承诺帧时间收益。下一项最小干预考察 CDF 二分搜索的串行读取链，保持原 upper-bound 比较和 RNG；仍须实际净收益才能保留。

证据：工作区 `outputs/alpha.69/` 的 status、module-validation、shader-validation、self-comparison、workload-comparison、sampling-limitations、e2e-validation、原始 suite/CSV/telemetry、两份捕获、候选源码／patch／反汇编和复现脚本。所有变更仍未提交。
