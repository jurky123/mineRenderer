# VoxelLight v0.2 原始设计文本

> 从原始 DOCX 按段落提取，供搜索与评审；表格按单元格顺序展开，示例代码保留原文。原始 DOCX 是该版本的完整排版来源。

VoxelLight
基于 Minecraft 原生 Vulkan 的高性能 Voxel 光影系统
架构与实现设计文档 v0.2



状态
Architecture / Engineering Design
目标平台
Minecraft Java 26.3+ / Fabric / Native Vulkan
推荐开发基线
Minecraft 26.3；持续跟踪 26.4+
项目类型
纯客户端 Voxel-aware Lighting Engine Mod
日期
2026-10-01
核心方向：复用 Mojang 原生 Vulkan Renderer，仅重构 Minecraft 最缺少的世界空间光照层。

1. 执行摘要
VoxelLight v0.2 的核心架构变化，是从“替换 Minecraft 渲染后端”转向“扩展 Minecraft 原生 Vulkan 渲染器，并在其上实现专门针对方块世界的高性能光照引擎”。
Minecraft 26.2 已引入可选原生 Vulkan 后端；Fabric 文档要求渲染 Mod 通过 Blaze3D 抽象层而非直接依赖 OpenGL。26.3 继续调整 terrain shader 以支持 multi-draw，并加入 OIT 相关 shader；26.4 Snapshot 1 中 Vulkan 已成为默认 Graphics API。
核心结论：不再重写 VkInstance、VkDevice、Swapchain、terrain mesh、entity renderer 和 UI；VoxelLight 把工程资源集中投入 GPU Voxel Scene、Shadow Cache、Hierarchical Occupancy、Probe GI、Local Lighting、Temporal Reuse。

模块
归属
原因
Vulkan device / swapchain / frame lifecycle
Minecraft
官方后端已负责，避免重复造轮子
Terrain / entity / block model rendering
Minecraft 优先
最大化 Mod/资源包兼容性
GBuffer / lighting integration
VoxelLight 扩展
需要额外 normal/material/velocity 等数据
Sun shadow
VoxelLight
利用 chunk 静态性做缓存与增量更新
Voxel occupancy / hierarchy
VoxelLight
世界空间 GI/AO/visibility 的共享基础
GI / emissive / local lights
VoxelLight
Minecraft 原生光照无法提供现代间接光
OIT / UI / text
Minecraft 优先
不应重建已有成熟路径
Hardware RT
后续可选扩展
不是 MVP 前置条件

2. v0.1 → v0.2 的关键重构
v0.1 设计
v0.2 决策
影响
VulkanMod 作为硬依赖
Minecraft Native Vulkan / Blaze3D 为首选底座
降低后端维护成本
VoxelLight 自己承担 terrain backend
优先复用 Mojang terrain path
兼容性明显提高
自建 Vulkan frame lifecycle
由 Minecraft 管理
减少同步/交换链类 bug
Renderer Replacement
Lighting Engine / Renderer Extension
项目边界更清晰
LOD 作为较早目标
LOD 推迟为独立可插拔阶段
先证明 lighting 架构价值
所有高级能力倾向底层 Vulkan
Blaze3D first，native extension only when necessary
降低版本脆弱性

3. 当前 Minecraft 原生 Vulkan 渲染基础
3.1 可依赖的官方方向
26.2：提供原生 Vulkan backend，并要求渲染 Mod 不再假设 raw OpenGL 可用。
Blaze3D：位于 Mod/游戏渲染逻辑与 OpenGL/Vulkan backend 之间，是高层兼容边界。
RenderState / extraction：Mojang 正在把“从游戏世界提取渲染数据”和“GPU drawing”分离，为并行准备下一帧创造条件。
26.3：terrain shader 为 multi-draw 继续重构，并增加 Order-Independent Transparency 相关 shader。
26.4 Snapshot 1：Default Graphics API 行为改为 Prefer Vulkan，表明 Vulkan 已进入主路径。
工程含义：VoxelLight 不应绑定某个 Mojang 内部 Vulkan 类作为全局基础。稳定边界应尽量放在 Blaze3D / RenderPipeline / RenderState；只有高级 compute/storage/ray-query 能力确实无法表达时，才引入版本隔离的 Native Vulkan Extension。

4. 项目目标与非目标
4.1 目标
在原生 Vulkan renderer 上提供显著优于传统 shader-pack 方式的阴影效率。
利用方块世界“规则、chunk 化、绝大多数静态”的性质，建立世界空间缓存光照。
以增量更新和 temporal reuse 为默认设计，而非每帧全量重算。
尽量不接管 terrain/entity 模型系统，从而保留 Fabric Mod、BlockEntity、资源包兼容性。
在无 RT Core 的 GPU 上仍可运行；硬件 RT 只能是可选加速路径。
从第一版起内置 GPU/CPU profiler 和固定 benchmark，性能预算是架构约束。
4.2 非目标
不实现完整 OptiFine/Iris shader-pack 兼容层。
不在 MVP 阶段实现 Path Tracing。
不在没有 benchmark 证据前替换 Mojang terrain renderer。
不允许每个视觉模块维护一套独立世界表示。
不以 full-resolution brute-force ray marching 作为主要质量提升手段。
5. 总体架构
Minecraft World / Chunk / Entity / Resource Packs                     │            Render Extraction / RenderState                     │        ┌────────────┴────────────┐        │                         │ Native Minecraft Renderer   VoxelLight Scene Bridge        │                         │ Terrain / Entity / OIT      Dirty Regions / Lights        │                         │        └────────────┬────────────┘                     ▼             Extended GBuffer / Depth                     │        ┌────────────┼─────────────┐        ▼            ▼             ▼ Shadow Cache   GPU Voxel DB   Local Light DB        │            │             │        │       Occupancy Mips     │        │            │             │        └──────┬─────┴──────┬──────┘               ▼            ▼            GTAO       Probe GI / Visibility               │            │               └──────┬─────┘                      ▼                Lighting Resolve                      │          ┌───────────┴───────────┐          ▼                       ▼     Volumetrics             Reflections          │                       │          └───────────┬───────────┘                      ▼              Temporal Resolve                      │                 Upscaling                      │              Minecraft UI/Post
6. 与 Minecraft Renderer 的职责边界
能力
默认实现者
VoxelLight 是否接管
备注
Window / Swapchain / present
Minecraft
否
完全复用原生 backend
VkDevice / queue / frame sync
Minecraft
否
只有 extension 获取受控句柄
Terrain mesh generation
Minecraft
否
先 benchmark，再决定是否需要替换
Entity / BlockEntity renderer
Minecraft
否
保持复杂 Mod 兼容
OIT / vanilla translucency
Minecraft
默认否
VoxelLight 只做 lighting integration
GBuffer extension
VoxelLight
是
normal/material/velocity 必需
Directional shadow
VoxelLight
是
缓存/clipmap 是核心能力
GPU Voxel Scene
VoxelLight
是
只为 lighting 服务，不替代 mesh
Local lights / emissive aggregation
VoxelLight
是
现代 clustered lighting
GI / AO / volumetric / reflection
VoxelLight
是
固定渲染管线
UI / text / HUD
Minecraft
否
在 VoxelLight 输出后绘制

7. Backend 分层设计
7.1 高层：MinecraftRenderBackend
interface MinecraftRenderBackend {    FrameContext frame();    RenderTargets targets();    SceneDepth depth();    void addRenderPass(RenderPassDescriptor pass);    void addComputePass(ComputePassDescriptor pass); // capability-dependent    TimestampQuery timestamp(String name);}
该接口是 VoxelLight 核心模块唯一可见的后端边界。Shadow、GI、AO 等模块不得直接持有 Mojang Vulkan 内部对象。
7.2 NativeVulkanExtension（可选）
仅当 Blaze3D 当前公开能力无法满足高效实现时启用，目标是补齐少量 GPU primitives，而不是创建第二套 renderer。
Storage Buffer / Storage Image
Compute dispatch / indirect dispatch
Timestamp query
Advanced barriers / timeline synchronization bridge
未来可选 VK_KHR_ray_query / acceleration structure
约束：NativeVulkanExtension 必须版本隔离，并由 capability 检测控制。核心算法必须能够在缺失某项扩展时降级，而不是让整个 Mod 无法启动。

8. World Scene Bridge 与统一脏区系统
VoxelLight 不能让 Shadow、GI、Light DB 分别监听 Minecraft 世界事件。应首先建立统一的 WorldSceneBridge，将游戏世界变化转换成不可变/可并发消费的渲染事件。
World / Chunk Events        │        ▼WorldSceneBridge        │        ├─ SectionChanged(sectionPos, changedMask)        ├─ LightChanged(region)        ├─ DimensionChanged(...)        ├─ CameraTeleported(...)        └─ ResourceReload(...)        │        ▼WorldDirtyTracker        │        ├─ Geometry dirty        ├─ Occupancy dirty        ├─ Shadow-page dirty        ├─ GI-probe dirty        └─ Light-cluster dirty
9. GPU Voxel World Database
9.1 目的
Minecraft mesh 适合 rasterization，但不是世界空间光照查询的理想 representation。VoxelLight 应并行维护一套紧凑、只服务 lighting 的 voxel representation。
9.2 Section 基础数据
VoxelSectionGPU {    ivec3 sectionPos;    uint occupancy[128];    // 4096 bits = 512 B    MaterialRef surfaceMaterial[]; // sparse / compressed    uint emissiveMask[128];    uint version;    uint flags;}
16×16×16 section 共 4096 voxel；仅 occupancy 使用 bitset 时每 section 约 512 B。材质数据不应朴素地为所有体素保存完整 PBR struct，而应采用 palette / surface-only / compressed representation。
9.3 Hierarchical Occupancy
L0 : 1×1×1 block occupancyL1 : 2×2×2L2 : 4×4×4L3 : 8×8×8L4 : 16×16×16 section summary
该层级是共享基础设施，同时服务 Probe GI ray traversal、可选 sun visibility、AO 辅助和未来 reflection/RT fallback。禁止为不同效果重复构建空间层级。
10. Extended GBuffer
优先让 Minecraft 原生 geometry path 继续产生场景；VoxelLight 只扩展 lighting 所需数据。建议先做最小 GBuffer，避免一开始大量增加带宽。
Target
建议格式
内容
备注
Depth
native / D32
scene depth
优先复用原生 depth
G0
RGBA8
albedo + flags
可根据资源包模式调整
G1
packed / RGBA16F
normal + roughness
normal 可 oct encode
G2
R16UI / packed
material/emission index
可选
Velocity
RG16F
motion vector
Temporal pipeline 必需

11. 核心模块一：Cached Directional Shadow Clipmap
为什么 Shadow 是 P0 核心：传统 Minecraft 光影常为了太阳阴影额外重绘大量 terrain。VoxelLight 的第一性优化不是提高 shader 算力，而是利用“世界大部分不变”减少重新渲染的 shadow work。

11.1 Clipmap
级别
示例覆盖
空间精度
典型更新频率
L0
0–64 blocks
最高
2–4 frames / dirty immediately
L1
64–128
高
4–8 frames
L2
128–256
中
8–16 frames
L3
256–512
低
16–32 frames
L4
512–1024+
最低
低频/按页调度

覆盖范围和更新频率不是固定常数，而是动态质量控制器的预算参数。近处 dirty page 可立即更新，远处只要求时间上稳定。
11.2 Shadow Page Cache
ShadowPage {    WorldAABB worldRegion;    uint clipLevel;    uint atlasPage;    uint contentVersion;    uint lastUpdateFrame;    DirtyReason dirtyReason;    float priority;}
页更新触发来源：camera clipmap shift、相关 section 改变、sun angle 超过阈值、资源重载。动态实体不应污染 static terrain page cache。
11.3 Dynamic Shadow Overlay
Player、mob、boat、minecart、动态 BlockEntity 使用独立 dynamic layer。最终 visibility 合成 static terrain shadow 与 dynamic shadow，避免一只实体移动导致大范围缓存失效。
12. Ambient Occlusion
屏幕空间部分：Half-resolution GTAO + bilateral upscale。
世界结构部分：利用邻接 voxel/block 状态提供低成本 Micro-AO。
二者合成而不是大量 voxel rays per pixel。
GTAO 使用统一 TemporalResolve，不单独发明 temporal framework。
13. Local Lights 与 Emissive Aggregation
Minecraft 原版 block light 可以作为光源发现和重要性先验，但不直接等同于最终高质量 direct lighting。
Minecraft Block/Resource Data        │        ▼Emissive Detector        │        ├─ torch / lantern / lamp        ├─ lava / large emissive region        └─ modded emissive material        │        ▼Light Aggregator        │        ▼GPU Light DB        │        ▼Clustered Lighting
连续熔岩面、大片发光方块必须进行 cluster/area-light 近似，禁止一个 emissive voxel 直接对应一个 point light。Screen-space clustered lighting 将复杂度限制为每个 cluster 的 nearby lights。
14. 核心模块二：Cascaded Radiance Probe GI
14.1 选择 Probe GI 而非 per-pixel GI
Minecraft 的几何和光源变化速度通常远低于帧率，因此 world-space radiance cache 比每像素每帧重新追踪更符合场景统计特征。
Cascade
示例 probe spacing
示例维度
用途
C0
4 blocks
32³
近景细节
C1
8 blocks
32³
中距离间接光
C2
16 blocks
32³
远距离低频环境光

每 probe 保存低阶 SH radiance、validity、age、variance 等元数据。FP16 优先。实际 cascade 数量和 spacing 必须由显存/时间预算决定。
14.2 Progressive Update Scheduler
priority(probe) =    w0 * nearCamera  + w1 * dirtyRegion  + w2 * lightingChange  + w3 * variance  + w4 * age
每帧只更新有限数量 probes，例如 512–2048，而非全量。
每 probe 仅发 8–32 条低差异/蓝噪声方向 rays。
在大片空区域通过 hierarchical occupancy 快速跳跃。
历史 radiance 通过 temporal accumulation 融合；block change 只 invalidate 局部。
15. Temporal-first Rendering Framework
Temporal 不是一个最后加上的 TAA pass，而应是所有低频效果的共同基础设施。
输入历史
主要消费者
Previous Color
TAA / upscaling
Previous Depth + Normal
history validation / disocclusion
Velocity
reprojection
Previous AO
GTAO accumulation
Previous GI
GI stability
Previous Volumetric
low-res volumetric reconstruction
Previous Reflection
SSR/reflection stabilization

统一 history validation
统一 neighborhood clamp
统一 disocclusion detection
统一 reset 事件：teleport / dimension switch / resource reload / camera cut
16. Volumetrics、Cloud 与 Reflection
16.1 Volumetrics
1/4 resolution 或 checkerboard。
蓝噪声抖动 + temporal accumulation。
Sun shadow 只采样必要层级，不重新建立独立世界 shadow representation。
God rays、fog、cloud atmosphere 共享低分辨率 volume history。
16.2 Reflection
第一阶段采用 SSR + sky/probe fallback，只对 water / glass / high-reflectance material 运行。未来有 ray query 时，可以形成 SSR → hardware ray query → probe/sky fallback 的层级策略。
17. Render Graph
Minecraft Geometry / Depth          │          ├────────── Shadow Page Updates          │          ├────────── Voxel DB Incremental Update          │          ▼     Extended GBuffer          │          ├── Clustered Light Culling          ├── GTAO          └── Probe Update (budgeted)          │          ▼     Direct Lighting          │          ▼     Indirect Lighting          │          ├── Volumetric          └── Reflection          │          ▼     Transparency / OIT integration          │          ▼     Temporal Resolve          │          ▼     Upscaling / Tonemap          │          ▼       Minecraft UI
如果 Blaze3D 后续提供更完整的 RenderGraph/compute abstraction，应优先迁移到官方能力。自建 RenderGraph 仅封装 VoxelLight 自己的 pass dependency 和资源生命周期，不与 Minecraft frame lifecycle 竞争。
18. 动态质量与性能预算
18.1 Frame Budget
所有视觉模块必须有明确 GPU 预算。以下值是 1080p Balanced 的工程目标，不是预先承诺的 benchmark。
模块
目标额外 GPU 时间
超预算时优先降级
Shadow incremental update
≤ 1.0 ms
page updates / resolution / frequency
Clustered lighting
≤ 0.7 ms
cluster granularity / light cap
GTAO
≤ 0.5 ms
resolution / directions
Probe GI update
≤ 1.0 ms
probes/frame / rays/probe
GI resolve
≤ 0.5 ms
resolve resolution / SH order
Volumetric
≤ 0.8 ms
steps / resolution / checkerboard
Reflection
≤ 0.7 ms
SSR steps / resolution
Temporal + post
≤ 0.8 ms
filter quality / render scale

18.2 Dynamic Quality Controller
Target GPU Frame = 8.3 ms (120 FPS) / 16.7 ms (60 FPS)if GI > giBudget:    probesPerFrame ↓    raysPerProbe ↓if Shadow > shadowBudget:    farPagesPerFrame ↓    farClipResolution ↓if totalFrame > target:    renderScale ↓ (last resort)
调整应带 hysteresis，避免画质参数每帧振荡。Quality Preset 只定义预算上下限，真正运行时由 profiler 反馈控制。
19. CPU/线程与同步
Game / Extraction Thread        │        ▼WorldSceneBridge        │        ▼Lock-free / bounded update queues        │    ┌───┴─────────────┐    ▼                 ▼CPU workers       Render threadvoxel encode      command recordinglight aggregate   frame graph        │                 │        └──── GPU upload ─┘
Game thread 不执行 GI preparation、occupancy rebuild 或 shadow page raster preparation。
GPU data 使用 persistent arenas / suballocation / ring staging。
正常帧中禁止依赖 vkDeviceWaitIdle 类全局同步。
Resource reload、dimension switch 等重事件允许受控 flush，但必须可观测并记录 profiler。
20. Mod / Resource Pack 兼容策略
v0.2 的最大收益之一，是复杂 geometry 继续走 Minecraft renderer。VoxelLight 只需要为 lighting 建立兼容层。
对象
策略
Vanilla opaque terrain
完整 VoxelLight lighting
Slab / stair / common block model
完整 lighting；occupancy 可保守近似
Leaves / grass
cutout lighting path
Water / glass
原生/OIT geometry + VoxelLight transparent lighting integration
BlockEntity / custom entity
原生 geometry；使用 GBuffer/material fallback
未知 Mod material
默认 dielectric + no emission
自定义 emissive material
Material Extension API
无法 voxelize 的复杂 geometry
不进入 occupancy；依赖 depth/GBuffer 和动态 shadow fallback

21. Material Extension API
interface VoxelLightMaterialProvider {    MaterialInfo query(BlockState state, ResourceLocation model);}MaterialInfo {    float roughness;    float metallic;    vec3 emission;    float transmission;    MaterialFlags flags;}
API 必须是可选增强，而不是 Mod 必须适配才能显示。未知材质采用安全默认值。
22. Debug 与 Profiler
指标
必须可视化/记录
GPU frame / pass time
是
CPU extraction / update time
是
Visible terrain sections
是
Shadow pages updated / reused
是
Dirty regions / section versions
是
Probe updates / rays / traversal steps
是
Light count / cluster occupancy
是
GPU memory per subsystem
是
Temporal rejection rate
是

建议提供 F3 风格 VoxelLight Debug HUD，并可导出 CSV/JSON benchmark。核心效果还应提供 overlay：shadow page、clip level、dirty region、probe validity、occupancy mip、light clusters。
23. Benchmark 设计
场景
主要压力
A. Plains
baseline / overhead
B. Dense Forest
cutout / shadow / overdraw
C. Large City
geometry / occlusion / shadow cache
D. Cave
local lights / GI
E. Torch Stress
clustered lighting / emissive aggregation
F. Rapid Block Updates
dirty-region / cache invalidation
G. Flight / Elytra
clipmap streaming / temporal disocclusion

每次性能评估必须至少记录：平均 FPS、1% low、CPU frame、GPU frame、VRAM、各 pass GPU timestamp、shadow reuse ratio、probe update count。
24. 分阶段开发路线
阶段
范围
Architecture Gate
P0 — Native Vulkan Integration
版本适配层、GBuffer、GPU profiler、debug overlay
能稳定挂接原生 Vulkan 渲染流程，不破坏 vanilla scene
P1 — Shadow
3-level clipmap、page cache、dirty tracker、temporal shadow
相同视距/近似质量下 shadow cost 明显低于传统每帧 shadow redraw
P2 — AO + Local Lights
GTAO、emissive DB、clustered lighting
大量火把场景仍满足预算
P3 — GPU Voxel DB
section bitset、occupancy hierarchy、incremental upload
大世界更新成本稳定，无全量 rebuild
P4 — Probe GI
probe clipmap、scheduler、hierarchical traversal
GI 在运动中稳定且满足预算
P5 — Volumetric / SSR
低分辨率 temporal volumetric、SSR
完整画质模式仍保持可控 frame budget
P6 — Upscaling / DRS
FSR/XeSS/DLSS 视能力接入
1440p/4K 有稳定 performance mode
P7 — Optional RT
ray query / RT reflection / probe tracing acceleration
非 RT 路径保持一等公民

25. P0/P1 可直接交给 Agent 的任务拆解
25.1 P0：Native Vulkan 接入
建立独立仓库模块：core、minecraft-adapter、debug。
确认 26.3 Fabric 映射下可稳定访问的 Blaze3D / RenderPipeline / RenderState 接口。
建立 MinecraftRenderBackend capability 表，不允许核心模块直接引用版本类。
插入最小 post/lighting test pass，验证 Vulkan 与 OpenGL fallback 行为。
实现 GPU timestamp profiler；任何后续模块必须先可测再合入。
建立 automated smoke scene：启动世界、切换维度、resource reload、resize、fullscreen。
25.2 P1：Shadow Prototype
实现 camera-centered 3-level directional shadow clipmap。
实现 ShadowPage atlas、world-region mapping 与 LRU/priority scheduler。
WorldDirtyTracker 将 section changes 映射到受影响 shadow pages。
Static terrain cache 与 dynamic entity overlay 分离。
实现 temporal shadow stabilization 和 camera-cut reset。
加入 debug view：clip level、page age、dirty reason、update count。
与 Vitrail/Iris 类传统 shadow pass 做同场景 benchmark；比较 GPU time，而不是只比较 FPS。
26. 推荐代码结构
voxellight/├── core/│   ├── backend/│   ├── rendergraph/│   ├── profiler/│   └── temporal/├── minecraft-adapter/│   ├── blaze3d/│   ├── renderstate/│   ├── worldbridge/│   └── nativevulkan/        # optional, version-isolated├── world/│   ├── DirtyRegionTracker│   ├── VoxelSectionDatabase│   └── LightDatabase├── gbuffer/├── shadow/│   ├── ShadowClipmap│   ├── ShadowPageCache│   └── DynamicShadowLayer├── voxel/│   ├── OccupancyEncoder│   ├── OccupancyHierarchy│   └── Traversal├── lighting/│   ├── directional/│   ├── clustered/│   └── material/├── ao/├── gi/│   ├── ProbeClipmap│   ├── ProbeScheduler│   └── ProbeTracer├── volumetric/├── reflection/├── upscale/└── debug/
27. 明确禁止的架构退化
不要为了某个效果方便而让模块绕过 WorldSceneBridge 直接遍历 Minecraft world。
不要为 Shadow、GI、AO 分别建立不同 voxel world database。
不要默认每帧重绘完整 shadow world。
不要在 P0/P1 阶段实现 full path tracing。
不要把 Native Vulkan Extension 扩张成另一套 device/swapchain/renderer。
不要因一个 Mod 不兼容就持续加入 per-mod patch；先设计 material/fallback abstraction。
不要只看平均 FPS；GPU pass cost、1% low、cache reuse、memory 和 CPU extraction 同等重要。
28. 主要风险与缓解方案
风险
影响
缓解
Mojang Vulkan internals 快速变化
版本更新频繁破坏 mixin/调用
adapter + capability 层；核心模块禁止依赖版本类
Blaze3D 暴露能力不足
难实现 compute/storage 高级算法
最小 NativeVulkanExtension，而非替换 backend
复杂 Mod geometry 无法 voxelize
GI/visibility 与 raster geometry 不一致
保守 occupancy + depth/GBuffer fallback
Shadow cache 因太阳移动失效率高
收益不足
page scheduler + angle threshold + temporal reprojection
Probe GI ghosting / leaking
视觉问题
visibility moments、normal/depth checks、dirty invalidation、probe relocation 后续加入
显存增长
高视距下压力大
sparse section allocation、palette、ring eviction、可视半径限制
Compute 与 graphics 争用
async compute 反而变慢
默认串行；profile 证明有收益后再 overlap

29. v0.2 MVP 验收标准
仅安装客户端 Mod 即可运行，不要求服务端插件。
Minecraft 26.3 Vulkan backend 下稳定启动、切世界、切维度、resize、resource reload。
不替换 terrain/entity renderer 即可获得 VoxelLight directional lighting + shadow。
Shadow clipmap 可视化明确显示 page reuse，而不是每帧全量更新。
在固定 benchmark 中输出可复现实验数据。
关闭 VoxelLight 后可以恢复原生渲染；出现 capability 缺失时可安全降级。
P1 Architecture Gate 通过前，不进入 Probe GI 大规模开发。
30. 最值得优先实验验证的问题
优先级
实验问题
原因
P0
官方 terrain/depth 能否以低侵入方式扩展出所需 GBuffer？
决定是否需要更深 renderer hook
P0
Blaze3D 是否足够表达 compute/storage buffer 工作流？
决定 NativeVulkanExtension 规模
P1
Shadow page cache 在 day/night sun movement 下实际 reuse ratio 多高？
决定核心性能收益
P1
Clipmap shadow vs 传统 shadow map 的 GPU bandwidth / raster cost
验证项目第一性假设
P3
Occupancy hierarchy 的 traversal steps / cache behavior
决定软件 voxel GI 是否高效
P4
Probe update 数量与运动场景质量的 Pareto 曲线
决定 GI budget

31. 当前实现依据与参考
Minecraft 26.4 Snapshot 1 — Vulkan becoming the default graphics API — https://www.minecraft.net/da-dk/article/minecraft-26-4-snapshot-1
Minecraft Java Edition 26.3 technical changes / OIT / multi-draw shader refactor — https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3
Fabric Docs — Basic Rendering Concepts 26.2 / Blaze3D and RenderState — https://docs.fabricmc.net/develop/rendering/basic-concepts
Vitrail Shaders — OptiFine-format packs on Minecraft native Vulkan renderer — https://github.com/avpbynf/Vitrail-Shaders
Radiante — path-traced rendering sharing Minecraft native Vulkan device (example of deep native integration) — https://github.com/Gabrieli2806/Radiante-26.3-Fabric
32. 最终架构决策
一句话定位：VoxelLight 不是新的 Minecraft Vulkan Renderer，而是运行在 Mojang 原生 Vulkan Renderer 之上的 Voxel-aware Lighting Engine。

最终优先级：Native Renderer 复用 → Cached Shadow → GPU Voxel DB → Probe GI → Temporal/Volumetric → Optional Hardware RT。
如果 P1 无法证明 cached shadow 在相同质量下具有明显的 GPU/CPU 优势，应暂停继续叠加 GI 和视觉效果，重新评估 scene representation、page invalidation 与 render integration。这是本项目避免走回“效果越来越重、性能越来越差”路径的首要架构纪律。
