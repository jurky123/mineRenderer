# alpha.67：EXACT 阴影最近命中交接优化

2026-10-15。保留 shadow inline RayQuery 优化。相对已验收 alpha.61，同客户端多光源 A–B–A 的 Transport P50 下降约 **52.2%**，PT ready 下降约 **46.1%**。没有减少 SPP、候选或 bounce，没有替换 FULL 材质或 EXACT 光学模型。

## 热点 → 对照 → 干预

[alpha.66 内部计时](ALPHA-66-SCOPED-GPU-LATENCY.md) 给出的含等待调用延迟：FULL BSDF eval 约 2.9–3.0µs，candidate emission 约 2.4µs，shadow TraceRay 最近命中交接约 44–45µs，普通 radiance trace 约 5.3µs。这些是诊断 Shader 的 invocation 延迟，不能累加为帧时间；它们只负责选择下面的干预对象。

Caustica `shaders/pipelines/world/trace.slang:visibility` 使用 first-hit termination 和 skip-closest-hit，透明处理在 any-hit 完成。VoxelLight 的 EXACT 使用有序最近命中逐边界处理 RGB 消光、Fresnel 和八层介质。此前状态中的 `visibility=QUERY` 只表明相应 Query 能力／opaqueBlocked 路径启用，**不代表混合几何 EXACT 的每次最近命中已经使用 Query**。该路径实际上仍调用普通 TraceRay 和 material_closest_hit。

本次借鉴的是缩短阴影交接路径，而非复制 Caustica 的平均透明度近似。Query-enabled 变体只在 `traceSurface(..., shadow=true)` 中 inline 遍历同一 TLAS；非不透明候选使用原 `visibleTexel` 决定是否 commit，最终返回最近命中的距离、barycentrics、三角形和实例编号。实例／几何分组／primitive 映射通过同一 `triangleIndex` 生成，ray flags、mask、TMin/TMax 保持一致。OMM 状态沿用原配置。普通 radiance TraceRay 与不支持 Query 的回退保持原路径。

原 `visibility` 的 24 边界顺序、介质身份／嵌套、Fresnel／消光、火把源自壳排除及后续 transport 代码没有改动；接受脚本逐字核对这整段源码。生产只改变一个 common/world 源文件。没有新的 Java、GPU buffer、descriptor、每帧上传或 barrier。

## GPU 命中对照与端到端

玻璃／水客户端先运行一个独立诊断窗口，抽取实际 shadow 射线后，在相同帧的同一 GPU TLAS／OMM 上分别运行旧 TraceRay 和新 Query。距离与 barycentrics 使用 `1e-5` 的相对／绝对检查，triangle/instance ID 要求相同。

| 对照内容 | 数量 |
| --- | ---: |
| 检查射线 | 40960 |
| 有交点 | 16655 |
| 命中带 cutout 标志的几何 | 133 |
| 透明／透射材质交点 | 8349 |
| 不匹配 | 0 |

160 个计数帧的两份统计（内部计数与既有 visibility mismatch 计数）一致。此窗口的 capture/replay 代码只在 RUNTIME 诊断资源中存在，**不在参与性能验收的纯 CLEAN Shader 内**。该对照验证交点获取，不声称给出了全部最终 HDR 像素的误差上界。

随后纯候选完成 ENDPOINT 和 ENDPOINT_FAST 两次捕获：26 个 float 图层均有限，HDR/RR/transmission 非零；客户端正常退出 0。它们确认端到端可运行，没有将有限值检查当成逐像素画质等价证明。多光源验收客户端也正常退出 0，没有重复质量捕获。

## 速度验收：几何稳定的多光源 A–B–A

每段 8 秒，A 是已核对哈希的原 alpha.61 二进制别名，B 是纯 shadow Query。计时 raygen 不包含函数时钟、命中记录或对照代码；剔除常规计数帧。因资源 bank 同时包含单独的 parity 资源，整个运行保留 diagnostic-bank 分类，不宣称为新的正式跨 Renderer 基线。

| GPU 范围 | A1 P50 ms | B P50 ms | A2 P50 ms | B 相对两端 A 均值 |
| --- | ---: | ---: | ---: | ---: |
| World | 12.523520 | 7.599104 | 12.243968 | -38.64% |
| Primary + Guides | 2.379264 | 1.712160 | 2.508784 | -29.95% |
| Transport | 7.573728 | 3.531552 | 7.206400 | -52.21% |
| PT ready for RR | 10.377664 | 5.510592 | 10.064640 | -46.09% |
| B1 | 4.069600 | 1.677824 | 3.860480 | -57.68% |
| B2 | 3.116000 | 1.365152 | 2.960224 | -55.07% |
| RR | 1.177184 | 1.197056 | 1.199392 | +0.74% |

Transport P95 为 8.336557 / 4.027178 / 8.026259ms；PT P95 为 11.193907 / 6.140045 / 11.046918ms。收益同时出现在 P50/P95，明显超过两端 A 的 Transport 4.97%、PT 3.06% 漂移。Primary 也包含首层直接光与阴影，所以其下降不是纯 guide 算法优化。父子范围不能相加。

所有 block 有效，剔除计数帧后 GPU timestamp 覆盖率超过 95%。FULL/EXACT/LATE、524×336、SPP 1、terrain signature `1417246207890208711`、40 sections、5453040 scene bytes 在区间元数据及全部资源抽样中一致。工作量均值相对两端 A 的最大变化 0.009681%；RIS 为 -0.001877%，surface hits 为 -0.001787%。不支持“靠少算了一批射线／候选”解释该量级收益。

估计采样窗口 GPU 频率中位数 2520 / 2505 / 2475MHz，温度中位数 83 / 86 / 86°C。B 并没有比 A1 更高的频率；频率差远小于加速幅度。不过这仍是一次短 A–B–A，没有逆序重复，不把 52.21% 当作所有设备／场景的精确常数。

观测开始和结束的相机、矩阵、世界时钟、焦点保持一致。suite 结束后恢复 CLEAN 会重建 context，结束快照中的 renderer 资源可能处于初始构建状态；它不是最后一个计时 block 的几何证明。计时几何一致性来自每个 block 的元数据及区间资源抽样，而不是用恢复后的资源冒充。

## 玻璃／水性能观察的限制

同一玻璃／水客户端的纯 A–B–A：Transport 为 6.476768 / 2.206320 / 6.160752ms，PT 为 8.742016 / 3.880352 / 8.456368ms。改善幅度也很大，但 sceneBytes 在各窗口及窗口内部约 5396640–5400960 范围变化，静态 terrain signature 不变，动态字节变化根因未在本轮定位。因此此组仅作性能观察，严格验收依据是上面的稳定多光源；不隐去这个限制。

## 归因、剩余差距与保留范围

数值／命中检查支持最近命中语义一致；干净 A–B–A 支持 **EXACT shadow 的 TraceRay/closest-hit 获取路径是此前一个主要性能成本**，改变这一处交接方式足以产生明显净收益。寄存器、spill、occupancy 或 instruction-cache stall 仍未获得硬件计数，不能进一步声称某种具体硬件原因已确诊。

历史 Caustica 多光源 PT ready 约 2.214ms、Transport 约 1.924ms；本轮候选为 5.511ms、3.532ms，仍未达到目标。两边照明和 pass 边界不同，这些历史值是方向参考，不能解释为剩余时间全部来自某一种 BSDF。下轮应在新基准上重新抽样 RIS proposal/BSDF/发光路径，旧版含等待延迟不能直接当作新版成本；不要回到已失败的循环分裂或小结构体推断。

最终正常 bank 的 110 个 SPIR-V／ABI 校验通过；运行中使用的 Primary/Indirect FULL QUERY SER 二进制与最终 bank **字节哈希相同**。默认 build/vulkan-rt 更新为已验收结果，bank 不包含 profile-workload 标记或 parity capture。全部 44 个 common/world 源中仅 material_transport.slang 相对 alpha.61 改动。安装游戏未替换，尚未提交／推送。

本轮诊断及验收子进程均局部禁用 implicit Vulkan layer，与 alpha.66 校准环境一致；A/B 使用同一环境，无全局设置改变。不能将相对历史不同 layer 环境的时间差算作此次优化；上表来自同客户端对照。先前 alpha.65 的 native destruction 卡顿根因仍未确定，本轮两个验收客户端正常退出不等于已经修复该问题。

证据保存在工作区 `outputs/alpha.67/`：`gpu-hit-parity.json`、玻璃／水 `self-comparison.json`、`multi_light/self-comparison.json` 与 workload/raw suite/CSV/telemetry、两份图像捕获、`e2e-validation.json`、最终 `shader-validation.json`／manifest、accepted source/patch、运行与焦点记录及复现脚本。alpha.66 内部时钟证据另存 `outputs/alpha.66/`。
