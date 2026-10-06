# 迁移任务、性能预算与验收

[总设计](README.md)。本次交付为文档，不修改渲染器、不发布新 jar；以下阶段都还需要实施。先让每次变更可测量、可独立比较，再决定默认功能。

## 1. 测量合同和基线

现有 `.passes.csv` 的 frame 实际 scope serial、width/height=0。M0 改为每条 scope 有 `renderFrameId/scopeId/parentScopeId`，每帧另有唯一 summary；GPU 延迟结果携带原始 frame ID，不冒充采样完成时的当前帧。

固定记录字段：版本/提交、GPU/驱动/OS、输出/内部尺寸、requested/actual spp、mode/reconstruction/upscale/DRS、scene/resource/world epoch、active instances/sections/triangles、trace dispatch shape、GPU clock/温度可用性。tick/partialTick 和 capture age 留作定位动态问题。GPU telemetry 不可用时标 missing，不伪造稳定时钟。

| 类别 | 每帧主要计数 |
|---|---|
| CPU/提交 | collect/plan/commit/record/submit、malloc/alloc 次数、VkBuffer/VkDeviceMemory/AS create、host copy bytes、driver wait |
| 场景 | changed terrain/dynamic mesh/transform、BLAS build/update、TLAS build/update、scratch peak、retired bytes |
| 纹理/灯 | active/dirty/uploaded tiles、bytes、emitter local rebuild/world update、environment rebuild |
| 追踪 | 各 vertex 活路径、primary/continuation/shadow rays、binary/RGB visibility、any-hit/alpha rejection、interface cap、material/medium 分布 |
| 重建 | per-signal accepted history、disocclusion/reactive、motion invalid、interop copy/wait/kernel、upscale 时间 |
| 覆盖 | loaded/resident/stale/missing sections、primary/secondary incomplete sample 比例、page 请求/命中/eviction/overflow |
| 数值/预算 | invalid samples、RR 终止、max-depth 截断、queue overflow、CPU/GPU used/allocated/retiring/peak |

shader instrumentation 用 debug variants，GPU counter 延迟读回；固定 seeds/camera 路径，测 profiling on/off 扰动。标准 scope 包含 inclusive/exclusive 语义；总 world/frame 从真实开始/结束 timestamp 测量，不能相加父子 scope、CPU/GPU 或不同窗口分位数。

对照 alpha.23 的导出只复现趋势，**新基线必须重新采集**。每场景先 warmup 至资源稳定，再至少 60 秒同条件静止/移动录制，报告 median/P95/P99、whole-frame 与 world 区间、CPU/GPU bottleneck、上传量、覆盖与质量。多次重复并注明异常/节流；不以最高瞬时 FPS 或最终 status 替代整个窗口工作量。

## 2. 迁移阶段与代码落点

### M0：可靠 frame context 和测量

落点：`RenderPassProfile`、`GpuPassTimer`、`PassMetrics`、`VulkanPathTracer`、`VulkanRtContext`、`tools/analyze_rt_profile.py`。

产出：逻辑 `RtFrameContext`（可以先为 record）；真实 frame ID + scope parent；每帧 scene update 次数、requested/actual dimensions/spp、trace counters。保留旧日志 reader，新增 schemaVersion，不静默改变 frame 字段含义。

验收：一帧 summary 能关联全部 scene/material/trace/reconstruction scope；无宽高 0；多 spp/DRS 改动在每次 dispatch 可识别；GPU 未完成记录不混入完成样本；指标采集本身扰动有量化。没有正确 M0 数据，不声称后续改造提升固定百分比。

### M1：收集变动后一次 commit

落点：`VulkanPathTracer.render` 的 terrain prepare + dynamic prepare；`VulkanRtContext.prepareScene`；`RtDynamicScene.prepare`；`VulkanRtScene.update/rebuildTlas`。

产出：update 从“立即 build/TLAS”改为 pending collect；`commitFrame` 汇总 admission、上传、BLAS、instance、emitter，发布唯一 snapshot。先保持现有 geometry packing 和 transport，避免同时变 shader ABI。

验收：无变化帧 TLAS=0；有 terrain+dynamic 更新的普通帧 TLAS≤1；同帧引用的 AS/geometry/light 版本一致；空 section、删除、突然 capture 失败、budget 拒绝仍正确；render 后到达的 worker snapshot 留下一帧。对照旧路径图像与 baseline 能量，frame 时间不回退。

### M2：持久资源与独立 dirty generations

落点：`VulkanRtBuffer`、`VulkanRtAccel.build`、`VulkanRtScene`、`VulkanRtMaterialAssets`、`RtEmitterTable`、`RtDynamicScene`。

分小步：缓存 build-size signature → 动态 vertex/AS capacity 复用 → scratch arena/staging ring → texture dirty/version → section-local emitter delta。此阶段先复用现有布局，shader arena 改动移到 M3。

验收目标：warmup 后静态镜头且无动画时零 AS build、零材质/纹理重复上传；容量稳定动画帧新增 `vkAllocateMemory` 为零（需要扩容/世界新增的帧明确标出）；同纹理/不变 skin 不复制；纯环境参数变动不刷新 geometry。在途资源 completion 安全、scratch non-overlap、UPDATE 约束通过 validation；retiring peak 纳入 budget，连续运行无单调增长。

这些是资源行为门槛，不是“必须达到某毫秒”的性能承诺。动态 allocator 仍可能有 CPU 工作，需实际测量。

### M3：稳定实例与 geometry indirection

落点：`DynamicModelBuffer.RtModel`、`EntityShadows`、`BlockEntityShadows`、`RtDynamicScene`、`VulkanRtScene`、`VulkanRtPipeline/VulkanSbt`、`terrain_material.slang`、`material_closest_hit.slang`。

产出：保留原生提交对象/feature 身份，local geometry + current/previous pose + instance transform；shader geometry 稳定 allocator/table；custom index 改 instance slot；primitive/material binding 与 emitter key 同步切换 ABI version。CPU reference fixtures 和旧 triangle packing adapter 保留作迁移比较，不在最终 runtime 保留两套 scene。

验收：100 个共享静态 mesh 的刚体移动只改 instance，无 vertex 上传/refit；同皮肤不同姿态不串模型；箱子不动不 refit；section eviction 不搬移无关 geometry；对象删除/slot reuse/history 对应正确；大正负坐标/origin shift 无几何抖动。离屏 capture 缺失如实显示 poseAge，不把保留模型当实时完整动画。

### M4：surface ABI 与真实动态运动

落点：`material_primary.slang/material_resolve.slang`、`RtSignals`、`RtReconstruction`、`rt_denoiser_guides.fsh`、`OptixReconstruction`、`shaders/rt/tests/reconstruction_fixture.slang`。

产出：同 primary sample 的 surface ID、viewZ、normal/roughness、current→previous motion、confidence；上帧 pose/transform；每类贡献 owner 和 signal sum。首先替换现有 Vulkan/OptiX 的错误/缺失输入，尚不同时换 denoiser。

验收：单像素解析运动落点、camera rotation/translation、object-only motion、pose motion、hand FOV、sky、origin shift；beauty=sum signals 的数值等价；动态不再全屏 reject，disocclusion 不拖尾；中心 guide 与 noisy surface mismatch 有显式 debug。此阶段若信号仍错误，不进入 SDK 质量比较。

### M5：transport 的低风险结构优化

落点：AS geometry classification、`material_cutout.slang`、`material_transport.slang`、`emissive.slang`、`VulkanRtContext`。

顺序：opaque/cutout/transmission 分类 → 同 vertex 近灯查询复用/空间 proposal → hot/cold path state → binary/RGB shadow 分离 → 存活 compaction。每一步单独开关和 profile，不一次性全上 wavefront/ReSTIR。

验收：stone 场景 any-hit 显著减少且 trees/glass/torch 仍正确；baseline 与新算法无系统能量偏移；line/plane 薄墙不漏光；active queue 只在实际有收益尺寸开启；queue overflow 被处理而非丢样本；register/spill/occupancy 与 RT 时间证实收益，不能只比较 PathState 字节数。

M4/M5 可交错实施，但 common shader ABI 修改需分别提交/验证；M3 的稳定身份是光源重采样与对象运动的前提。

### M6：分信号降噪与真正放大

落点：`RtReconstruction`、`RtReconstructionBackend`、`OptixReconstruction`、`native/denoiser`、Vulkan 重建 shader、`VulkanPathTracer` display/scale。

产出：Vulkan baseline 分信号 + TAAU；同输入 NRD 与 OptiX 对照；透明/体积独立策略；必要时 RR device-init/native bridge 单独增量。默认选择依据 target GPU 的画质和端到端时间，保留明确 fallback reason。

验收：内部尺寸固定/1 spp 相同，边缘、薄树叶、hand、移动实体、反射/折射与灯光变化的稳定性通过；输出像素确实由 temporal reconstruction 而非 LINEAR；switch/failure 同帧 fallback，不共享错误 history；interop/native runtime 版本固定且安装包只含合法必要组件。RR 不叠默认去噪；NRD 不把 transparent signal 当 opaque。

### M7：环境、覆盖与视觉特性补齐

落点：`LightingEnvironment`、`Atmosphere`、`VulkanRtEnvironmentAssets`、`RtTerrainWarmup`、overlay mixins、`VisualComposite`。

产出：Overworld/Nether/End profile、页面 coverage state/debug、HDR overlay depth、透明/加法粒子与天气、glint/outline/crumbling ownership；完善 reference 分块预算和显存总账。未实现特征有明确原生回退而不是默默消失。

验收：维度切换不残留太阳/旧介质，资源 reload 无陈旧 UV；PT success 原生 features 只画一次；PT failure 手持/UI/水/透明恢复；缺页不累计为精确 reference；最大允许范围/视距与 fast flight 的覆盖质量、等待延迟有记录。

### M8：独立高级实验

ReSTIR DI → radiance cache/远景代理 → 焦散 → OMM/SER → HDR → FG，各自独立设计增量和 gate。不是必须一次把所有项目做完；只有已解决前置数据/同步合同且质量/成本曲线更好才进入默认。GI/PT reservoir、异质体积、GPU skinning 按真实瓶颈决定。

## 3. 性能预算设计

目标先在一张明确 GPU 上建立可用的 1080p 输出方案，再扩设备矩阵。60 Hz 的 16.67 ms 是**整帧预算目标**，不是 alpha.23 或本提案已经达到的性能；原生游戏 CPU/UI、呈现和 headroom 都必须算入。

`budgetPT = targetFrameGPU - measuredNativeRemaining - reserve`。实际 GPU 时间直接量测并按 dependency critical path 理解，禁止把不同队列有重叠的时间全部相加。CPU 单独预算；CPU scene 若已占整帧主要部分，降低 spp 不解决它。

场景更新（CPU/上传/AS）、transport、reconstruction、overlay/display 各有 soft budget；primary 可见编辑与成功帧 correctness 属于 hard requirement，不能低优先延期。planner 使用已完成 GPU rolling estimates，允许短期 build 峰值但记录 P95/P99；没有独立执行前后 timestamp 时不伪造单项时间。

内存预算按：

```text
peakGPU = resident geometry/attributes + BLAS/TLAS + material/textures
        + max concurrent scratch/staging + path/queues
        + signal/history/upscale + optional interop/SDK allocations
        + retiring resources + native-game headroom
```

Path 初始热状态 112 B/path，冷介质/signal/queue 另算；不能宣称只占 112 B。RGBA16F image=8 B/pixel、RGBA32F=16 B/pixel、R32F=4 B/pixel，history ping-pong 和 SDK scratch/persistent 逐项列出。以实际 allocation requirements 和 GPU budget query 验证 estimate；禁用 transmission/volume 时可省相应 image，但 signal sum 合同要一致。

提高内部尺寸前先证实预算能覆盖 geometry/AS/reconstruction peak，不默默把 1080p 输出设回 214×120 来证明高 FPS。质量档包含内部比例、path depth、light proposal、reconstruction 和 scene range，stats 明示；固定基准不能换档。

## 4. 场景验证矩阵

| 场景 | 主要检验 | 必留输出 |
|---|---|---|
| 单 diffuse/conductor/coat；白炉 | BSDF/sample/PDF/能量，新增优化等价 | CPU Slang error、reference HDR |
| 黑/百万亮天空 + 手持灯 | 独立 held、MIS、finite、曝光 | held on/off radiance/visibility |
| 薄墙室内/大量火把/小 emissive | 光源 support、阴影、RIS/ReSTIR bias | shadow/有效贡献、收敛图 |
| 草地/树叶/栅栏/台阶/门 | alpha、几何覆盖、opaque classification | any-hit、motion/coverage mask |
| 镜子、粗糙铁、水/玻璃后移动实体 | specular/transmission 运动与重建 | 分 signal、hit distance、history age |
| 浅水/深水/岸线/相机入水/TIR | medium identity、stack、Beer/fog ownership | medium debug、reference 差分 |
| 生物群/箱子/旗帜/同皮肤多实体 | stable ID、pose、instance/refit | upload/build/transform、poseAge |
| 第一人称主副手、FOV/摇晃/受伤 | view-model 投影、motion、held 与 suppress | native/PT A/B、feature ledger |
| 放置/挖掘/爆炸/活塞/区块快速加载 | revisions、stale/empty、背压、一次 commit | frame versions、pending age、P99 |
| 长距离飞行/大坐标/反射中离屏地形 | paging、precision、secondary coverage | page latency/eviction、incomplete |
| F3+T/resize/fullscreen/off-on/切维度/退出 | resource lifetime、epoch、恢复 | validation、allocated/retiring curves |
| blend/additive 粒子、雨雪/glint/outline/UI | depth/HDR/重复绘制/回退 | feature coverage 与最终画面 |

测试画质在同一 committed scene snapshot、相机、环境时间、贴图帧、曝光、resolution/spp 下比较；freeze shader clock 不能被计为 realtime 性能优化。Reference 输出 scene-linear HDR + metadata；初期六顶点 oracle 用于迁移等价，深 reference 用于检查 truncation。高样本还未收敛或覆盖不足时标明，不能凭一次截图宣称“无偏”。

建议误差同时报告 relative luminance/RGB、平均与高分位、暗区绝对误差，以及 motion sequence ghosting/disocclusion；SSIM/PSNR 仅辅助，不能替代镜面/透明/hand 实机观察。统计随机估计器时给样本量、置信区间/多 seed，新增算法的允许误差按基线收敛噪声设定，不编造统一百分比。

## 5. 自动验证与实机责任

沿用现有 `./gradlew build` 的 Java 合同、Minecraft GLSL 链接、RT SPIR-V 验证、实际 Slang CPU transport fixture。新增 meaningful tests 对准以下不变量：

- allocator slot/generation/reclaim、pending coalescing/epochs、scene commit 一致、UPDATE signature、预算峰值与空页移除。
- primitive/material 分类、样本 ID 不受 queue reorder 影响、PDF/MIS parity、queue overflow 处理、signal sum。
- 解析 motion/pixel 落点、pose/origin shift、相同 sample guides、texture/material history、SDK adapter 空间/方向/roughness。
- native draw suppression 仅 capture+commit+display 成功、回退完整、资源在途不提前释放。

GPU/driver 层必须在目标 RTX 与另一个 Vulkan RT 设备（如可用）执行 validation/synchronization validation、长时压力、queue/AS/interop 验收。构建主机没有 NVIDIA GPU，CPU fixture/SPIR-V 通过不等于 driver refit/重建画质通过。设备不足时列出 pending，不泛化为全平台支持。

每阶段先编译及合同验证，再固定机位实测；小改动不重复无关测试。文档变更只校验内容、相对链接与 diff，无需为未改 runtime 发布安装包。

## 6. 文档、配置与依赖交付

当前版本、命令与安装状态仍由 `README.md` / `CURRENT.md` / `SETTINGS.md` 管理。新增 `rt_scheduler`、NRD/RR/upscale 等设置只能在真实实现后加入 help/preferences migration，不提前宣传可用。每项设置明确能力、fallback reason、请求/实际值与 reference applicability。

新增外部依赖仅在阶段开始固定 release/commit、工具链、平台和许可证；NRD、RTXDI、Streamline 的源码/二进制分发与 NVIDIA runtime 各按实际许可处理。参考设计无需复制第三方实现；若后续复用 Caustica 代码，单独评估其 LGPL 和 bundled third-party obligations，不把“开源”理解为可无条件拷贝。

旧 `RTX-PATH-TRACING-ARCHITECTURE.md`、`PATH-TRACING.md`、`FULL-REFERENCE.md` 保留历史入口；当前设计调整以本组文档为准。实施每阶段更新当前状态及对应细节文档，不把提案表改成“全部已完成”。

## 7. 下一轮可以直接执行的范围

优先做 **M0 + M1**：真实 frame 数据和单次 scene commit，保持 BSDF、路径数量、纹理外观和重建后端不变。它们会给出可靠的场景 CPU/TLAS 成本，并为 M2 的持久资源验证提供基线。随后 M2/M3 解决每帧资源分配、重复 emitter/texture 更新和实例身份；M4/M6 解决动态画面与高分辨率输出；M5 针对最大的 RT batch 成本。

不以“先接 RR”“直接少三次 bounce”或“换体素引擎”替代前述可验证任务。每阶段可以交付独立版本，性能收益由完整 GPU/CPU/质量数据决定。
