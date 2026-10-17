# alpha.71：发光物叶级 alias 读取链实验未证实净加速

日期：2026-10-15。生产基准继续 alpha.67，候选保持隔离。没有重跑 Caustica，也没有替换安装游戏。

## 差异与已有证据

alpha.68 在接受版上观察到 proposal geometry/alias/PDF 的含等待调用延迟约4.6µs，约2.28M candidates/frame；这不是可累加的 GPU 帧毫秒，也没有把其中某次 Load 单独归因成硬件瓶颈。本轮依此定位编译后确实存在的序列依赖，再通过单项 A–B–A 判断其帧时间影响。

对照固定 Caustica commit `14770591086232e13a6b1d5320b0f2f8ef8731e3` 的 `lighting.slang:selectSectionLight`：光源记录按 section 连续排列，采样 alias 得到局部索引后，可以直接计算 `firstLight+localIndex`。其 LightGridSpan 还直接保存 self/alternate section 的 first/count，绕开独立 node 读取；这属于另一项结构性差异，本实验未一起修改。当前 Light 真实 ABI 是32B、LightAlias8B，旧源码注释中的 Light48 字样已经过时。参考仓库注释里的其他机器毫秒不作为本机证据。

VoxelLight 的64B记录全局按 triangle ID 排序，供 runtimeHitPdf 二分；同一 section 的记录 ID 可能不连续。旧叶级 alias 为16B，包含 threshold、alternate slot、self light ID、conditional PMF。旧 FULL QUERY SER Indirect SPIR-V 保留：读取 threshold `%14028` → 拒绝时读取 alternate slot `%14037` → Phi得到chosen column `%14038` → 读取该列+8的 light ID `%14044` → record。确认的是这条依赖未被编译器消除，尚非缓存未命中／带宽／寄存器停顿归因。

## 最小改动

只改变叶级 alias 第四个word：重复的 conditional PMF 改为该列 alternate 对应的全局 light ID。原 alternate slot、自身 light ID 和 threshold 保留；原精确条件 PMF 已在 light record+12中，继续从那里读取。section/global/grid alias 布局和 PMF 全部不变。

新增 runtimeLightAlias 从当前采样列读取 threshold/self ID/alternate ID 后直接选择 light ID，不再追到 chosen column。Java 构建器只改变叶级写入值；没有增大表、buffer、上传或descriptor，也没有更改 RNG、提议分布／数量、采样坐标、空间 emission、FULL 材质或 EXACT 介质光学。未叠加 alpha.69/70。

候选编译后的 `%14021/%14026/%14030` 三次 Load 位于同一基本块，先于分支，`%14034=OpSelect` 直接选择record ID；被选中列的回查已经消失。Primary/Indirect 分别426216→425992B、440460→440236B，各减少224B。SPIR-V依赖和大小不等于最终GPU执行时长／内存事务测量。

## 模块与端到端验证

用实际 Java 构建器生成0/1/2/17/160/512 sections的表，含相互穿插的triangle IDs、area/point、稀疏和大动态范围权重、adaptive factors、PMF索引及容量回退。逐字节验证表长度一致，且除叶级第四word外全部字节相同；每个新alternate ID都等于旧表追到对应列取得的ID，light record／PMF原位不变。

实际 Slang CPU 执行24896组叶级选择，覆盖threshold和相邻浮点数、0/1端点、单光源／奇数数量／跨section非连续IDs；完整FULL RIS执行524288组，含金属、玻璃／水、各向异性、多散射、terminal/cache/volume、OWEN/SHIFT。分别比较“旧Java+旧Shader”“新Java+旧Shader”“新Java+新Shader”：输出、alias选中记录的条件PMF、reservoir摘要和完整32位最终RNG逐位相同，最大归一化误差0。新表对旧Shader的独立对照证明A仍是有效基准；旧叶级PMF在该选择路径中不被使用。

一次有效隔离客户端，通过Windows-MCP启动／核验前台，自动完成multi_light A–B–A，每段采样8秒。两边共用相同新Java数据和原native，只切换逐字节匹配alpha.67的Shader别名与候选Shader。无内部Shader clocks或parity capture，counter frames排除；容器bank为diagnosticOnly，driver将纯代码计时标记为formalTimingEligible。

独立ENDPOINT/ENDPOINT_FAST捕获共26个float layer均有限，HDR、RR输出及transmission非零，客户端正常退出0。这是端到端健全性验证，不是逐像素画质等价证明。Windows-MCP此前因USER_CONTROL拒绝启动，用户暂停操作后才实际启动；未绕开控制状态。

## 性能结果

GPU P50，单位ms：

| 阶段 | A1 | B | A2 | 相对两端A均值下降 | A自身漂移 |
| --- | ---: | ---: | ---: | ---: | ---: |
| transport | 3.488320 | 3.495744 | 3.472960 | -0.43% | 0.44% |
| pt.ready_rr_inputs | 5.417312 | 5.428640 | 5.398528 | -0.38% | 0.35% |
| world | 7.464736 | 7.486976 | 7.463936 | -0.30% | 0.01% |
| primary_guides | 1.672576 | 1.672240 | 1.674432 | 0.08% | 0.11% |
| transport.bounce.1 | 1.683840 | 1.668656 | 1.678400 | 0.74% | 0.32% |
| transport.bounce.2 | 1.347936 | 1.361216 | 1.349696 | -0.92% | 0.13% |

Transport P95 为 3.926499 / 3.922658 / 3.969408ms；PT P95 为 5.973949 / 5.881291 / 5.979187ms。

三个block有效且GPU范围覆盖完整，geometry signature为1417246207890208711，40 sections、5453040 scene bytes、524×336、SPP1、FULL/EXACT/LATE和全部区间资源抽样一致。主要workload均值最大变化0.000200%。遥测样本数7 / 7 / 7，时钟中位数2520.0 / 2520.0 / 2520.0MHz，最低2520.0 / 2520.0 / 2520.0MHz；遥测最大间隔1.473秒，窗口边界约一个遥测周期不确定。CPU/World分位数与最大值另存sampling-limitations.json。仅一次短A–B–A，没有反序复测或硬件cache/spill/register计数，不外推持续FPS。

## 验收与后续

事先条件为Transport和PT同时快于两端A，且下降幅度超过`max(2%, 基线自身漂移)`，并满足场景／资源／workload／时钟匹配。结果：**未通过，未保留**。

接受版44个common/world源文件、293个生产class和默认110-stage bank全部保持alpha.67哈希。候选只在隔离源码和overlay中，未写入生产。源码依赖缩短是真实结果，但单独该级回查尚未显示可验收帧收益，不据此声称所有proposal访问都便宜。

后续如果继续缩短整条链，应单独评估Caustica式section span携带self/alternate first/count以绕过node／chosen-section元数据的方案；VL还需保留精确local/global mixture PMF、adaptive bucket和按triangle ID查询的语义，不能直接复制其距离公式或仅按32B/64B猜测带宽收益。本轮并未证明那项改法能加速。

证据：工作区`outputs/alpha.71/`中的source-audit、leaf-chain-compiler-evidence、candidate.patch、module-validation、shader-validation、self-comparison、workload-comparison、sampling-limitations、e2e-validation、status、原始suite/CSV/telemetry、源码／Java/plan快照、构建器实际表及复现脚本。未自动提交或推送。

后续控制实验见[alpha.72](ALPHA-72-LEAF-SELECTED-WORD.md)：只加载选中字段避免self额外读取，同样未证实净加速；不再追加叶级微调。
