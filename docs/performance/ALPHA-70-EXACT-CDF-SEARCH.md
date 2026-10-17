# alpha.70：CDF 搜索依赖链实验，未验收

2026-10-15。生产仍为 alpha.67；本轮没有新的已验证加速，没有重测 Caustica，没有替换安装游戏。

## 差异 → 假设 → 干预

alpha.68 的环境采样调用延迟约 2.5µs，包含等待及子插桩，不能累加成 GPU 帧耗时。VL 在有 emitter 时仍分配 0.2 的环境提议概率，环境分布为 256×128 的行／列 CDF；Caustica 固定参考 commit `14770591086232e13a6b1d5320b0f2f8ef8731e3` 的 emitter RIS 没有这部分环境候选工作，天空通过 continuation miss 收集。不能通过删去 VL 环境采样宣称等价优化。

alpha.69 去掉环境候选第二次方向映射，但没有证实帧时间收益。本轮继续用隔离候选检验 CDF 搜索的串行读取链：原二分的下一次读取地址依赖本次 CDF 比较结果；候选一次预读 lower/middle/upper 三个 prefix，从这些独立读取结果完成原来的两步判断，最后单步区间另行处理。

保持原 `prefix <= value` 的 upper-bound 语义，首尾／重复 prefix／零质量区域、RNG 调用顺序、采样方向、PDF、FULL/EXACT 和所有 emitter 路径不变。只改 common/environment.slang；没有额外资源、Java/native、上传、barrier 或 descriptor。没有叠加 alpha.69。

对正常 128 行／256 列的搜索，源码推导的循环轮数合计由 15 降至 8，prefix 读取次数由 15 增至 22；这是一项延迟与额外读取的取舍，不是实测硬件事务或节省时间。候选 Indirect SPIR-V 的 `%6571/%6577/%6583` 三次 Load 位于同一块，且全部发生在 CDF 分支前，确认干预进入了编译结果。最终驱动是否重新调度、实际寄存器／缓存行为没有测得。Primary／Indirect 比接受版分别增加 2952B。

## 最少验证

实际 Slang CPU 路径验证：

- 环境相关 2097152 组：随机候选、角度边界、稀疏／HDR／零功率，并直接核对 CDF prefix 及相邻浮点值的搜索结果。覆盖宽 1/2/3/127/128/255/256/257、高 1/3/2/63/127/128/129/130；参考二分与候选两步搜索一致。
- 实际完整 RIS 524288 组：FULL 材质、金属、玻璃／水、各向异性、多散射、terminal/cache/volume、OWEN/SHIFT。输出与完整 32 位最终 RNG 均逐位相同，最大归一化误差 0。
- 两个实际运行阶段通过 SPIR-V／ABI 校验；不为未验收候选重建整套 110 stages。

准备期间一次误启动发生在隔离 benchmark plan 写入结束前，触发 JAR 文件锁；该启动另有 fixture 实体数量不符，在任何 benchmark 前失败。已通过 Windows-MCP 对创建时间核验后的本任务 PID 正常关闭，观察退出 0；所有记录保存在 invalid-launch，未纳入计时。之后创建全新隔离 state，完整准备结束、核验 JAR／plan／候选与基准哈希，再启动有效实验。没有用无效启动证明端到端成功。

有效实验只有一次同客户端 multi_light A–B–A，每段采样 8 秒，A 使用接受版 alpha.67 二进制别名；两边无内部 Shader clocks 或 parity capture，counter frames 排除。独立的 Windows-MCP 焦点检查为 true，相机起止一致。有效客户端正常退出 0。

## 实测结果与限制

以下为 GPU P50，单位 ms：

| 阶段 | A1 | B | A2 | 相对两端 A 均值下降 | A 自身漂移 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Transport | 3.474816 | 3.497792 | 3.520000 | -0.011% | 1.29% |
| PT ready | 5.385728 | 5.419264 | 5.446752 | -0.056% | 1.13% |
| World | 7.441440 | 7.477152 | 7.493536 | -0.129% | 0.70% |

Transport P95 为 3.889805／3.975808／4.021082ms，PT P95 为 5.972198／6.027885／6.035046ms；候选仍处于两端基线之间，未显示稳定改善。未通过事先的保留条件：同时快于两端 A，且 Transport／PT 下降超过 `max(2%, A 漂移)`。结果不支持新增优化，也不证明 CDF 访问无成本或候选必然回退。

三个 block 有效、GPU 范围覆盖完整；全部区间资源抽样的 geometry signature 为 1417246207890208711，40 sections、5453040 scene bytes、524×336、SPP 1、FULL/EXACT/LATE 等状态一致。主要 workload 最大均值差异 0.001259%。三个窗口分别有 7/7/8 个遥测样本，时钟中位数为 2520／2505／2467.5MHz，最低值 2520／2505／2385MHz，温度中位数 83／86／86°C。后段存在降频、边界约一个遥测周期的不确定性；不做精确硬件归因或微小百分比的外推。遥测最大间隔 1.61秒，没有 alpha.68 的数秒 CPU 停顿现象。

ENDPOINT 和 ENDPOINT_FAST 的独立捕获共 26 个 float layer 均有限，HDR、RR 输出、transmission 非零。该检查确认候选可以端到端运行，不代表逐像素画质等价；数值一致性的证据来自上述实际 Slang 模块比较。

## 决定与后续优先级

候选不进入生产。已核对接受版的 44 个 common/world 源文件、293 个生产 Java class、默认 110-stage bank 哈希均保持 alpha.67；运行 overlay 仅 benchmark plan 与声明的候选阶段不同。

alpha.69 与本轮分别改变角度映射和 CDF 依赖结构，均没有证实净收益。因此暂不继续堆叠环境微优化。它们不能证明整个环境子路径都便宜；但这些局部干预尚不足以支撑缩小与历史 Caustica PT 约 2.214ms 目标之间差距的承诺，当前纯版 PT 仍约 5.4ms。

下一优先级回到完整候选 proposal 的 emitter 分支及实际 alias→node→alias→record 数据依赖链。理由是 alpha.68 的 proposal 含等待延迟约 4.6µs，workload 约 2.28M candidates/frame；环境概率为 0.2，剩余 emitter 工作覆盖面更大。需要在编译后确认具体重复加载／依赖，再做单项布局或复用干预；64B 对 32B 不能直接当作两倍带宽瓶颈。保留精确 PMF、空间纹理 emission、金属及介质规则，不重复已失败的整体 BSDF 分流、CDF 预读或仅凭源码尺寸缩结构体。这里是下一项调查顺序，并非已经确认某条 alias Load 贡献了多少毫秒。

证据目录为工作区 `outputs/alpha.70/`：status、module-validation、shader-validation、compiler-search-evidence、self-comparison、workload-comparison、sampling-limitations、e2e-validation、原始 suite/CSV/telemetry、捕获／退出／焦点记录、候选源码和复现脚本；失败启动单独归档。生产继续 alpha.67，所有未提交的既有工作保持原状。
