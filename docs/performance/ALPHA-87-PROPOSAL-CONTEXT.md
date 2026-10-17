# alpha.87：完整候选上下文复用未通过离线筛选

2026-10-17。生产基线为已验收的 alpha.86；源码身份为 `1a246dbde3c849f4f565bbaba8eb0deff8374013`。本轮没有启动 Minecraft 或 Caustica，没有改动生产源、默认 shader bank 或已安装模组。alpha.87 仅为实验编号，没有发布包。

## 已完成的安装与源码推送

用户授权后，alpha.86 完整包已安装至 `E:/MC/.minecraft/versions/26.2-Fabric 0.19.5/mods/voxellight-client-26.2-0.39.0-alpha.86.jar`。安装前后 SHA-256 均为 `d0fbfaa23935329316ee51a58040f1cce19d1c0716318cc670b9d0e03c609986`。alpha.73 已备份并在 mods 内改为 `.jar.disabled`；其他模组及设置保持一致。回执在 `outputs/client-alpha86/installation.json` 和 `deployment.json`。源码、工具及报告已提交并推送至 `origin/main` 的上述提交；安装行为没有产生新的性能或启动验证结论。

## 差异、假设和最小实验

固定 Caustica 提交 `14770591086232e13a6b1d5320b0f2f8ef8731e3` 的 `shaders/pipelines/world/lighting.slang` 在 `risInitial` 循环前执行 `findLightGridCell`。VL 虽已提前计算 cell，原编译结果仍在循环内读取 runtime header 中的 groups、record/alias/node 基址、slots 和 PMF index；这是直接的编译后证据，可以验证是否值得复用整个只读 proposal 上下文。

候选仅增加 `RuntimeProposalContext`，将这些元数据移至同一次 RIS 的循环前。local/global 分支、原 float32 PMF、alias threshold、RNG 调用顺序、候选预算、几何、精确空间 emission、FULL BSDF、MIS 和 reservoir 更新顺序均不变。其他兼容调用通过包装函数构建上下文。没有新增资源表或上传，避免再次重复 alpha.74 section span 和 alpha.83 96B UV 热记录的失败实验。

完整 RIS 的 SPIR-V 中，runtime header 的 **12 处循环内静态 Load 位置变为 0**；它们受分支控制，不能称为每候选必然执行 12 次。相关 header Load 的循环外位置为 6→15，SPIR-V 102076→101796B。这证实改动实际发生，不能证明驱动最终寄存器、访存或指令数的变化。没有硬件计数权限，未宣称定位了 spill、cache miss 或 occupancy。

## 完整块离线 A–B–A

使用 alpha.79 实际 multi_light 捕获、alpha.81 候选表和 alpha.86 同帧 CDF guide。383 个实际进入 RIS 的父输入，2217 个候选，按既有 dense 表重复至 16384 项；这是热缓存的离线排序，不能当作实际画面执行时间。每阶段 10000 次预热、10000 次测量，输出被读回消费。

| 完整工作块，P50 ms | A1 | B | A2 | 处置 |
| --- | ---: | ---: | ---: | --- |
| 完整候选（含 receiver gate / emission） | 0.021504 | 0.021504 | 0.021504 | 无改善 |
| 完整 RIS（FULL BSDF / MIS / reservoir） | 0.069792 | 0.071680 | 0.069888 | **慢 2.63%，拒绝** |
| 完整候选准备循环 | 0.038912 | 0.036864 | 0.038912 | 快 5.26%，未转化为完整 RIS 收益 |

完整 RIS P95 为 0.072704/0.074752/0.072704ms。这三个工作块的每个 A/B/A 实际 submit 窗口 GPU 时钟中位数均为 2535MHz；完整候选短窗口各只有 1 个样本，仅作辅助，主要拒绝依据是完整 RIS 的 4–5 个窗口内样本。所有保存的对应输出字段、候选 ID、bucket 和 RNG 逐位一致。没有将“准备循环加速”冒充实际 PT 加速，也没有因此进入游戏。

额外 emitter-only 短阶段含 2370MHz 的启动 boost 窗口，A2 中位数 2452.5MHz，不满足跨窗口 3% 门槛，排除该阶段的性能结论。最初执行脚本的全阶段频率断言因此失败，原始数据保留；上述完整 RIS 等阶段各自频率检查通过，足以拒绝更慢的候选，不补跑无收益实验。阶段资格单独记入 `status.json`，没有将整次执行伪装为全通过。

结果关闭了“循环内 invariant header Load 是主要可兑现热点”的具体假设。上下文延长活跃值、编译器/驱动优化及完整循环调度都有可能抵消准备阶段收益，但目前没有证据区分这些原因，不能用一个未经测量的寄存器解释替代根因调查。候选只留在隔离实验目录，未推广至生产。

## 精确发光路径的进一步定位

Caustica 的 `lightRadiance` 从记录解码平均打包颜色；VL 的 `terrainLightEmission` 必须按实际 barycentric UV 读取材质、albedo、tint 和 cutout，语义不同。继续使用完整 RIS 中的冻结替换，不改变 BSDF、PDF、receiver gate 或 reservoir。单独缓存整个 area emission 的首尾对照：

| 诊断替换 | P50 ms | P95 ms |
| --- | ---: | ---: |
| 原版 A1 | 0.069952 | 0.072704 |
| 缓存精确 area emission 完整结果 | 0.064832 | 0.067584 |
| 原版 A2 | 0.069984 | 0.072704 |

输出和 RNG 逐位一致，实际计时窗口全部 2535MHz。影响约 7.34%，只是带缓存替换的敏感性结果，不能视为可兑现帧收益、严格上界或与 alias 诊断差值相加。

再用 GPU 从同份实际资源捕获精确 emission 的中间输入；1577 个通过 receiver gate 的 area 输入，调用生产 `terrainLightEmission` 得到的 RGB 与原表逐位一致。冻结 tint 线性 RGB、color 线性 RGB、UV 和两个 atlas texel，再分别替换完整循环内对应计算：

| 诊断替换 | 完整 RIS P50 ms | P95 ms |
| --- | ---: | ---: |
| 原版首 | 0.069728 | 0.072704 |
| 仅缓存 tint 线性 RGB | 0.070656 | 0.073728 |
| 仅缓存 color 线性 RGB | 0.070656 | 0.073728 |
| 缓存 UV 及 material/albedo texel | 0.069824 | 0.072704 |
| 原版尾 | 0.069808 | 0.072704 |

五个计时窗口全部 2535MHz，各有 4–5 个时钟样本，16 个 RIS 输出字和 RNG 全部逐位一致。UV/texel 差值低于两端 A 的漂移；缓存线性 RGB 反而稍慢。插入缓存本身有访问代价，这些结果不能证明原算术或纹理读取免费，也不支持为了消掉单次 pow/Load 就新增生产 cache/atlas。中间输入打包脚本第一次将 16 行误作 16 字进行形状断言，修正读取切片后复用原 GPU 捕获并完成检查；未增加游戏启动。

## 下一步与限制

| 差异/问题 | 支持证据 | 尚未证明 | 下一项有意义的实验 |
| --- | --- | --- | --- |
| 完整选择依赖链仍有影响 | alpha.86 的冻结 alias 选择替换影响约 7.25% | 哪一级读取的硬件延迟占主导 | 从实际 alias/record 工作块考察跨候选访问重叠；先验证生产函数、RNG 和全部输出，再测完整 RIS，不能仅凭 Load 数推广 |
| 完整 emission 替换有影响，单项输入替换无改善 | 本轮约 7.34% 对照及上述中间输入表 | 控制流、访问依赖、活跃值及替换开销各占多少 | 若实验完整路径重排，必须同时保留 receiver 早退、空间 emission 与 cutout；拒绝平均颜色替换，避免再造只缓存 tint/UV 的方案 |
| 准备阶段收益不能外推 | context 候选准备循环快 5.26%，完整 RIS 慢 2.63% | 准确的驱动调度/寄存器原因 | 后续每项候选仍以完整 RIS 为离线筛选门槛，模块收益不再单独触发游戏测试 |

冻结表丢失原像素/波前相邻关系，并且重复同一小组输入；本轮不能用它证明整帧访存瓶颈，也不能据此排除冷数据、复杂材质或动态场景中的成本。采样数、FULL 材质、金属和 EXACT 介质语义未降低。生产版本继续 alpha.86，历史 multi_light PT 5.202816ms，仍高于 Caustica 历史 120% 预算 2.6568ms，没有新增帧收益或达标声明。

精简证据随报告保存在 [alpha87/](alpha87/)：隔离候选 patch、编译后读取位置、原始阶段 P50/P95/时钟摘要、发光诊断和拒绝状态。更完整证据位于工作区 `outputs/alpha.87/{identity,shader-identities,compiler-evidence,summary,status}.json`、`probe/`、`emission/` 和 `scripts/`。完整源/二进制在 `work/mineRenderer/build/alpha87-proposal-context/`。脚本为 `work/alpha87-{build,run,emission,evidence,finalize}.py`，调用仓库通用回放工具；依赖既有实际捕获，不从源码虚构输入。此轮所有 Vulkan 子进程正常完成，未重测 Caustica，**0 次游戏测试**。
