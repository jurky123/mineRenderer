# alpha.64：FULL RIS 专用路径为什么没有加速

日期：2026-10-15。生产源码维持已验收的 alpha.61；本轮是 alpha.63 的归因实验，没有新增生产优化，也没有启动 Caustica。

## 结论与证据边界

alpha.63 只验证了“专用路径变慢”，当时没有验证变慢机制。将它归咎于寄存器、spill 或指令缓存都证据不足。本轮补上编译结构检查和一次 A–B–C–A 干预对照：保留同一个精确专用 BSDF，只把复制的 RIS 候选循环改为共享循环。退化大部分消失，但没有稳定超过旧版。

证据支持的结论是：**alpha.63 的循环分裂／代码生成改动带来的代价，超过了它减少通用分支的收益。源码大结构体并不是每个候选都发生一次整体内存拷贝；单独缩小参数结构体没有证明存在可削掉的对应成本。** 这是对这组实现改动的归因，不是 GPU 指令缓存、寄存器或 occupancy 的精确硬件归因，也不是 VoxelLight 与 Caustica 整体性能差距的完整解释。

还需纠正“同样分流方案让 Caustica 更快”的前提：我们没有 Caustica 采用该分流前后的实验数据。它整体更快的历史结果，不能证明轻量参数接口就是原因。Caustica 此处从入口执行较轻的计算；alpha.63 则保留完整通用路径后，追加了一套完整候选循环，二者不是相同的编译结构。

## 编译结果实际发生了什么

比较实际使用的 `material_indirect_full_query_ser`，同为 Slang 2026.19、Vulkan 1.2、INLINE GGX、相同 FULL/RIS/QUERY/SER 配置：

| 静态 SPIR-V 指标 | A：已验收 alpha.61 | B：alpha.63 双循环 | C：同一专用 BSDF，共享循环 |
| --- | ---: | ---: | ---: |
| 文件字节数 | 418448 | 454880 | 427752 |
| 指令条目数 | 24654 | 26750 | 25217 |
| `OpLoopMerge` 数量 | 54 | 59 | 54 |
| 1024 项 GGX 数组的 Function 变量／整数组 Store | 24／24 | 28／28 | 28／28 |

这几个指标是静态编译结构，不能按次数直接换算执行时间、GPU 内存流量或栈大小。驱动还可以继续优化它们；尤其不能把 GGX 数组 Store 直接叫作 spill。

三者 SPIR-V 的结构类型清单都不含源代码的 `Material`／`PreparedBsdf`／`RoughDiffuseBsdf`，只剩 Camera、缓冲区布局、payload、Medium 等结构。材质上下文已经标量化，不能依据源代码 `Material m=p.material` 推断每个候选复制一整块材质。这里**没有**证明所有类型判断也被消除了；原版反汇编仍有材质类型标量的比较和分支。

B 用编译期泛型复制普通／通用候选循环，连同提议选择、环境／光源处理、发光采样、reservoir 更新的内联代码一起扩张。C 保留完全相同的 `RoughDiffuseBsdf` 和 `evalRoughDiffuse`，在同一个循环中使用命中时计算的分类布尔值选择专用／通用评估。它重新引入每候选的一次分类布尔分支，却减少了复制的提议／发光／reservoir 代码。因此，这是代码生成／控制流这一组因素的干预，不能进一步把它当成只改变指令缓存的单变量实验。

## Caustica 的候选评估并不做同样的工作

参考版本：`work/Caustica` commit `14770591086232e13a6b1d5320b0f2f8ef8731e3`。主要对照 `shaders/pipelines/world/lighting.slang::risInitial/evalSampleContrib/lightRadiance` 和 `math.slang::ggxD/ggxG1/fresnelSchlick`；VoxelLight 对照 `light_runtime.slang::selectDirectPrepared/directIntegrand/runtimeCandidateEmission`、`bsdf.slang::evalPreparedLocal`、`terrain_material.slang::terrainLightEmission`。

| 每候选工作 | Caustica 此路径 | VoxelLight FULL，包括本轮 B/C |
| --- | --- | --- |
| 普通漫反射 | Lambert | Oren–Nayar 与方向 Fresnel 衰减 |
| 镜面 Fresnel | Schlick，多项式 | 精确介质 Fresnel，包含开方／除法 |
| GGX | 各向同性 D 与可分离 G1 | 各向异性 D／Smith 与多重散射补偿 |
| 多重散射能量 | 此 BRDF 没有同样的能量查表 | 入射方向 GGX 能量插值；出射等命中不变量已预备 |
| BSDF PDF | `evalSampleContrib` 不输出 BSDF PDF | 计算 PDF 和竞争 MIS |
| 面光源辐射 | 解码已加载 `Light.le` 的打包颜色 | 对通过筛选的面光候选，按重心坐标读取 UV、材质、颜色纹理、tint、cutout 和空间发光值 |
| RIS 候选 | 此 `risInitial` 处理发光面记录 | 还含环境候选分支及其采样／密度／辐射读取 |

这里比较的是这些函数，不表示 Caustica 整个渲染器没有其他环境或材质工作。VL 的点光、环境候选也不全执行面光源纹理路径。表格确认了额外运算和依赖读取，**没有**测出每行独立的 GPU 毫秒数。

alpha.52 已把平均能量／Fresnel 积分准备移到每次命中；不能再把它乘到所有 RIS 候选上。alpha.63 也没有消除上表中的 FULL 数学与空间发光读取，只改变了普通材质的调用和分支结构。因而仅让接口更像 Caustica，没有获得它较少工作量的全部收益。

## 正确性补充验证

- 实际 Slang CPU 目标的 64 配置 × 8192 次 RIS（524288 次）输出、随机数与序列逐位一致。覆盖八组材质、粗糙度／各向异性／F0、多重散射、体积／缓存／终止回退、点光／面光／环境／自适应提议、深度和 OWEN／SHIFT。
- 上述 RIS fixture 的出射方向沿法线，这是 alpha.63 原验证的覆盖限制。本轮另加 262144 组实际通用／专用 BSDF 对照，覆盖随机出射和入射方向、旋转切线框架、背面、4096 组法线／掠射边界、各向异性、F0 和多重散射；全部有限，最大归一化差异 0。
- A/B 使用此前各自完整校验的 110-stage bank；C 只为实际诊断配置编译并验证 Primary／Indirect 两个 FULL QUERY SER stage 及其 SPIR-V／ABI。它不是完整生产发布 bank，没有据此接受其他未编译配置。

这些验证排除了所测域中的公式实现或 RNG 改动，不能当作所有 GPU 帧逐像素一致的证明。

## 最小运行干预：A–B–C–A

一次隔离 VoxelLight 客户端，每窗口 8 秒；初始化、预热、drain 与 counter 帧不计入 GPU 时间。只测试 multi_light，不重复质量捕获，也不运行 Caustica。

- A：已验收 alpha.61 的原始二进制，通过诊断 GGX_INLINE 资源别名加载。
- B：alpha.63 被拒绝的双循环二进制，通过 CLEAN 加载。
- C：同一精确专用 BSDF，改共享循环，通过 RUNTIME 资源别名加载 **FULL 编译二进制**。
- 最后回到 A，检查前后漂移。

这些枚举只用作隔离包的二进制选择器，不启用其名称可能暗示的其他材质算法。实际 Primary／Indirect／Resolve 的资源和 SHA256 已逐项核对。临时 benchmark-plan class 安排四个窗口；生产 Java 和 shader 源码未改。

| GPU scope，P50 ms | A1 | B：双循环 | C：共享循环 | A2 |
| --- | ---: | ---: | ---: | ---: |
| World | 13.069296 | 13.648336 | 12.691584 | 12.487296 |
| Primary + Guides | 2.376512 | 2.360032 | 2.375040 | 2.368864 |
| Transport | 8.325280 | 8.867440 | 7.893248 | 7.770064 |
| PT ready for RR | 11.047536 | 11.605104 | 10.647200 | 10.529248 |
| B1 | 4.748272 | 4.891872 | 4.125824 | 4.063440 |
| B2 | 3.163344 | 3.443872 | 2.978496 | 2.949504 |
| RR | 1.180928 | 1.178368 | 1.179072 | 1.178912 |

四窗口所有已采样 GPU 频率均为 2520MHz，温度中位数为 82／83／82／84.5℃，功率中位数为 85.42／85.63／86.16／87.30W。每窗口时段由 block 完成时间扣除 2 秒 drain 与 8 秒采样估算，边界精度约一个遥测采样。几何签名均为 `1417246207890208711`，40 sections、5453040 scene bytes，资源采样期也保持一致。主要计数相对前后 A 的均值变化绝对值低于 0.002%，GPU 时间覆盖超过 95%，所有 benchmark block 有效。相机／矩阵／时钟／预算／执行控制一致，只有记录过的二进制选择器不同。

C 比 B 的 Transport 低 10.99%、PT 低 8.25%，支持复制循环／控制流改动对 alpha.63 退化有重要贡献。但 A 前后 Transport 漂移仍为 6.90%；只有一个 B、C 窗口，没有逆序重复，不能把 10.99% 作为无误差的单因素收益。C 比较快的 A2 仍慢约 1.59% Transport／1.12% PT；相对 A 均值的小幅下降也未超过漂移，所以不接受 C 为生产加速。

Windows-MCP 完成启动与焦点控制，benchmark 持续检查焦点。本轮在启动后立即保留所属进程句柄，确证正常退出码 0；不再依赖上一轮失败的 stdio focus helper 之后才获取句柄。

## 已确定与尚未确定

已确定：alpha.63 不是“照搬同一 Caustica 算法却意外更慢”，而是保留更重的 FULL 工作，并新增了一组会扩大 shader 的循环／控制流改动。原版的整块 Material/PreparedBsdf 拷贝已在 SPIR-V 层拆散；专用上下文不是凭源码尺寸就能成立的收益点。保留相同专用数学、改回共享循环，可在本次对照中消除大部分退化。

尚未确定：驱动最终机器码的寄存器数、spill、occupancy、指令缓存停顿；FULL Fresnel、GGX 能量读取、空间发光读取和 EXACT 可见性各自解释多少跨渲染器差距。既有 Nsight 指标访问拒绝和驱动未返回 RT executable statistics 的限制仍存在，本轮没有把缺失指标当作零，也没有更改全局驱动权限。需要这些硬件指标才能继续区分缓存、寄存器和调度机制。

后续应针对上表中的具体运算或依赖读取做有明确误差定义的独立实验，不能继续以“分支多／结构体大”作为已经确诊的原因，也不能把 alpha.63 退化归因为材质公式算错。本轮不保留新优化，44 个 common/world 源码哈希仍匹配 alpha.61。

证据：workspace `outputs/alpha.64/` 的 `compiler-structure-comparison.json`、A/B/C 反汇编、`binary-selector-map.json`、`ris-parity.json`、`angle-parity.json`、`mechanism-comparison.json`、逐 block CSV／资源／遥测、原始 suite、退出记录、`intervention-source/B-to-C.patch`、重现脚本和 `production-identity.json`。隔离包 SHA256：`a49e147eace6074775e4eeef1ed03f40c69b17bd2c7b2b67f64af082714da506`。
