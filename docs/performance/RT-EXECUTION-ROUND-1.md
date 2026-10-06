# Vulkan PT 执行层第一轮：alpha.27

本轮保持现有六层输运、光源 proposal、RNG、BSDF、MIS、介质和 spp 平均规则。目标是减少 shader 调用和状态传输。代码、数值回归和可执行的 A/B 入口已经落地；没有 NVIDIA GPU 的构建环境不能证明 1.3–2× 加速，也不能宣布执行层通过实机冻结验收。

## 实际执行结构

```text
Section BLAS（同一顶点缓冲内稳定分类）
├ OPAQUE：geometry OPAQUE，closest-hit，没有 any-hit
├ CUTOUT：cutout closest-hit + any-hit；可选保守 OMM
└ TRANSMISSION：closest-hit，没有 alpha any-hit

Visibility
├ opaque TLAS：共享 OPAQUE 顶点、独立 opaque BLAS/instances
│  └ FORCE_OPAQUE + first-hit terminate；TraceRay 或 Ray Query
└ 主 TLAS：cutout 与透射界面、原来的 24-interface 上限
   └ Fresnel、RGB extinction、介质栈和 flame 自遮挡例外
```

每个 material BLAS 固定三个 geometry，即使某类为空。三角形按类别稳定排序，顶点 UV、法线、tint、flags 一起移动。实例表末尾每实例三个 float4：当前平移、上一帧平移/身份、三个 geometry 的 primitive base。closest-hit/any-hit 用 `InstanceID + geometryBase[GeometryIndex] + PrimitiveIndex` 还原稳定 shader arena 中的全局三角形索引；emitter proposal、上一帧动态顶点和 topology 使用同一排序。

CPU 分类读取实际上传的 Material 3 / LabPBR palette 网格；UV 不确定或网格缺失时归入透射范围。动态模型继续遵循现有漫反射合同；CUTOUT 优先于 TRANSMISSION，保留植物/玻璃 cutout 的 alpha 语义。Opaque shadow TLAS 排除 view-model，与原有 world shadow mask 保持一致。它增加 opaque AS storage，但复用顶点和 scratch；比较 transport 与完整帧时要分别计入 AS 成本。

Opaque visibility 的 TraceRay payload 是单个 uint（4B），hit 保持 1，独立 miss 写 0，跳过 closest-hit。Ray Query 使用相同 origin、direction、TMin/TMax、mask 和 opaque AS。主世界 traversal 在 OMM 关闭时 cull opaque geometry，只处理剩余 cutout/透射。Opaque miss 且场景无剩余类别时直接使用原有介质 extinction。Flame 的源模型例外保留原来的完整遍历，不能用 opaque early-out 提前遮住自己的灯芯。OMM 开启时保留完整界面遍历，避免 fully-opaque micromap cutout 被 cull 掉。

这些标志的含义以 [Vulkan Ray Traversal](https://docs.vulkan.org/spec/latest/chapters/raytraversal.html) 为准；FORCE_OPAQUE 只用于已经剔除透明类别的 AS。

## OMM 的当前范围

默认关闭，`rt_omm on` 请求启用，必须同时满足广告扩展、物理 feature 和 Minecraft device 的已启用能力。初始实现使用 Vulkan OMM **三角级 special index**：全透明 -1、全不透明 -2、未知 -3。没有伪造混合 alpha 的透明度；跨 UV 范围有一个 texel 的保守 guard，未知/动态/普通动画 texel 执行原 any-hit。玻璃/水 ID 的 alpha 例外与 shader 相同，不乘 tint alpha。

这是合法的 OMM attachment：全部 index 为 special index 时 `micromap = VK_NULL_HANDLE`，不需要独立 micromap 数据对象。依据 [VkAccelerationStructureTrianglesOpacityMicromapEXT](https://docs.vulkan.org/refpages/latest/refpages/source/VkAccelerationStructureTrianglesOpacityMicromapEXT.html)。**混合 alpha 三角形尚未细分为子三角 micromap**，所以不能把它称为完整的高细分 OMM baker；叶片边缘仍执行 any-hit。此接入避免新增 SDK 和烘焙成本，先测已知覆盖的收益。

Opacity 从静态资源 albedo 读取；原生 atlas 超过 ID 网格尺寸时全部保持 unknown，避免缩放丢失 alpha 细节。alpha.32 中 Minecraft Vulkan encoder 对已知静态 alpha 区域的后续写入仅使对应区域变为 unknown；opacity epoch 改变后重建驻留静态 OMM BLAS，回退受影响三角形的精确 any-hit。完整 coverage 不可用时 TLAS instance 仍禁用 OMM。写动画 unknown 区域和 alpha 不参与 cutout 判定的玻璃/水区域不会误伤。绕开 Minecraft encoder 的第三方底层纹理写入不在此 tracker 合同内；这种兼容场景保持 OMM off。

## Continuation 与 scratch

| 数据 | alpha.26 | alpha.27 |
|---|---:|---:|
| PathHot | 144B/path | **64B/path** |
| Cold medium | 288B/path | 288B/path，mediumCount > 0 才访问 |
| 独立 AOV 累积 | 包含在 hot 中 | 48B/path |
| Continuation + AOV 分配 | 432B/path | 400B/path |
| Beauty 输出 | 16B/path | 16B/path，兼作 radiance 累积 |

PathHot 三个 float4 保存 origin、direction、throughput；末尾 16B 保存 packed state、etaScale、uint RNG seed 和对齐字。Packed state 的 bit 0–3 是 medium count，4 是 previous delta，5 是 invalid，6–7 是 AOV channel。未使用的 reserved 字段不再传输。Radiance/AOV 直接写独立输出，不在 continuation 恢复时载入，也不作为 advance 中的累计 RGB 跨 TraceRay 保持；每个原始 path index 独占自己的累积位置。固定 dispatch、compact、SER 都保持这个 index。

Scene 拥有一个幂次增长、按设备 scratch alignment 对齐的 arena。BLAS/TLAS 仅持有 AS storage 和 build/update 所需的大小；每次构建之间用 AS_BUILD read/write dependency 串行复用 scratch。增长时旧 scratch 留到引用它的 command buffer 提交后再进入 Minecraft 的延迟销毁队列。异常清理覆盖新建 opaque/main BLAS、micromap index 和 upload buffers。

## Active queue 的真实标定

`rt_queue auto` 去掉分辨率乘 spp 的 65536 固定阈值。支持间接 tracing 且 buffer 范围足够时，两张 GPU queue 持续分配，切换模式不重新分配。`fixed` 与 `compact` 是明确的强制 A/B 入口，实际回退见 stats。

AUTO 未标定时默认 fixed；打开 profiling 才交替测试。延迟返回的 GPU batch 时间按真实 frame 与每八帧的 alive[0..5] 曲线匹配，并验证尺寸、spp 和 scene generation。按尺寸、spp 和五次 continuation 平均存活率的十分位桶保留最多 31 个样本/模式；两个模式各至少六个匹配样本后，以中位数比较，compact 至少便宜 3% 才选中。曲线/时间乱序到达都可匹配，CPU 时间、丢失帧和跨场景结果不参与学习。策略只在当前 context 保留，不把一块 GPU 的经验写成另一块 GPU 的硬编码阈值。

关闭 profiling 后停止额外计数和 visibility 回放，使用已有测量；未观察的新尺寸回到 fixed。这属于实际设备在线标定入口，**当前构建机没有产生任何 RTX 标定结果**。场景改变可能改变最优调度；必要时用两个强制模式重新捕获。

## SER 是最后一项 A/B

默认 off。`rt_ser on` 只有 device feature 和 `REORDER` hint 都支持时才选择真实 NV SPIR-V 变体；在命中/材质解码后按 material type 进行 `ReorderThread`，其余 estimator 不变。视图索引在 reorder 前转为持久原始 path index；reorder 后的 held diagnostics/page requests 不读取变化后的 invocation builtins。此轮没有使用 HitObject 解耦 traversal，也不把 SER 当作硬件自动提速承诺。实现依据 [Slang ReorderThread](https://docs.shader-slang.org/en/stable/external/core-module-reference/global-decls/reorderthread-07.html)。

## A/B 操作与指标

安装后沿用原来的 RT 设置。先固定窗口、`rt_scale`、`rt_spp`、场景和资源包，静止预热至 section/page 数量稳定。建议以 reference 单 spp 记录输运，并用 realtime 重复移动场景；reference 不开启 freeze。

```text
/voxellight rt_ser off
/voxellight rt_omm off
/voxellight rt_queue fixed
/voxellight rt_visibility legacy
/voxellight profile on
# 每段 20–30 秒，export，再逐项切换；切换 visibility/OMM/SER 会重建 context，重新预热。
/voxellight export
/voxellight rt_visibility trace
/voxellight export
/voxellight rt_visibility query
/voxellight export
/voxellight rt_queue compact
/voxellight export
/voxellight rt_queue auto
# 开 profiling 至 stats 中 matchedGpuSamples 有足够匹配数据
/voxellight export
# 然后保持获胜的 visibility/queue，分别测试 OMM，最后测试 SER
/voxellight rt_omm on
/voxellight export
/voxellight rt_ser on
/voxellight export
/voxellight profile off
```

只在有 opaque AS、fast visibility 且 profile on 时，从生产 visibility 调用中捕获最多 256 条真实射线，在 transport batch 结束后回放 TraceRay 与 Query。按八帧块交替先后顺序，第二次回放比较 uint 结果。`.rays.csv` 包含 opaque visibility 调用数、回放射线数和 mismatch；mismatch 必须为零。`vulkan_rt_visibility_{trace,query}_256_{first,second}` 是**最多 256 条射线的独立微基准时间**，不是整帧 inline visibility 总时间；必须分别比较相同先后位置，不能把缓存预热收益当成 Query 加速。

`.passes.csv` 包含 primary、bounce1..5、sample resolve、fixed/compact batch、AS 与回放 scope。父子 scope 不可相加；回放在 batch scope 之外。`.rays.csv` 的 any_hit/active_0 是全部 any-hit invocation 除以 camera primary 数，包含 continuation/shadow 的 any-hit，不是“primary-only any-hit”。分辨率、spp、场景和 alive 曲线必须匹配。可运行：

```sh
python3 tools/analyze_rt_profile.py benchmark-results/voxellight/*.passes.csv > execution-report.json
```

广告并启用 `VK_KHR_pipeline_executable_properties` 时，创建 pipeline 捕获驱动编译统计，`export` 附带 `.passes.csv.pipelines.csv`。原样保存驱动定义的 executable/name/value；没有 register/spill 字段就没有此项结果。依据 [Vulkan pipeline executable properties](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_pipeline_executable_properties.html)。

运行时 register spill、L1/L2 traffic、inline visibility 的 shader 调用占比使用 [Nsight Graphics Shader Profiler](https://docs.nvidia.com/nsight-graphics/UserGuide/shader-profiler.html) 的 GPU Trace、shader/callsite、live-state 和缓存指标，在 profile off 的稳定窗口捕获；Java/SPIR-V 静态大小不能代替这些硬件数据。

冻结条件：相同场景 RGB/AOV 无回归、Query 回放 mismatch=0，any-hit/primary 降低，visibility/bounce1/bounce2 与完整 transport 的实测中位数改善，无更高 spill/缓存流量导致的反退化，AS 建造/显存成本可接受。1.3–2× 是目标，不是本版本已测量的结果；是否冻结仍取决于上述 RTX 实机数据。

## 本轮自动验证

278 项 Java 回归（分类、OMM 阈值/失效、延迟曲线匹配、AS 签名/SBT/可选 SPIR-V 能力等）、23 个 RT SPIR-V stage 的 spirv-val/ABI 检查、真实 Minecraft GLSL pipeline 链接，以及实际 Slang CPU target 数值回归。Visibility 对比运行生产 fast/legacy 函数，仅以 CPU 三角相交替代硬件 traversal；状态验证运行生产 64B load/store、独立 AOV/radiance、两张 sample bank、八层 medium、invalid/delta/channel 和三类 primitive-base 映射。硬件驱动的 AS/OMM/Query/SER 执行仍需实机验收。

alpha.32 新增按 bounce 的 HYBRID 候选、固定 60 MiB 静态快照完整装入校验、GPU 预热稳定检查和大幅重复段漂移拒绝，详见 [自动测试](RT-AUTOMATIC-BENCHMARK.md)。驱动编译统计缺失原因导出至 `pipelines-status.json`；运行时 spill/缓存指标仍需硬件 profiler。
