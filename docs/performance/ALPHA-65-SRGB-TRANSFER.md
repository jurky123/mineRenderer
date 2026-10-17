# alpha.65：学习 Caustica 的光源数据准备方式，sRGB 查表未获加速

日期：2026-10-15。结论：候选未通过性能验收，已回退到 alpha.61；本轮没有保留生产优化。

## 差异与最小实验

Caustica 的 `shaders/pipelines/world/lighting.slang:lightRadiance` 解码 `Light.le` 中已打包的线性辐射值。VoxelLight 的 `shaders/rt/common/terrain_material.slang:terrainLightEmission` 则在候选采样位置插值 UV，读取材质/颜色图集并处理 cutout、染色与空间发光，再调用 `linearRgb`。两者做的工作不同，不能将 Caustica 的整个渲染速度归因于一个较小的 BSDF 接口。

本轮只验证其中一个精确可替换的部分：将 8 位颜色分量的 sRGB→线性转换提前计算为 256 个 float，通过现有 assets 缓冲区读取。新增表 1 KiB、头部偏移字段在字节 252；不增加 descriptor 或 GPU barrier。空间发光、UV、cutout、材质、金属、FULL/EXACT、采样预算和 RNG 均保持原语义。没有复制 Caustica 实现，也没有把空间发光替换为平均光源颜色。

假设是重复颜色转换占有可观成本。该实验仅替换转换算术；它没有提前准备整个候选的发光数据，也不能独立估计所有纹理读取成本。

## 模块与编译证据

- Java 主/客户端编译通过；全部 110 个 SPIR-V 及 ABI 检查通过，归档清单中的二进制哈希逐一核对。
- 实际 Java 表与实际 Slang CPU 算术路径比较：4096 个颜色组合、每通道覆盖全部 256 个值，包含 alpha 0/255，逐位相同，最大绝对误差 0。
- Terrain native binding fixture：32 个用例、1408 个分量，两版本对期望值最大归一化误差均为 `2.98023e-08`，版本间逐位相同。包含空间发光、cutout、EXACT 介质数据和受保护的 guide/延迟帧路径。这是模块验证，不能替代最终图像捕获。
- 实际 FULL QUERY SER Primary 二进制 404188→396980 字节，Indirect 418448→411240 字节；两者静态 `Pow` 均 59→5，`Sqrt` 均保持 163。静态操作数不是每帧执行次数，也不是硬件停顿测量。

## 一次客户端 A–B–A 结果

一个隔离 multi_light 客户端，预热后各采样 8 秒。A 使用已验收 alpha.61 的原始 SPIR-V，经已核对哈希的 GGX_INLINE 资源别名加载；B 使用查表 CLEAN。两边均使用带新表的资产缓冲区，A 不读取该表。因此本实验隔离的是 Shader 转换方式，未独立测量新增 Java 初始化成本。测试没有启动 Caustica。

| GPU 范围 | A1 P50 ms | B P50 ms | A2 P50 ms |
| --- | ---: | ---: | ---: |
| World | 12.991104 | 12.886016 | 12.604160 |
| Primary + Guides | 2.375520 | 2.363744 | 2.415488 |
| Transport | 8.260768 | 8.159968 | 7.828320 |
| PT ready for RR | 10.991040 | 10.889120 | 10.613088 |
| B1 | 4.466336 | 4.342016 | 4.122112 |
| B2 | 3.133760 | 3.112928 | 2.950912 |
| RR | 1.175392 | 1.177856 | 1.181760 |

三个区间均通过 benchmark 有效性检查，剔除计数帧后的 GPU 时间戳覆盖率超过 95%。分辨率 524×336、SPP 1、FULL/EXACT/LATE、terrain signature `1417246207890208711`、40 sections、5453040 scene bytes 一致，区间资源抽样一致。RIS/visibility/connection/hit/queue 工作量均值相对两端 A 均值的最大变化为 0.001944%。估计采样窗口内全部 GPU 时钟样本均为 2520 MHz；温度中位数为 83/84/86°C。P95、原始 CSV 和计数数据保存在机器可读报告中。World、PT、Transport 是包含关系，不能相加。

Transport 相对两端 A 均值为 1.435% 更慢，PT 为 0.806% 更慢；但 A 自身 Transport 漂移为 5.376%、PT 为 3.499%，不能把这些小差异解释为稳定回退。Primary 的 1.326% 降低也没有超过其 A 漂移 1.668%。结论是未证明净加速，不是证明查表必然更慢。没有重复客户端或扩展测试。

## 为什么减少 Pow 没有换来可验收的加速

已经证实的是转换替换生效、数值一致、执行总时间没有可验收收益。查表增加数据读取和依赖，算术减少未必缩短 GPU 关键路径；原有几何/图集读取、BSDF 和 visibility 工作仍存在。这些是解释假设，尚无硬件 counter 可以区分具体贡献。此前 Nsight 的 `ERR_NVGPUCTRPERM` 限制仍然存在，不能声称已测到 cache miss、寄存器溢出或 SFU 瓶颈。

该结果降低了继续单独微调颜色转换的优先级。后续应分别检查：是否可对确实均匀的光源预存完整线性发光值并保留非均匀 fallback；以及 EXACT visibility 的 TraceRay/closest-hit 往返是否可以在保持 ordered-boundary/介质语义下缩短。两者目前都只是下一步调查对象，尚无独立成本或加速证明。Caustica 的平均透明度近似不能作为 VoxelLight EXACT 的等价替代。

## 端到端限制与回退

三个计时区间和 suite ZIP 已完整导出，随后切回 CLEAN 的资源清理阶段，客户端渲染线程停在原生 `vkDestroyPipeline`。相隔约一分钟的两份线程栈均停在 `VulkanRtPipeline.destroy → DestructionQueue.rotate → VulkanCommandEncoder.submit`，期间 GPU 基本空闲。定位到了停滞位置，驱动/资源生命周期根因仍未确定；不能归因于查表本身。

尚未产生 ENDPOINT/ENDPOINT_FAST 捕获，也没有末尾相机快照，因此不能声称最终图像验证通过或正常退出。通过 Windows-MCP 验证所属隔离 PID 6216 的创建时间后强制结束；观察到退出码 `4294967295`，不是正常退出 0。性能验收失败独立于这个退出问题成立。

候选 Shader、Java、builder、编译 class 和补丁已归档。生产的 terrain Shader、资产 Java/class 和 builder 从实验前快照恢复，仅删除本轮新建的 `RtSrgb.java/.class`；保留此前已验收的未提交改动。44 个 common/world 源码哈希全部与 alpha.61 清单一致，原默认 Shader bank 未被覆盖，安装游戏未改动。

复现与原始证据位于工作区 `outputs/alpha.65/`：`self-comparison.json`、`workload-comparison.json`、`module-validation.json`、`compiler-comparison.json`、`shader-validation.json`、`candidate-manifest.json`、`artifact-difference.json`、`restoration.json`、`status.json`、原 suite/CSV/telemetry、两份 thread dump、退出/启动日志、`candidate.patch`、`rejected-source/` 及实验脚本。当前变更尚未提交。
