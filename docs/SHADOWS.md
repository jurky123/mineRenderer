# P1a/P1b 地形阴影与 tile 缓存（0.8.0）

局部地形阴影实验：默认使用当前 native SkyRenderState sun/moon，sun fixed 保留归一化方向 `(0.6, 1, 0.35)`，局部地形 caster，默认复用三个世界 anchor 的 overlapping cascade map，再合成方向阴影、月光 fill 和 emissive-block 局部灯。没有虚拟 shadow page/滚动 clipmap、动态实体层或 GI。动态层、远处 caster、真正太阳光分离和性能验收仍未完成。

## 数据路径

`mode shadow` 自动开启原有 scene。WorldSceneBridge 提供最多 343 个局部 section 的 world/resource/geometry token；客户端模型编译不再等待独立 occupancy 快照。ShadowRenderer 创建自有 SectionBufferBuilderPack，调用 vanilla SectionCompiler(false, true, ...)；关闭 AO、启用 cutout leaves，使用现有模型/材质 atlas。solid/cutout 保留原模型顶点与 UV，translucent layer 不进入 caster 集；不是 full-cube occupancy 替代。原 CPU occupancy 数据仍可用于 scene inspect。

新增驻留每帧至多编译/上传一个 section；已驻留的编辑组最多 8 个 section 同帧替换，空 section 无顶点。先要求邻域九个 chunk 已加载，再在客户端线程通过 RenderRegionCache 复制真实邻域并同步编译，worker 不访问新引入的实时模型/tint 数据。此路径会出现 CPU 暖机开销，不承诺帧时间预算；需要在实机记录 lastBuildNs。自有 mesh 不进入 vanilla 的编译队列或可见列表。

相机可见性不参与 caster 选择，因此已加载局部窗口内的画面外模型也能投影。在至少有一个 caster 条目准备完成后绘制当前有效集合，不再因为未知 section 的投影范围抹掉已有阴影：缺失几何只能增加可能的遮挡，不能否定已确认的遮挡。不复用失效旧几何，因此真正缺失的 caster 仍可能暂时漏影。全新世界/reload 初始准备仍可能短暂恢复 vanilla，未加载邻居/快速移动可持续出现局部不完整范围；status 显示 partial coverage 与 casters=M/N。

geometryVersion 在 LOAD/GEOMETRY/RESOURCE 变化时递增，LIGHT 单独更新不失效模型几何；block 边界、section neighbors 与 chunk load/unload 使相邻 exposed faces 一起失效。编辑小组在同一次 prepare 内先构建替换，验证全部 token 当前后统一移交；大组/失败立即移除旧 caster；streaming geometry 预算不足时有 token/字节 deferral，优先保留更近的有效几何。token 独立于 CPU 快照，编译发布前再次核对。世界/资源变化与卸载重载不能复用同坐标旧模型。失效后不复用旧 shadow map。

## GPU pass

光相机位于 8 格世界 cell anchor 的光源方向 128 格处，以当前相机相对坐标上传，三层正交范围 ±32/±64/±96、near=1/far=256，D32_FLOAT depth 清为 1、LESS_THAN_OR_EQUAL，双面绘制。方向接收距离默认 48 格，在 40–48 格平滑淡出；12–16 和 26–32 格重叠混合，人工灯仍 24 格。caster 来源为 7×7×7 局部窗口；更远的遮挡物不会产生近处影子，未承诺整个视距的覆盖。

26.2 builder 自动声明一个 color slot；当前配 R8_UNORM、禁用全部 color write 的 attachment，shadow shader 不写颜色。cutout 的 alpha cutoff=0.5，采样当前 block atlas，包括动画材质；主世界的 depth/颜色完全独立，不修改游戏 projection、编译缓存或 framebuffer owner。参考模式每帧全图清空重绘；默认路径仅清空重建脏 tile，cutout 投影覆盖的 tile 每帧更新。

resolve 先复制 scene color 避免 read/write alias，采样原 reversed-Z scene depth，用当前 Projection inverse 和 camera view rotation inverse 恢复相机相对世界位置。0.3.1 将光相机原点以双精度吸附到 texel 网格；0.3.2 从相邻 scene depth 重建同表面法线，以光矩阵逆转置得到 receiver-plane 梯度，避免屏幕导数跨模型边缘；当前连续 5×5 comparison PCF 对最多 6×6 个实际 texel 中心使用此深度修正和小常量 bias；以 receiver 的 subtexel phase 对比较结果加权，权重总和为 25，不插值原深度，再合成阴影/月光/局部灯，天空/范围外不变；手与 HUD 在这个 hook 之后绘制。shadow_mask 输出白=无遮挡/范围外、黑=遮挡；shadow_map 显示光相机 depth（空白为白色）。

## 资源上限

| 资源 | 上限/回收 |
| --- | --- |
| 三张 D32 map（2048²、1024²、1024²） | 24 MiB |
| 三张同尺寸 R8 写禁用 attachment | 6 MiB |
| 自有 caster 顶点 | steady 32 MiB，每 section 4 MiB；replacement staging 最多额外 8 MiB、live owned peak 40 MiB；批量超限退回暖机，streaming 超限保留有效 caster 并记录 deferred，不关闭整幅画面 |
| shadow settings | caster 3×160 + resolve 304 字节，GPU COPY_DST uniform；无 CPU 映射当前在途内存 |
| scene color scratch | 当前分辨率×4 字节；与 color 模式同一 owner |
| caster 编译 | 新增每帧一个/至多 4 MiB，或编辑 replacement batch 最多 8 section/8 MiB；两者本帧不叠加，不是 CPU 硬时间上限 |
| CPU builder | 一份 vanilla pack（初始约 9 MiB），复杂模型可增长；关闭时回收 |
| quad index buffer | 借用游戏 sequential buffer；最大单 section index count 有界，不由 mod 销毁 |

不把 Java 对象、模型缓存、临时编译数据、游戏 staging/backend 延迟销毁计入 GPU payloadBytes。关闭 mode、resize、resource reload、切世界、shutdown 清理自有 GPU/CPU caster 资源；scene 有独立开关。性能未知，不能依据 32 MiB 顶点上限推断帧率。

CPU/GPU CSV 覆盖颜色复制、map draw、resolve。caster 编译/上传不在这个 timestamp 区间，status 的 lastBuildNs/uploadBytes 单独说明最近编译 CPU/当帧上传；完整 benchmark 系统仍在计划中。

## 当前画质边界与验证

这是已有 vanilla 颜色的乘性调暗，还没有未光照 albedo/独立太阳光项；fog、block light、发光色也可能一起被调暗。scene depth 不包含所有透明表面，不能正确处理水/玻璃 receiver。天空不会修改；实体/箱子等单独渲染模型暂不产生影子。默认 world light 随原生 sky 角度、月相和雨天改变；fixed 模式保持旧策略。world 模式无足够 celestial elevation 或非 OVERWORLD skybox 时关闭 directional shadow/moon，仍可运行 local lights；vanilla 原有 lightmap 保留。

自动测试：scene/caster version、旧快照、世界/资源 generation、光源投影/深度比较、大坐标/reversed-Z 重建；最终 JAR 的 shadow pipeline attachment/depth/topology，以及 Minecraft GLSL→SPIR-V/实际 uniform 与 stage IO 绑定。不创建 GPU device，不能证明 raster 输出。

实机验证按 [INSTALL.md](INSTALL.md)：柱子、画面外 caster、边界编辑、半砖/栅栏/树叶、mask/map、reload、切维度、resize/fly、off。用户已验证的 0.2.0 normal/scene/reload/下界切换单独记录在 [INTEGRATION.md](INTEGRATION.md)，新增阴影仍待实机确认。通过此局部参考后再扩展动态层与覆盖，并按同一 caster/质量建立缓存对照。

## 0.3.1 用户截图回归

用户提供的 11:16:56 彩色图与 11:17:41 mask 在大面积平面出现条纹/三角网格，属于明显 self-shadow acne；11:17:29 light-camera 图已包含城堡模型。随附 latest.log 为零字节，不能据此推断任何运行错误。

旧 PCF 把每个相邻 texel 的深度都与中心 receiver 深度比较，忽略了斜面在 texel 内及 PCF 邻域的深度变化；小 angle bias 不能覆盖此误差。新 resolve 用 shadowUv/z 导数求平面梯度，在实际 nearest texel 中心修正 receiver 深度，再减 0.00012 bias。模型边缘/掠射角梯度限制仍有近似误差，新画面需要实机重测。

旧 prepare 在任何 section 缺失时关闭整帧效果，跨 section/编辑可导致全局闪灭。新实现使用上述局部未知列抑制；shade/mask/map 模式切换保留 caster。自动回归覆盖旧比较在合成斜面产生 acne、修正保留真正 upstream blocker、相机 texel 稳定性（含 ±3000 万坐标）、局部缺失不影响范围外/upstream receiver，以及 SPIR-V 反射确认上传 UBO 大小。

## 0.3.2 移动/视角回归

11:35:55 与 11:36:10 新截图显示地面 acne 已改善，台阶侧面和边缘仍有三角条纹；用户确认部分阴影在移动、某些视角消失。移除未知 section 范围的过度遮挡抑制，保留有效 caster 的确定阴影；用深度邻域重建法线和光空间平面修正替代屏幕导数。自动测试覆盖多相机方向下平面深度一致、真实 upstream blocker 保留，以及 SPIR-V 实际 160 字节 uniform。有限窗口、16–24 格淡出和真实 caster 重建漏影仍存在；本环境不能验证最终 GPU 图像。

## 0.4.0 单层缓存基础

投影中心按世界 XYZ 的 8 格网格取 floor；lightMatrix(anchor) 仍进行双精度光空间 texel snapping，再用 camera-anchor 差值表达当前相机相对坐标，避免巨大世界坐标转 float。XY 与 depth 都固定在世界中，不能仅固定 XY 而复用随相机移动的旧 depth。参考/缓存模式使用相同 anchor，不降低缓存质量来换取复用。

cache key = anchor XYZ + 单调 caster 集 revision。有效 mesh 增加、删除/失效递增 revision；新 map pass 记录成功后才发布 key。close 清除 key 和计数，off/resize/world/reload/error 沿用既有清理路径。主相机旋转不影响 caster 选择。所有 cutout mesh 保守每帧重绘，避免 atlas 动画 alpha 更新没有几何版本事件而导致旧影子；solid 不做 alpha cutoff，与原生 solid 分类一致。自有 ChunkSection UBO 的 ChunkVisibility 仅作为 0/1 cutout flag，不修改原生 UBO。

`shadow_cache off` 每帧重绘，`on` 默认缓存，切换清空缓存计数/计时；status/export txt 报告 mapRenders/mapReuses/mapReason/mapSize。缓存重用帧 casterDraws=0，resolve/color copy 仍执行。更新后等待动画分层、三层 clipmap/分页、动态 caster、太阳运动及性能验收，不能宣称完整 P1b 已完成。当前零显示设备，只验证 CPU cache 和 shipped GLSL/SPIR-V binding，不宣称实机收益。

## 0.5.0 选择性 tile 更新

继续采用单张 dense 2048² map，不新增 texture、history、virtual page table 或 shader indirection。逻辑上划分为 8×8 个 256² tile，dirty BitSet 上限 64，矩形合并最多 64 个区域；本帧所有脏页立即绘制，没有延期页/时间预算。初次初始化、8 格世界 anchor 跨界、关闭缓存和生命周期 reset 全更新为一个矩形。

CasterBounds 从实际上传的 BLOCK float3 Position 顶点提取 world double AABB，再合并 solid/cutout；保留独立 cutout bounds。覆盖扩展到 section 外的模型，不用固定 section cube 猜测。BLOCK stride/position offset/format 通过 shipped pipeline 契约测试锁定。取八角点正交投影、两 texel raster guard、map 裁剪；移除先标记旧 bounds，新增标记新 bounds，空 mesh/图外模型不失效可见页。cutout 每帧仅标记其足迹。

每个脏矩形以 RenderPassDescriptor.renderArea 限制 depth clear 与初始 scissor；26.2 VulkanRenderPass 的 viewport 仍使用完整 attachment 尺寸，不缩放 tile 的投影。Vulkan loadOp CLEAR 的范围是 render area，STORE 保留结果，游戏 encoder 在 pass 结束时建立 memory barrier；不使用独立 queue 或手工 submit。依据：[Khronos attachment load operation](https://docs.vulkan.org/refpages/latest/refpages/source/VkAttachmentLoadOp.html)、本地 26.2 VulkanCommandEncoder/VulkanRenderPass 源码，以及 viewport 构造的字节码契约测试。

清空之后必须重绘所有当前 footprint 与矩形相交的 mesh，包含未变化的 mesh；不能只画新 block，否则移除前景 caster 会抹掉原本在其后的遮挡。记录 clear/draw 成功后才将该区域标记干净；异常沿用关闭/清理路径。旧几何仍立即撤销，section 暖机缺失仍可能局部漏影，不能宣称无闪烁。

status/export txt 新增 updatedPages=N/64（最近帧）、pageUpdates/pageReuses（本次累计）、updateRegions（最近帧）；mapRenders/mapReuses 仍统计有/无 map 更新的帧。page 数是更新区域占比，不是 GPU 时间收益，密集草木或多邻居编辑可能更新全部图。CPU 软件深度参考覆盖新增/移除重叠 caster、远 caster 显露、脏 tile 合并；GPU 图像、边界 PCF 与性能仍需 cache on/off 的实机比较。三层 clipmap、预算/截止、动态 caster 和太阳运动仍未完成。

## 0.5.1 同帧编辑替换与 light packet 分类

旧路径先销毁过期 mesh，然后等待 worker 的 occupancy snapshot，再每帧只编译一份，造成修改 section 中未改变的 caster 也一起暂时消失。新几何 token 直接包含 world/resource generation 与 geometry version，模型从主客户端线程已加载的世界 RenderRegionCache 抽取；CPU scene 编码仍保持旧线程/队列边界。

准备时先移除窗口外/卸载 mesh；若过期驻留组不超过 8 且邻域已加载，用新 GPU buffer 分别构建 complete replacement group。预算同时检查 section 4 MiB、steady 最终 32 MiB、staging 8 MiB、live owned overlap 40 MiB。全部 token 再检查后，在任何 shadow draw 前交换所有 group mesh，标记旧/新 footprint 并关闭旧 buffer。旧 geometry 只保留在这一同步 prepare 调用内部，不跨帧显示陈旧数据。过大的组/缺邻居/预算/被覆盖版本不发布半个组；清理 staged buffer、移除仍过期的 mesh，保留原安全暖机路径。尝试构建 batch 的帧不另做一份 streaming upload。

本地 26.2 ClientPacketListener.readSectionList 是 light packet 数据应用路径，会调用 ClientLevel.setSectionDirtyWithNeighbors；旧 ClientLevelMixin 把此调用误当 GEOMETRY，扩展成 27 个邻居 section 重建。新增 WrapOperation 只包裹这一特定调用，ThreadLocal try/finally scope 将 section rebuild reason 分类为 LIGHT，原 vanilla 调用照常执行。实际 setBlocksDirty/section geometry 更新在 scope 外不变，原 ClientChunkCache.onLightUpdate 也仍走 LIGHT。nested scope/异常/其他线程不能泄漏分类；最终 JAR selector/调用点测试锁定目标。没有取消 vanilla lighting 或更改原生 mesh owner。

replacementBatches/replacementFallbacks、lastReplacementSections/replacement、peakBuildNs 出现在 status/export txt；常见单块编辑应走 same-frame edit replacement，但局部刷新、CPU frame spike 与极端编辑行为仍需实机确认。未实现后台模型编译、无限 edit batching、dynamic entity caster、太阳运动或完整 frame benchmark。

## 0.6.0 变化 celestial light（历史；当前连续方向见下文）

从当前 GameRenderer.gameRenderState.levelRenderState.skyRenderState 获取 skybox、sunAngle/moonAngle、rainBrightness、moonPhase；不是按 dayTime 猜测角度，兼容原生环境属性/timeline 的插值结果。本地 SkyRenderer 的 celestial pose = rotateY(-90°) × rotateX(angle) × translate(0,100,0)，对应 light direction = (-sin(angle), cos(angle), 0)。月亮使用自己的 native angle（而非硬编码 sun+180°）。固定 light 的投影/强度维持旧行为。

ShadowLight 选取具有最大当前强度的单个 sun/moon source；sun base 0.45，moon base 0.12 × phase fraction（full=1/new=0），weather multiplier=0.25+0.75×rainBrightness，elevation smoothstep(0.10,0.25,y)。这是近距参考合成策略，不是物理天气/GBuffer direct-light 模型；不宣称匹配 shaderpack 或所有 skybox mod 的附加 pose 修改。角度取整到 0.025°，cache/ref 使用相同 key 与 orientation；最大偏差 0.0125°，没有旧方向和新 receiver matrix 混用。world strength 连续变化，不加入 cache projection key。

ShadowMapCache key 现在包含 anchor 与 source+angleStep。改变 angle/source 立即全页失效；tile partial clear/filter/caster selection 用同一 light matrix。矩阵采用恒定 world Z up，太阳/月亮在 XY 平面，正午不会出现 Y-up 的 lookAt 奇点或轴切换。fixed 仍采用旧 Y-up。双精度 anchor/texel snapping 与 camera-relative shader 保持，资源 bytes 不增加。near/far/receiver radius 不变，低太阳角淡出避免有限 caster window 误称远处完整覆盖。

world resolve 以已有深度邻域 world normal 定向朝相机，用 facing smoothstep(0.015,0.08,dot(normal,light)) 抑制背光/掠射 receiver 的额外暗化，避免正午垂直墙面的无限 plane gradient artifacts。fixed 分支不启用此新 gate。尚无真实 GBuffer normal/太阳光分离；opaque/cutout/透明 receiver 与 local coverage 限制仍在。

新增 sun world/fixed 命令切换只清 cache 与 pass samples，不释放 caster。status/export txt 包含 sun mode/source/angleDeg/strength；world 随时间全更新可降低缓存 reuse，这是正确 invalidation，不应套用固定太阳静止基准。自动测试覆盖 native pose 对齐、方向绕 noon 翻转、月相/雨天 strength-only 不失效、horizon/invalid inputs/noon finite matrix、角度误差界限、source/angle 全失效与大坐标相机移动；实际 GPU 图像/时间跳变/天空自定义仍待实机验证。三层 clipmap、页延期预算、动态 caster 和完整 P1b 验收仍未完成。

## 0.7.0 稳定性与局部灯路径（历史；最新见 0.8）

用户反馈 0.6 world shadow flicker、月光不可辨识；定位到 discrete render angle 与 rotating global-coordinate snap 的潜在相位跳变，以及 moon 只有颜色乘性调暗。当前方向保持 native float angle 的连续值，key 使用同一 normalized double angle，angleStep 不控制 projection；celestial projection 只用 anchor-relative 光矩阵，不按全局 30M world coordinate 取整。fixed projection 仍保留旧稳定 lattice。anchor 的每轴中心距离超过 8 格才移动，避免在边界往返反复换 map。近轴 normal (>0.98) 吸附到真实 block face，facing smoothstep(0,0.3)，bias=0.00025、5×5/36 comparison PCF 与 4 texel footprint guard。没有 temporal history；真正的 raster alias、薄模型、subpixel edge 和流式缺 caster 仍需实机检查。

moon full-phase strength 上限 0.22；沿方向阴影外加入 vec3(0.14,0.20,0.34) 冷色 fill，随遮挡、朝向、phase/rain/elevation 和 16–24 格 coverage 淡出。NONE 时普通 resolve 不读 shadow depth、不绘制 directional map；cache suspend 不发布脏页或伪计 reuse，恢复 sky light 时再正确失效。shadow_map 在 NONE 仍可绘制 legacy fixed basis 的 caster 诊断，不代表真实月光。

ArtificialLights 是新 CPU/GPU owner：client thread 用 geometry token 捕获已加载 section 的 live immutable state，isSolidRender 分类 full-block opacity，getLightEmission 分类发光体。与独立 CPU scene encoder 和 vanilla LIGHT packet 都不互相等待；每个 section 4³ spatial cells（每 cell 4×4×4 blocks）取最强 emitter 代表，固定 index tie-break，最多 64/section。颜色目前为显式名称族的艺术参数；未知 mod emitter 用中性色；不推断材质光谱。

resident edits 优先同帧最多 8 section 重新采集，其余失效区域立即 unknown；初始/new section 每帧一个。卸载/generation/reload 清除同坐标旧数据；使用桥的有效 geometry token 再发布。cell 数据保存在最大 125 section 集合；每次 geometry/window 变化生成 80³ cells、800×640 R8 atlas：atlas x=voxel.x+(voxel.y%10)*80，y=voxel.z+(voxel.y/10)*80。上传后 texture 不再改动直到下一变化；buffer 每帧更新相机相对的 origin/light position，整数世界坐标先作 double subtraction。

全局最多 16 active source slot，36 格候选范围确保接收 24 格+半径 12 格交集；importance=emission/(1+cameraDistance²)，已驻留获得 1.25 保留权重，同分按坐标。新增/淘汰以 0.25 秒 fade 限制 cap jitter；removed emitter 立即消失。shader 范围外/背面先跳过，Lambert+平方 falloff，再以最多 48 步 voxel DDA 测试 full-block 视线；endpoint emissive cell 自身不挡光，unknown/outside/full opaque 挡光。不是 screen-space trace，墙在屏幕外仍有效；不处理半砖/透明/实体的精细遮挡。附加亮度在已照亮 LDR scene color 上做 bounded fill；未分离 vanilla blocklight，不能称完整 point-light deferred renderer/GI。

新增预算：GPU opacity=512,000 字节，LocalLightSettings=560 字节；CPU section opacity ≤125×4096 字节，representatives ≤125×64，atlas=512,000 字节，临时 native upload=512,000 字节。每次变更完整上传 atlas（不是 incremental GPU voxel DB），native/game staging 和 backend delayed destruction 不算这些 owned 字节。更新读取至多 8×4096 个状态，不宣称 CPU 硬时间预算。close 在 mode/scene off、dimension/resource change 和 shutdown 路径回收自有 texture/view/buffer 与 CPU entries。

shadow/copy/resolve timestamp 现在包括 opacity texture upload 和局部-light shader；CPU extraction 在 prepare、query 之外，lightExtractNs 单独统计；atlas rebuild/record 时间 lastVoxelPrepareNs 在 query 区间的 CPU 路径。16×48 是每 receiver 最坏 trace cap，不代表已满足 6ms/FPS 目标；需要密集灯场景实机测量，再推进 finer materials/cluster/probe GI。

## 0.8.0 三层 overlap、视角稳定与 residency

默认方向影/月光 48 格，fade 最后 8 格；可设 12..48。cascade near/mid/far 的 (resolution, halfExtent, blend) 为 (2048,32,12..16)、(1024,64,26..32)、(1024,96,40..48)。每个 map 用独立 ShadowMapCache instance，tile=256：64+16+16=96 页，各自 bounds projection/clear/guard/ref/cutout；相同 current sun/moon/anchor/geometry。caller 不将 default legacy 单图 cache dimensions 用到外层；legacy math 留给历史回归测试。caster projection eye=128、near=1、far=256，bias 0.00014≈0.036 world blocks；receiver-plane 修正各自 inverse transpose。

三层 caster UBO 各 160 字节，避免在同一个 in-flight buffer 覆盖多个 cascade 的 matrix；resolve 的三 mat4 + inverse view mat4 + light vec4 + coverage vec4 + ranges vec4 =304 字节。opaque+cutout geometry 和 native quad index buffer 共用，不额外编译三个 mesh set。全方向变化/anchor/generation 仍立即绘制每层有效页；没有外层延迟更新或“虚拟分页”。纹理/UBO 由 ShadowRenderer 的 Cascade owners 管理，partial allocation failure 和所有 reset 关闭全部三层资源。

shader 按重建世界 radius（不是 view-depth/frustum）选择 map；overlap 最多比较两张，其他位置一张，36/72 comparison 上限。supported receiver spheres 在 maximal 8-per-axis anchor offset 下都处于各自 UV/depth guard 内，map 边缘另以 texel 距离做 smooth fade。世界距离/FOV/相机 yaw/pitch 的 CPU projection 契约有测试，不能当成 GPU 渲染验收。shadow_ranges 从同一 coverage/ranges uniform 绘制 near 绿/mid 橙/far 蓝，可把 range 切换与 normal/filter artifact 区分；shadow_map 改为 near/mid/far panels。

normal 路径读取每轴左右各两份 scene depth，以 abs(2*neighbor-firstSecond-center) 的 plane extrapolation residual 决定同面；硬件 depth 在 projected triangle 内为 affine。残差相近时用旧近-depth tie-break；接近 axis 时 smoothstep(0.94,0.995) 连续混合，去掉 0.98 的硬切换。薄模型/图像边缘/未知材质仍是 depth normal approximation，没有真正模型 normal 或 history。

scene token/palette 窗口扩大到 7³=343（bridge cap384），worker job/copy soft budget 不变。人工灯独立筛选 camera section±2，只保留最多 125 entries/80³ atlas，不因 directional distance 擅自扩大 source/trace 预算。geometry resident32 MiB/staging8 MiB/section4 MiB 不变：失败的 streaming 编译在 upload 前记录 nextBytes+geometryToken；只有版本/world/resource 改变或真实腾出足够空间才重试。优先级为 section 距离平方+坐标 tie-break，不受视角影响；只有严格更远/更低优先级、非空且能完整腾出空间的 caster 才可淘汰，不进行无收益的部分淘汰。evicted metadata 也保存，防止来回编译远近条目。budgetDeferred/budgetEvictions 与 partial coverage 可观察；budget pressure 不伪报全部 caster ready。

三层只扩展已知局部 caster 的投影/receiver，不保证 48 格每个 receiver 都获得来自更远未知 geometry 的阴影，尤其低角度天空；缺失几何只能失去潜在遮挡，不可保留陈旧 caster。shadow_range 设置只调整末端 receiver fade、清样本，不改 projection/filter/cache 内容。无 GI、动态 entity、滚动 clipmap/延期页预算或实际性能验收。

## 0.9.0 人工灯形状遮挡与增量上传

用户已确认 0.8.0 的阴影距离控制有效。0.9.0 保留三层方向阴影，在人工灯光线遍历中增加原生 BlockState 缓存 occlusion shape 的 AABB 相交测试。默认 `/voxellight light_occlusion shapes`；`/voxellight light_occlusion full` 恢复此前只检查 full-block 的参考行为，便于同场景比较。

测试：Vulkan 世界运行 `/voxellight mode shadow`，在火把与地面之间放置半砖、楼梯和栅栏，比较 shapes/full；观察开口透光和实体部分遮挡。随后放置/破坏遮挡块、移动跨 section、F3+T、切维度，检查无陈旧遮挡。status 中 shapeRows、shapeFallbacks、shapeOverflows、uploadRegions、voxelUploadBytes、shapeUploadBytes 显示实际资源/上传计数。

800×640 R32_UINT 网格使用 2,048,000 字节，32×1024 RGBA32_FLOAT 形状纹理使用 524,288 字节，总计 2,572,288 字节。最多 1023 种非空形状，每种最多 16 个框；复杂形状退化为包围框，形状表满时保守地当作整块遮挡。状态缓存最多 4096 条；世界/资源重置同时清空网格、形状 ID 和 GPU 资源。

局部编辑只上传受影响 section 的 atlas 区域；一个 section 为 16,384 字节。相同遮挡数据重新捕获不上传网格；超过 64 个合并区域或窗口原点移动时完整上传。形状表只上传新增行，旧 ID 不改写。仍为最多 16 盏灯、80³ 已加载方块窗口、每光线最多 48 次方块遍历，未知区域保守遮挡。

此阶段使用原生遮挡形状，不使用资源包模型或 alpha 贴图；noOcclusion 玻璃/植物不会新增形状阴影，框裁剪到所属方块的 0..1 范围，不表示超出方块的模型部分。没有动态实体灯光/阴影、GI 或物理材质输出。自动测试覆盖形状编码、保守容量回退、编辑/卸载/移动增量上传，以及 Minecraft 原生 GLSL→SPIR-V 和绑定布局；新画质和 GPU 耗时待实机验证。

## 0.10.0 动态实体模型阴影

用户已确认 0.9.0 shapes/full 的人工灯遮挡差异有效。本阶段推进 P1a/P1b 的动态层：为每个 cascade 增加独立 D32 深度层，每帧使用原生实体 renderer 的 interpolated state、模型动画、模型/部件 submit、pose 与 Sampler0 alpha 纹理生成自有 BLOCK 顶点。光照 PCF 每个 tap 使用 terrain/entity 的较近深度；动态实体移动不改变静态 terrain cache token 或 tile validity。实体层为空或关闭时清除已有动态深度；连续为空时复用空层。shadow_map 显示两层合成深度。

默认 entity_shadows on，`/voxellight entity_shadows off` 可同场景比较。优先测试白天平地的牛/猪/僵尸、行走动画、第三人称玩家；用 `/voxellight sun fixed` 暂停天空角度干扰。让实体离开镜头但其阴影仍进入镜头，确认不依赖主相机 frustum。随后移除/杀死实体、开关 entity_shadows、F3+T、切维度和重进世界，确认无旧姿态残影。status 查看 entityModels、entityUploadBytes、entityCaptureNs、entitySkipped、entityFailures、entityOverflow。大量实体测试需记录帧时间与 CSV，构建通过不表示 GPU 性能验收。

最多选择相机 64 格球内最近 32 个已加载、非 invisible/removed/spectator 实体，按距离与 entity ID 排序，选择内存固定 32 条，不请求 chunk。每帧最多尝试 128 个合格 model、总顶点 1 MiB、单模型原生 scratch 最大 256 KiB。几何超限/不支持类型会跳过，失败 renderer 撤销该实体已捕获的全部模型，记录错误且保留地形光照。超限时不保证所有实体投影；目前无选入/淘汰 fade 或动态层 temporal filter。

非混合、QUADS、带 Sampler0 的 native Model/ModelPart 可投影；原生 PlayerModel 使用 translucent skin pipeline，作为特殊情况按 alpha>=0.5 cutout 投影。其他 translucent 模型、粒子、火焰、leash、blob shadow、文本、held/item geometry、custom geometry 与方块实体不进入新层。模组自定义 renderer 的兼容性取决于是否使用此 native model submit 路径。不替换 vanilla entity/blob rendering；人工灯 DDA 仍只检测方块，不新增实体的人工灯遮挡。

三张 dynamic depth（2048²/1024²/1024²）增加 24 MiB，复用既有写禁用 R8 color attachment；总 shadow targets 54 MiB。固定 dynamic GPU vertex buffer 最多 1 MiB，CPU frame pack 1 MiB + scratch<=256 KiB；off mode/reload/world reset 释放自有资源。entity_shadows off 保留已分配资源并清空深度，避免开关重分配。存在动态模型时 PCF 每 tap 最多增加一次深度采样；空层通过统一 flag 跳过动态采样。模型捕获 CPU 时间在 GPU query 之前单列 entityCaptureNs；GPU pass timing 包括动态顶点上传、动态层 draw/clear 和合成。

本阶段没有完成页调度、滚动 clipmap、动态方块实体、完整 frame profiler/GBuffer 或 AO/GI。下一步先实机验证动态层与压力性能，再实现预算页更新或 AO。自动测试验证 nearest admission/overflow、native animated Model→BLOCK 顶点坐标与容量限制、实际 ENTITY pipeline 的 GLSL→SPIR-V 和 Vulkan stage binding。
