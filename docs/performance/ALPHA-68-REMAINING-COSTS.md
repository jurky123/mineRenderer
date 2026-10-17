# alpha.68：alpha.67 后的剩余成本定位

2026-10-15。结论：旧 shadow closest-hit 交接不再是首要单次延迟；应优先检查 **RIS 提议获取与高频 BSDF 求值**。下一项最小实验明确为复用环境候选已计算的方向到 texel 坐标。本文是测量与代码审计，没有新增生产优化或加速验收。

## 当前正常 GPU 时间

一个隔离 multi_light 客户端运行 A–P–A，各采样 12 秒。A 为字节相同的已验收 alpha.67 二进制，P 为独立内部时钟诊断版本。两边使用相同 Java 诊断支持、同一设备、相同子进程 implicit-layer 设置；没有启动 Caustica或重复质量捕获。

| GPU 范围 | A1 P50 ms | A2 P50 ms |
| --- | ---: | ---: |
| World | 7.544320 | 7.530032 |
| PT ready for RR | 5.465888 | 5.499760 |
| Primary | 1.610816 | 1.630096 |
| 独立 Guides | 0.064096 | 0.064432 |
| Transport | 3.520080 | 3.515088 |
| B1 | 1.669152 | 1.660816 |
| B2 | 1.357728 | 1.360224 |
| Resolve | 0.067536 | 0.066592 |
| RR 输入转换 | 0.119808 | 0.124704 |
| RR | 1.178496 | 1.178864 |
| Scene update | 0.008288 | 0.011072 |

按同一帧的非重叠 producer timestamp 计算，B1+B2／Transport 的份额 P50 为 **94.39%／94.31%**；Transport／PT producer 约 **64–65%**，Primary 约 **30–31%**，独立 Guides 约 **1.17%**。这是逐帧比值，不是将各阶段中位数相加。PT producer 是逻辑非重叠范围之和，不是硬件关键路径计量；World、PT、Transport 等父子范围不能再相加。

环境准备 GPU 范围约 0.197ms，其中 map 约 0.110ms、distribution 约 0.081ms。它与下文每个候选的环境 CDF 查询不是同一个成本。先前精确环境缓存实验未获明确净收益，不能将它当作尚未尝试的主因。静态场景下 Scene、Guides、Resolve 和 RR 输入转换均不支持排在剩余 Transport 之前；这不外推到动态场景更新。

历史 Caustica 多光源 PT 约 2.214ms、Transport 约 1.924ms。仍有差距，但其照明和 pass 边界不同；不能将差额全部分配给 BSDF 或某一种材质。

## 内部调用延迟及频率

沿用同日同 GPU 已校准的 Device 时钟，约 1ns/tick、约 1024ns 量化。186 个计数帧交替以约 1/1024 和 1/4096 的 launch-index 抽样，每帧旋转哈希。14 个范围中的两个低成本旧探针改为环境采样和方向性多重散射，其余范围保持相同测量定义。

| 调用 | 1/1024 平均 µs | 1/4096 平均 µs | 每帧频率背景 |
| --- | ---: | ---: | --- |
| RIS proposal geometry/PDF/alias | 4.571 | 4.636 | 实际约 228 万候选 |
| Candidate emission | 1.877 | 1.897 | 抽样估计约 184 万次 |
| FULL BSDF eval | 3.182 | 3.219 | 抽样估计约 223 万次，含其他调用点 |
| BSDF sample，含内部求值 | 5.607 | 5.753 | 抽样估计约 40 万次 |
| 按命中 prepare BSDF | 4.605 | 4.503 | 抽样估计约 40 万次 |
| RIS 循环，含子调用 | 69.626 | 70.253 | 抽样估计约 40 万次 |
| 完整 EXACT visibility | 5.112 | 5.273 | 实际约 53 万表面连接 |
| Shadow 最近命中 Query | 2.767 | 2.806 | 实际约 53 万遍历 |
| 环境重要性采样，proposal 子集 | 2.446 | 2.485 | 抽样估计约 45.5 万次 |
| Surface decode | 3.113 | 3.197 | 实际约 40 万表面命中 |
| Radiance TraceRay | 4.939 | 4.917 | 实际活动 radiance queue 约 47 万 |
| 空间面积光 emission，candidate emission 子集 | 1.022 | 1.031 | 抽样估计约 161 万次 |
| preparedMultiscatter，BSDF 求值子集 | 0.359 | 0.364 | 抽样估计约 230 万次 |

频率估计来自稀疏计数扩展，有相关性，不能冒充精确的独立 workload counter。延迟包括等待、调度和父函数内子探针开销，不是纯算术时间。GPU invocation 重叠，不能将表中延迟乘频率得到帧耗时或占比，也不能相减推导“纯 reservoir 时间”。BSDF sample、BSDF eval 与 multiscatter 是包含关系。

`preparedMultiscatter` 的小数值仅对应方向性补偿与其 GGX lookup，**不包含** prepare BSDF 中八点 Fresnel 和平均能量准备。因此本轮既不支持先删除多重散射，也不声称全部能量模型成本只有 0.36µs。保留 FULL、金属和介质处理，不重复已失败的双 RIS 循环特化。

旧 alpha.66 的 shadow TraceRay 约 44–45µs，与当前 Query 约 2.8µs 的观察一致地表明热点已经转移。不过这次 geometry signature 与旧观测不同，而且诊断代码增加了两个新探针；不将前后微秒比值当作另一份严格加速证书。alpha.67 的加速证据仍以当时的干净 A–B–A 为准。

## 插桩开销与数据限制

P 的正常非计数帧 Transport 为 3.715168ms，相对两端 A 均值增加 **5.62%**；PT 为 5.770336ms，增加 **5.24%**。P 的采样计数帧再分别高约 3.42%／3.85%。后者混合了已有 workload counters 和时钟记录，不能全部归给时钟本身。两种抽样密度排序接近，但绝非零扰动；这些数据用于选择最小干预，不用于宣称可节省精确毫秒。

三个 block 均有效，GPU 范围覆盖率为 100%；FULL/EXACT/LATE、524×336、SPP 1、40 sections、5453040 scene bytes、geometry signature `-2388503987709779188` 在本次所有 block 元数据和区间资源抽样中一致。主要 workload 均值相对 A 的最大变化为 0.002842%。这个新签名与此前 1417246207890208711 不同，原因未在本轮追溯，不做跨客户端几何一致性声明。

A1/P 估计采样窗口分别有 12/11 个 GPU 时钟样本，均为 2520MHz。A2 只有 4 个样本，中位数 2520、最低 2505MHz；该段发生一次约 **4.07秒 CPU frame 停顿**，GPU World 最大约 88ms，另有 nvidia-smi 超时、遥测最大间隙 8.57秒。原因未确定。它使 A2 的样本量与持续 FPS 不具可比性，不能将这个停顿归因于某个 Shader。A1/P 无此长停顿，A2 正常帧的 GPU P50 与 A1 接近；本文采用正常 GPU producer 和 P 内调用排序，不宣称解决该 CPU/系统问题。

## 与 Caustica 的源码差异

参考仓库固定 commit `14770591086232e13a6b1d5320b0f2f8ef8731e3`。

1. **候选数据组织。** 重新核对 `RtLightHierarchy.GPU_FLOATS_PER_LIGHT=8` 与 `world_common.slang:Light`，当前 Caustica 热记录是 **32B**，不是旧审计写的 48B；LightAlias 8B、LightGridSpan 16B。VL runtimeRecord 仍为 64B、alias 列 16B，记录包括三个 float32 顶点、PMF、area、mass、triangle、instance、kind、section。Caustica 使用矩形半轴 half 打包与 packed radiance。数据内容与精度不等价，不能直接缩成同样大小；stride 差异也不等于已测到两倍带宽或 cache transaction。
2. **环境候选。** VL 有 emitter 时将约 20% 提议分配给环境；256×128 分布通过行、列二分搜索产生方向，最长分别约 7/8 次比较。Caustica 这段 RIS 只处理发光物，天空由 continuation miss 收集。因此它没有这里相同的环境 proposal 工作；不能通过删除 VL 环境采样来声称等价优化。
3. **BSDF。** Caustica 普通材质候选为 Lambert/isotropic GGX/Schlick；VL FULL 保留各向异性、精确 Fresnel、粗糙漫反射、多重散射及 PDF/MIS。后者确有更多工作，但本轮还没有它们各自独立 GPU 毫秒。
4. **太阳连接顺序。** Caustica 太阳 NEE 在 visibility 非零后才求完整 BRDF；VL 先 evalBsdf 再做 visibility。它是次级候选改法，需要先确认遮挡率及仅用 cheap predicate 能否保持原 BSDF-zero 早退，不能先新增无用阴影射线或改金属/介质规则。

## 已确认的具体重复计算与下一项实验

`runtimeCandidateGeometry` 的环境分支调用 `sampleEnvironment → environmentDensity(wi)`，后者将方向经 `atan2/acos` 映射到 texel 求 PDF。通过 receiver reject 的候选随后进入 `runtimeCandidateEmission → environmentRadiance(wi)`，再次转换同一方向。

这不只是在源码中看起来重复：接受版 alpha.67 的 FULL QUERY SER Indirect SPIR-V 中，第一次 `%13743/%13756` 是 Atan2/Acos；同一环境方向 `%13736` 经 `%14190` Phi，在 `kind==1` 分支再次执行 `%14580/%14593` Atan2/Acos。编译后这两次映射仍存在。原始反汇编、SSA 片段和哈希已保存。这证明冗余未在此编译阶段消除，不证明具体 SFU 停顿或保证净收益。

**优先的最小实验：** 将 PDF 已使用的、由实际方向转换得到的 texel index 缓存在环境候选已有的 `record` 字段，并让 radiance 读取复用它。保持其他 kind 的地址语义、原 RNG、原 PDF、空间发光与无环境功率的 fallback。不直接复用 CDF 初选 row/column，因为生成方向的浮点舍入可能把边界样本映到相邻 texel。该方案不需要新 descriptor 或近似材质。

先用实际 Slang 路径验证不同 seed、接缝／极点／零功率／边界输入的 direction/PDF/radiance、reservoir/RNG 一致性，再只做一次多光源自身短 A–B–A；净收益不足就撤回。下一项还未实施，本文不承诺百分比。

随后才考虑：保持精确比较与 RNG 的 CDF 搜索目录；对热光源/alias 实际访问和依赖链做布局干预；以及 FULL BSDF 的具体重复算式。所有方向都以新基准为准，不重复通用分支分流和结构体尺寸推断。Caustica 源码中的其他机器实验注释不能作为本机 GPU 时间。

## 交付与生产状态

一次客户端正常退出 0。44 个 common/world 源文件、293 个生产 Java class、默认 110-stage bank 与已验收 alpha.67 的哈希均保持一致。诊断源码、三个 Java 支持类、benchmark plan 和插桩 SPIR-V 仅在隔离 overlay；未修改安装游戏，没有新增生产优化。

证据目录：工作区 `outputs/alpha.68/`。主要文件为 `scope-latencies.json`、`scope-frequency.json`、`pass-comparison.json`、`frame-stage-shares.json`、`source-audit.json`、`environment-cell-duplicate.spvasm.txt`、完整纯版反汇编、`sampling-limitations.json`、`status.json`、原始 suite/CSV/telemetry、退出/焦点记录、源码快照与复现脚本。旧时钟校准作为复用证据单独保留，未冒充本轮新校准。
