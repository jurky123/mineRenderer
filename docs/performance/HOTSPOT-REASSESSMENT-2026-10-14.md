# 2026-10-14：热点复核与 Caustica 源码对照

本次只复核既有数据与源码，不启动游戏、不重测 Caustica、不修改生产算法。采用 alpha.58 多光源和 alpha.59 的已验收 alpha.58 玻璃／水基准；alpha.59、alpha.60 失败候选不作为当前版本。目标是确定下一项可验证改动，不把源码差异直接写成已经测出的耗时归因。

## 1. 当前优先级

GPU P50，单位 ms。Caustica 为 alpha.50 三轮历史中位数；当前 VL 为短时自身观测。太阳、材质、运行日期和 pass 职责不同，差值用于排优先级，不是同工作量成本比。

| 场景／范围 | 当前已验收 VL | 历史 Caustica | 参考差值 |
| --- | ---: | ---: | ---: |
| 多光源 Transport | 8.249984 | 1.924 | 6.325984 |
| 多光源 Primary + Guides | 2.407520 | 0.144 | 2.263520 |
| 多光源 PT ready | 10.963136 | 2.214 | 8.749136 |
| 多光源 RR | 1.179840 | 1.180 | -0.000160 |
| 玻璃／水 Transport | 6.111216 | 0.791 | 5.320216 |
| 玻璃／水 Primary + Guides | 1.991584 | 0.176 | 1.815584 |
| 玻璃／水 PT ready | 8.406448 | 0.969 | 7.437448 |
| 玻璃／水 RR | 1.182912 | 1.167 | 0.015912 |

B1/B2：多光源 4.457840/3.145616；玻璃／水 2.187648/2.527904。两者中位数之和／Transport 中位数约为 92.2%／77.2%。这是指示性占比，不是逐帧精确分解，不能把父子 timestamp 相加。优先处理首两层；玻璃／水的尾部仍有一定成本，不能说所有场景的尾部都可以忽略。

Caustica Primary 不做 NEE/RIS/BSDF 采样，首层照明在 Indirect；VL Primary 已经执行这些工作。所以不能将 2.408 对 0.144 的比值直接称为纯 guide 开销。总 PT 是更合适的跨 Renderer 目标。RR、已测 Scene 和合成阶段的量级不支持先把它们当作主要差距来源。

## 2. 哪些旧解释应停止使用

- alpha.50 的 opaque 预测后再查透明几何已经被移除。当前 EXACT 混合几何走同一个有序命中循环，不能再次提出删除固定双遍历。
- alpha.52 已经按命中准备和复用 PreparedBsdf，不是每个 RIS 候选都重复八点 Fresnel／GGX 平均能量积分。
- alpha.57 解码前 Indirect SER 已验收；alpha.58 普通 Primary 已验收。不能把已解决的旧 SER 顺序重新当作待修主因。
- alpha.54 平均能量预积分、alpha.56 简化 Caustica RIS 目标移植、alpha.59 冷介质后移、alpha.60 不透光阴影法线延后都没有证明加速，已撤回。
- alpha.50 radiance tracing 数量是 Caustica 的 0.63–0.78 倍。证据不支持优先降低 radiance bounce/SPP。大多数场景 RIS 候选数也并不比 Caustica 多。

## 3. 源码对照：差异、证据与改动入口

### A. RIS 光源查找／PMF：下一项优先实验

VL：`shaders/rt/common/light_runtime.slang:27` runtimeSectionPdf、:41 runtimeCandidateGeometry、:115 selectDirectPrepared；生产表由 `src/main/java/com/voxellight/rt/RtLightRuntime.java` 构造。

Caustica：`shaders/pipelines/world/lighting.slang:183` proposalPdf、:199 selectLightGridSpanLight、:237 risInitial；其 LightAlias 为 8B、LightGridSpan 为 16B。2026-10-15 的 alpha.68 重新核对相同参考 commit 的 Java writer 与 Shader ABI，Light 热记录实际为 **32B**，修正此处旧记的 48B；此布局差异不是 GPU 带宽测量。

VL 从全局分布选到 section 后，需要扫描 cell 的最多 16 个局部 alias 条目，查到局部 PMF 再计算 `.2*global+.8*local`。局部提议分支已经直接拿到 PMF，并不执行该扫描。Caustica 根据 cell.invWeightSum、section 距离和光源功率直接重建 PDF，没有这个候选内列表扫描；其权重和支持域与 VL 不同，不能直接复制公式。

alpha.59 基准 140 个计数帧均值：RIS 候选约 1109594/帧，表面可见性连接约 266542/帧，阴影遍历约 366635/帧。高频候选使依赖读取值得优先检查，但这些计数不是函数耗时。Caustica 源码关于“候选依赖加载可省 5.9ms”的注释属于它自己的另一场实验，不能作为本机 VL 的测量结果。

**最小实现**：在 CPU 发布 alias 时附加精确的 cell→section 局部 PMF 查询表；小／中规模采用有容量上限的稠密索引，大规模保留现有扫描作为回退。存储已有 float32 PMF，不重算权重；保持选光 RNG、Owen 序列、候选数量、局部／全局混合概率和空间发光采样。runtimeSectionPdf 与 emitter-hit MIS 使用同一查询，保持 support 和 PDF 一致。

**验证与收益边界**：逐 cell/section 对照原扫描的 PMF，覆盖未入局部表、无光源、越界和容量回退；固定 seed 对照候选 ID、PDF、reservoir 和 emitter-hit MIS。再做一次既有多光源场景自身对比及端到端确认。同步看 CPU 表构建和 GPU 上传，避免用额外每帧大表抵消 shader 节约。这项只影响部分提议，不承诺独自解决 6ms Transport 差距。只有实际 B1/B2 与 PT 改善才验收。

### B. FULL BSDF：更大的算术差异，但独立耗时尚未知

VL：`shaders/rt/common/bsdf.slang:61` prepareSurfaceBsdf、:90 evalPreparedLocal；`light_runtime.slang:107` directIntegrand。

Caustica：`lighting.slang:44` evalSampleContrib；`indirect.rgen.slang` 的普通表面使用 Lambert＋各向同性 GGX／Schlick，并与其自身 continuation 权重配套。

VL 保留各向异性 D/Smith、精确介质 Fresnel、粗糙漫反射、多重散射和 coat；直接光候选还要得到 BSDF PDF 做 MIS。它们确实比 Caustica 普通材质公式包含更多操作，但尚不能把剩余 GPU 毫秒全部归于 BSDF。prepared 参数已复用；VL 还保存选中候选的 value，而 Caustica shadeReservoir 会重新评估一次，因此并不是所有地方 VL 都做得更多。

**第二项实验（alpha.63 已验证，不保留）**：普通无 coat 的 ROUGH_DIFFUSE 已尝试在 RIS 循环前分类，使用轻量上下文和编译期专用循环，完整公式／PDF／MIS保持一致，其他材质／volume／cache 回退原路径。实际 Slang CPU 的 524288 次 RIS 输出逐位一致，110 个生产 Shader／ABI 校验通过。但同客户端 A–B–A 中，候选 Transport 为 8.815104ms，两个旧实现窗口分别为 8.348416／7.770160ms；候选 PT 总耗时也高于两者，未验收。几何签名／场景字节完全一致，GPU 中位频率 2505／2520／2520MHz，主要工作量差异低于 0.001%。已撤回候选，保持 alpha.61。主 FULL Shader 体积增加约 9.02%；寄存器、spill、occupancy 和指令缓存的实际影响仍未知。不能继续把源码结构体大小等同于 GPU 拷贝开销，也不应扩展这条未加速的分流方案。完整数据见 PERFORMANCE-PARITY.md 的 alpha.63 和 workspace outputs/alpha.63。

不再次使用 alpha.56 的 Lambert＋Schlick surrogate 替换 FULL RIS 目标；也不把用户允许的 1e-2 数值误差解释成可以随意删掉整个物理项。将来若测试近似，只能明确限定误差定义、材质和角度域，并单独验收。

### C. EXACT 可见性：结构差异最大，需保留光学语义

VL：`material_transport.slang:213` visibility／:129 traceSurface、`terrain_material.slang:113` terrainExactShadow。最近已验收版本仍是最多 24 边界的有序 closest-surface 循环，进行 RGB 消光、Fresnel 和八层介质身份处理。

Caustica：`trace.slang:100` visibility 单次 TraceRay，ACCEPT_FIRST_HIT + SKIP_CLOSEST_HIT；`any_hit.rahit.slang:112/132` 水染色、预计算平均透明颜色累乘。any-hit 无序，不提供与 VL 相同的嵌套介质处理。

当前遍历／表面连接总量比约 1.3755，但包含聚合计数范围，不能将其当成逐透明边界的精确比值；阴影数量也不能单独解释 5.32ms 差距。alpha.60 已否定“延后几次几何法线读取就能有效提速”这项具体改法。

**第三项实验**：在支持 QUERY 的版本中，尝试用 inline RayQuery 得到每轮相同最近命中，仍按原有距离顺序处理介质和 Fresnel；仅改变 traversal／closest-hit 交接方式，保留 TraceRay 回退。必须验证 cutout、OMM、instance/primitive 映射、火把自壳、多层介质和相交边界；不能假设 Query 必然更快。若再研究单次遍历收集／排序，数组和截断成本需要单独证明，不能直接替换成 Caustica 的平均染色。

### D. 调度／状态：暂不作为第一项

Caustica 两层 medium 状态、打包 surface payload 和较大的 Indirect 内循环，与 VL 八层介质、wavefront dispatch 不同。但 VL 已有 48/64B 热状态和按活动前缀访问冷状态。alpha.59 的后移加载没有加速；历史和当前 B1/B2 时间也不支持先集中删除几个很短的尾部 dispatch。不缩减嵌套介质语义，不仅凭结构大小宣称 spill 已确诊。

## 4. 实施次序和验收

1. 精确 PMF 查询去扫描：alpha.61 已验收，继续保留。
2. FULL 普通材质类型专用上下文：alpha.63 同客户端验证未加速，已撤回，不继续扩展分流。
3. EXACT inline Query 最近命中：单独在玻璃／水场景验证，保留所有光学处理。

每项只运行受影响模块验证、Shader/ABI 检查和一次对应场景自身 GPU 对比／端到端检查；优先复用已有基准，不重跑五场景 Caustica。轻微百分比波动不算明确加速；模块时间下降但总 PT 增加不算通过。没有收益就撤回，保存证据。若前两项均无收益，应追加一次窄范围成本隔离，再调整优先级，不能继续凭高计数推测热点。

数据来源：alpha.50 正式剖析、PERFORMANCE-PARITY.md 的 alpha.52–60、workspace outputs/alpha.58、alpha.59、alpha.60。离线整理数据：outputs/hotspot-reassessment/accepted-stage-ranking.json。本次确认的是模块热点和源码差异，尚未得到 RIS／BSDF／可见性各自独立的 GPU 毫秒拆分；所有建议都需要候选实测后验收。


## 5. 代码层面进一步确认：精确 PMF 查询（本轮完成）

完整调用链为 Java RtLightRuntime.build → VulkanRtMaterialAssets.prepare 发布 assets → runtimeCandidateGeometry 的全局分支／runtimeHitPdf → runtimeSectionPdf。当前 section ID 是连续的 0..g-1；cell ID 0 表示格外纯全局，1..405 对应表内 cell。每个局部 alias 表包含唯一 section ID，PMF 写在每个原始列的 +12 字节，不随 alias alternate 改变。因而可以从这些已有列建立 `[cell-1][section]` 的精确局部 PMF 索引，未入局部集合的项保持 0。

重要范围：局部提议已经由 runtimeAliasPmf 返回选中 section 原始列的 PMF，不需要扫描。扫描发生在 cell 内全局提议分支，以及发光面命中竞争 PDF；cell=0 提前返回 global，也不扫描。应只改这两个查询消费者，不重写整个 RIS 或增加候选缓存。别把最多 16 次扫描乘到每一个候选上。

实际生产表并非每帧重建：VulkanRtMaterialAssets 在 emitterGeneration 改变、相机跨 16 格 cell，或 proposalRevision 改变且距上次重建至少 64 帧时才重新 build/upload。它目前在渲染调用线程同步构建；Caustica RtLightGridManager 将构建和 staging/copy 放到 worker，最后发布 GPU-complete generation。这是另一项 CPU 生命周期差异，不等于静态 Transport 的 GPU 耗时来源。新增查询表应沿用原有更新条件和同一代发布，第一项不要同时引入异步改造。

Caustica proposalPdf 能直接从功率、cell.invWeightSum 和距 cell 的 section 坐标计算局部 PDF，是因为它的局部支持是 +/-2 section 邻域，权重有可重建形式，且 section 光源功率在层级 PDF 中抵消。VL 的局部集合是最近最多 16 个 section，带 adaptive bucket 权重，并存储已舍入 float32 PMF；两者支持和数值并不一样。可借鉴“采样后直接得到 PDF、避免搜索”，不能把它的距离公式或固定 local/global 候选安排原样拷进 VL。M=8 时 Caustica 6 local/2 global 的分层提议，也不是 VL 当前 0.8/0.2 随机 mixture 的相同随机轨迹。

### 离线证明和边界

使用当前 RtLightRuntime.java 重新编译到隔离目录；生产源文件不改动。合成 0、1、16、17、64、160、512 个发光 section，每个混合 area/point 源，三组相机位置含负坐标和跨 section，使用非均匀 adaptive factors。由实际构建器的 alias 输出构造候选索引，逐一对照原扫描：935550 个局部和混合 PMF 项 float32 原始位值一致；并检查每个 cell 的 section 唯一性。没有使用 1e-2 容差，也没有测量 CPU 循环并将其冒充 GPU 加速。

单独调用实际 runtimeSectionPdf 的 compute fixture 经 Slang -O2 编译并通过 spirv-val，SPIR-V 中保留一处动态 OpLoopMerge 和循环内 assets 读取。这证明隔离函数的扫描在此编译结果中存在；不能据此宣称完整驱动机器码的延迟、缓存命中或寄存器成本已知。

建议附加表只存 **局部 PMF**，GPU 保留原 `.2*global+.8*local` 算术次序，避免 Java 预合并系数引入不同舍入／FMA。cell=0、无光源和旧表／容量不足路径保持原行为。使用 runtime header 的未使用 +60 字节放附加表偏移（0 为原扫描），不得与 Light record 自身 +60 的 section ID 混淆；维持现有 64B header 和已有表偏移。实现时须同步更新 writer/reader/fixture。

附加内存为 `405*g*4` 字节；设上限 256KiB，同时要求原表加新表不超过现有 2MiB runtime buffer。g=160 新增 259200B，可进入该上限；g=512 新增 829440B，应走原扫描，不能扩大固定 assets 容量。创建零填充索引仍有成本，最多 256KiB 的构建／上传和缓存压力都需要在后续实测检查。不能预先承诺净收益。

下一项实现已具备代码依据：附加索引只改变查找方法，alias 选择、seed、Owen、候选数、global support、材质和光学处理均不变。模块验收还需实际 shader／reservoir／emitter-hit MIS 对照；当前 Java 位值证明和独立 SPIR-V 仅是实施前审计。随后只做一次对应场景自身 GPU 对比和端到端确认。

证据：workspace `outputs/hotspot-reassessment/pmf-code-audit/`，含 runner、实际 helper fixture、SPIR-V/反汇编、结果和源码哈希。本轮没有修改生产算法、启动游戏或宣称加速。


## alpha.64 归因补充（2026-10-15）

alpha.63 的结论不能停留在“变慢就撤回”。本轮保留同一精确专用 BSDF，只改共享候选循环，单客户端 A–B–C–A 中 Transport 为 8.325280／8.867440／7.893248／7.770064ms，所有已采样 GPU 频率均为2520MHz，几何相同，主要工作量变化低于0.002%。共享循环消除了大部分双循环退化，但没有稳定超过旧版，因此只作为归因证据，不作为新生产优化。

原版 SPIR-V 已没有 Material/PreparedBsdf 整体结构；不能把源码大对象传值当作每候选整块内存复制。双循环增加静态代码和控制流：Indirect 指令数24654→26750，共享后25217，静态循环54→59→54。证据指向这组循环分裂／代码生成改动的代价，而不是证明物理公式实现错误。补充26万组不同视角／旋转框架／掠射／各向异性 BSDF 对照也逐位一致。具体指令缓存、寄存器、spill和occupancy仍未测到，不能把上述归因扩大为某个硬件 stall 已确诊。

Caustica 更轻的入口还包含更少的 BSDF 数学和打包发光数据读取，VL则保留精确FULL公式、PDF/MIS和按采样点的纹理发光。不能把“Caustica整体更快”直接解释成“小参数结构本身带来加速”。完整闭环见 ALPHA-64-RIS-REGRESSION-CAUSE.md；生产保持alpha.61，没有新增验收。下一项实验应明确到具体运算／依赖读取，不再凭大结构体或多分支的表面形态判断热点。

## alpha.66–67 后续实测（2026-10-15）

稀疏 GPU 内部时钟测得旧版 shadow 最近命中 TraceRay 约44–45µs/调用，而普通 radiance trace 约5.3µs、BSDF eval约2.9–3.0µs；这些是含等待延迟，不是可累加的帧占比。对照 Caustica skip-closest-hit 的阴影路径后，只将 VL shadow 最近命中改为 inline Query，保留有序介质、Fresnel、cutout和全部FULL公式。40960条玻璃/水实际GPU命中对照零不匹配，稳定多光源纯A–B–A中Transport约7.574/3.532/7.206ms、PT约10.378/5.511/10.065ms，收益明显大于基准漂移。alpha.67已保留，替代上文“下一项Query实验”的待办状态。

玻璃/水速度观察受动态sceneBytes变化限制，不作为严格性能验收；两份捕获共26层有限，两个验收客户端均正常退出0。最终110个生产stage/ABI通过，活跃stage与实测候选字节一致。硬件spill/register归因仍未测得，Caustica总PT目标也尚未达到。下一步应在alpha.67上重新检查剩余RIS/BSDF/发光成本。完整数据与限制见 ALPHA-66-SCOPED-GPU-LATENCY.md、ALPHA-67-EXACT-SHADOW-QUERY.md 和工作区 outputs/alpha.66、outputs/alpha.67。

## alpha.68 剩余成本定位（2026-10-15）

已用 alpha.67 纯版—内部时钟诊断—纯版的单客户端重新采样。正常纯版 PT约5.48ms、Transport约3.52ms；逐帧B1+B2占Transport约94.3–94.4%，独立Guides约0.064ms。Query最近命中约2.8µs，旧交接热点已退下；候选proposal约4.6µs、BSDF eval约3.2µs、emission约1.9µs，均为含等待的调用延迟，不能乘频率累加成帧时间。插桩对正常PT带来约5.24%开销，A2另有约4秒CPU停顿与遥测缺口，限制已保留。

下一项最小实验已明确到编译后仍存在的操作：环境候选PDF与radiance对同一方向分别执行atan2/acos映射。可以缓存第一次实际方向映射的texel index，保留原CDF/RNG/PDF与零功率回退；不要直接使用初选CDF cell来绕过边界舍入语义。环境采样与Caustica仅emitter RIS／sky continuation的工作边界不同，不能直接删除。完整闭环和32B Light ABI更正见 ALPHA-68-REMAINING-COSTS.md。生产保持alpha.67，本轮没有算法改动。

## alpha.69–70 环境子路径干预结果（2026-10-15）

上述 texel 复用已作为 alpha.69 实施：实际 Slang 环境与完整 RIS 输出／完整 RNG 逐位一致；短 A–B–A 的 Transport 仅下降0.81%、PT下降0.53%，与基线漂移接近，未通过验收。随后 alpha.70 单独检验 CDF 搜索链，将两次原二分决策改为三个独立 prefix 预读；2097152组环境和524288组完整RIS同样逐位一致，但 Transport 3.475/3.498/3.520ms、PT5.386/5.419/5.447ms，候选在两端基线之间，后段另有GPU降频。两项都未采纳，没有叠加实验，也没有重跑Caustica。各有效客户端的两份Guide/HDR捕获均26层有限并正常退出0。

生产44源文件、293class、默认110stage仍为alpha.67。暂不继续堆叠环境微优化，下一调查顺序为更大覆盖面的emitter候选分支和具体alias→node→alias→record依赖；需要编译后证据与单项干预，不把64B/32B直接换算成带宽差。完整记录分别见 ALPHA-69-ENVIRONMENT-CELL-REUSE.md、ALPHA-70-EXACT-CDF-SEARCH.md，以及工作区outputs/alpha.69、outputs/alpha.70。

## alpha.71–72 发光物叶级读取链（2026-10-15）

已从实际SPIR-V确认，叶级选中alternate slot后仍需追到chosen column读取light ID；Caustica依靠section内连续记录直接计算ID。VL为triangle命中PDF保持全局排序，因此用叶级冗余PMF word存入精确alternate ID，不改表长和PMF／RNG。alpha.71将两个ID同时加载后选择，编译后缩短依赖但self路径增加一次无条件word Load；短对照Transport3.488/3.496/3.473ms、PT5.417/5.429/5.399ms，全部遥测2520MHz，未加速。alpha.72改成先选择+8/+12地址再只读一个ID，两个分支均两个word Load，Shader各缩小384B；最后一次短对照Transport3.499/3.519/3.509ms、PT5.442/5.452/5.466ms，同样未证实净收益。

两轮模块实际执行的alias边界／完整RIS均逐位一致（24896／524288组、完整RNG），alpha.72复用已有控制快照；有效客户端均有26层有限捕获和正常退出0。两候选均隔离、不采纳，44源／293class／110默认stage仍为alpha.67。不把依赖缩短等同于已确认的主要硬件瓶颈，也不再追加此叶级微调。Caustica式section span直接携带self/alternate first/count、绕过独立node是另一项尚未测量的结构差异，必须保留VL精确mixture PMF与adaptive bucket语义后另行评估。详情见 ALPHA-71-LEAF-ALIAS-CHAIN.md、ALPHA-72-LEAF-SELECTED-WORD.md。


## alpha.74–75：两项热点干预均未通过性能验收（2026-10-15）

已验证Caustica式section span：精确self/alternate PMF和leaf range/bucket绕过node；230808组选择和524288组完整RIS/RNG逐位一致。一次A–B–A的Transport3.471488/3.481120/3.499904ms、PT5.407360/5.447056/5.424848ms，不采纳。新增36B span和最长256KiB footprint未获得净收益，不再追加alias布局小改。

随后只对有严格正PDF的无coat普通粗糙漫反射太阳连接延后完整BSDF，学习Caustica遮挡后才求BRDF的顺序。262144组实际连接循环的辐射、AOV、RNG、shadow次数逐位一致，但阶段代码各增加21476B；一次A–B–A的Transport3.441664/3.476608/3.456128ms、PT5.348192/5.374432/5.379872ms，仍不采纳。未测得硬件cache/register/stall，不能把未加速唯一归因于代码尺寸。

两客户端各有26层有限捕获和正常退出0，几何/资源匹配、主要射线工作量差低于0.001%，后段轻微降频已记录。生产仍保留alpha.73画质修复和alpha.67着色器，两个候选均隔离；没有重跑Caustica或替换安装游戏。详见docs/performance/ALPHA-74-SECTION-SPAN.md、ALPHA-75-SUN-BSDF-DEFER.md及工作区outputs/alpha.74、alpha.75。下一项单独检查radiance最近命中交接，先做GPU命中对照，不能从微秒差直接承诺帧收益。


## alpha.76：radiance Query 未证实加速（2026-10-15）

复用已接受的最近命中Query helper到radiance，保留全部payload/flags/mask、cutout/OMM和pre-decode SER；Caustica在此仍用TraceRay，此项依据VL自身剩余调用计时提出。一个客户端先做GPU命中replay，34048条实际radiance射线/27946hits零不匹配（透明3、cutout0），随后纯A–B–A的Transport3.452240/3.486336/3.552352ms、PT5.366592/5.409120/5.524416ms。没有快过两端A；最后段GPU中位时钟2430MHz，相对起始2520MHz超出3%一致性范围。无法验收加速，也不据此判定一定变慢；不追加测试。两份捕获26层有限并正常退出0。

已核对执行revision触发context重建、GGX_INLINE资源别名及包含实际binary的pipeline-cache键；没有发现A/B误用同一stage。alpha.74/.75/.76均隔离、不保留；用户验证的alpha.73画质、alpha.67生产着色器及安装JAR哈希不变。本轮未取得新的性能提升，Caustica约2.214ms PT目标仍未达到。下一轮应先取得完整候选准备/FULL BSDF的成本上界或可用硬件shader指标，停止以单次调用延迟推断整帧收益。详见 performance/ALPHA-76-RADIANCE-QUERY.md（同目录内为ALPHA-76-RADIANCE-QUERY.md），原始证据outputs/alpha.76。
