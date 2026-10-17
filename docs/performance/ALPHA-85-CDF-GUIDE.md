# alpha.85：同帧 GPU CDF 索引，PT 改善 2.13%，未保留

2026-10-17。由 alpha.81 完整候选/FULL BSDF 回放选择的环境采样实验。目标是缩短原行/列 CDF 的串行搜索，保持采样分布和物理语义。未重测 Caustica。

## 改动及数值证据

候选在每帧原环境 map/cells/rows 生成后，由独立 GPU fragment pass 生成 256×129 的 RGBA32F lo/hi 索引，复制到同一 assets buffer 的保留范围。新增 528384B；无 CPU 图像下载、资源新 descriptor 或手工 barrier。渲染器的命令顺序保证使用当帧 CDF。A 跳过索引 pass，使用接受版 alpha.67 原 SPIR-V；B 包含生成/复制和新搜索。两边同样保留 alpha.73 RR/天空修复及扩展 assets 容量。

粗分桶向两侧留余量。运行时检查区间端点，不满足原 upper-bound 规则时扩展回退，最后仍执行原 `prefix<=value` 二分；零功率分支不变，zero row total 走原全区间搜索。原 RNG 次数、方向、PDF、候选数、光源和 FULL/EXACT 均不变。

386100 组 float32 索引/搜索契约检查覆盖尺寸 1/2/3/127/128/129/255/256/257、均匀/全零/单峰/稀疏/HDR、CDF prefix 与相邻浮点值，零不匹配。它是索引算术契约测试，不冒充真实 Slang GPU 求值。真实捕获的独立 GPU A/B 在完整候选、BSDF、RIS 输出与完整 RNG 上逐位一致。GPU 格式离线完整 RIS P50 A1/B/A2：0.077920/0.075008/0.078848ms，只用于进入游戏测试的筛选。

两个实际运行阶段通过 Vulkan 1.2 SPIR-V、camera/descriptor/byte-address ABI 检查。Primary 426216→436288B，Indirect 440460→450532B；索引检查带来的代码增长是真实代价，但未测得具体驱动寄存器/cache 影响。

## 一次游戏 A–B–A

multi_light、524×336、1 SPP、FULL/EXACT、OWEN、SER/OMM、WAVEFRONT、MONOLITHIC、LATE 和光照设置一致；scene signature `1417246207890208711`，40 sections、5453040 scene bytes。每段 8 秒，排除 counter、编译/加载和预热，三段 benchmark 均有效。独立开发包 SHA-256 `1a7ace884353e40135e3e0da97a05c7915887b2286efb3330ad1e7fe46098306`，仅改两个环境 assets class、隔离 benchmark plan、新 fragment 资源及两个候选阶段。所有原 native 库保持原字节。

GPU P50，单位 ms；改善相对两端 A 均值：

| 范围 | A1 | B | A2 | 改善 |
| --- | ---: | ---: | ---: | ---: |
| Primary + Guides | 1.675104 | 1.586976 | 1.686144 | 5.57% |
| Transport | 3.483328 | 3.446368 | 3.479360 | 1.00% |
| PT ready | 5.402336 | 5.290784 | 5.409280 | 2.13% |
| World，包含索引构建/复制 | 7.350272 | 7.271424 | 7.352608 | 1.09% |

PT P95 为 6.052339/5.905350/6.142592ms；World P95 为 8.139674/7.961395/8.205312ms。新索引 pass 的独立范围 P50/P95 约 0.020576/0.022528ms，已包含在候选 World 中，不再把这个嵌套时间加减到 PT 来预测收益。

三段时钟中位数和最小值均为 2520MHz，每段 7 个样本。温度中位数 82/84/85℃，没有持续降频；频率窗口边界约一个采样周期的不确定性保留。主要工作量均值最大差约 0.000561%，未改变光线或 RIS 预算。PT 的 A 漂移约 0.128%，因此观察到的 2.13% 改善超过本轮漂移，但仍低于事先要求的 3%。不能据此宣称毫无收益，也不能放宽门槛验收。

## 端到端和处置

Windows-MCP 确认创建时间匹配的本任务 PID38220 在前台。两个候选后测捕获 ENDPOINT/ENDPOINT_FAST 共 26 个有限浮点层，HDR/RR/transmission 非零；客户端正常退出 0。未进行完整转动恢复/噪声量化，这些健全性检查不能替代画质验收。

准备阶段一个 provenance 路径仍指向旧实验文件，最终 ready 校验因此停止；修复并逐项校验完整 class/native/Shader 身份后才有效启动。此前 guard 未通过的 driver 没有启动 Minecraft。一次 Windows-MCP 启动自动审批等待超时，允许的单次重试成功；没有用无效准备或重复客户端作为测试样本。

**处置：不进入生产，不安装，不构建完整发布包。** 本轮游戏测量有效，未因为结果低于门槛而补跑。保留候选和原始证据，生产仍为 alpha.73。历史 Caustica multi_light PT 2.214ms、目标上限 2.657ms；本候选 5.291ms 仍远未达标。

证据在工作区 `outputs/alpha.85`：module-validation、bracket-contract、shader-validation、self-comparison、workload-comparison、guide-pass-time、原套件/分段 CSV、GPU 遥测、焦点身份、normal exit 和捕获。该结果证明优化环境 CDF 能影响实际 PT，但不足以解释或消除全部差距。下一次改动需要继续扩大完整候选工作的实质收益，或先取得生产 wave/编译资源证据；不继续把减少 BSDF 静态指令当作确定加速。
