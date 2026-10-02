# Minecraft 26.2 接入记录

## 已锁定的基线

Minecraft `com.mojang:minecraft:26.2`，Java 25，Fabric Loader `0.19.5`，Fabric API `0.160.0+26.2`，Loom `1.17.21`，Gradle `9.5.1`。依赖实际解析与构建成功，26.x 使用官方未混淆类名。入口为 `com.voxellight.VoxelLightClient`，mod ID 为 `voxellight`；元数据只允许客户端和游戏 26.2。

源码通过 `./gradlew genSources` 生成并检查，没有将 Mojang 源码提交到仓库。26.3 的 RenderPearl 包名与 26.2 不同，当前代码统一使用 26.2 Blaze3D。

## 能力与使用位置

| 能力 | 26.2 符号/证据 | 当前状态 |
| --- | --- | --- |
| 世界 pass 之后、手之前 | `GameRenderer.renderLevel(DeltaTracker)` 内 `LevelRenderer.render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc, GpuBufferSlice, Vector4f, boolean)` 返回后注入 | 版本内部 hook；字节码检查唯一调用且位于 `clearDepthTexture` 前；0.1.2 深度视图获用户实机确认；其他生命周期待验证。 |
| 世界颜色与深度 | `GameRenderer.mainRenderTarget()`，`RenderTarget.getColorTexture/View()`、`getDepthTextureView()` | 编译通过；只在 hook 内使用当帧 vanilla 引用，不跨帧保留。 |
| 自有 pass | `GpuDevice.precompilePipeline(RenderPipeline, ShaderSource)`、`CommandEncoder.createRenderPass`、`RenderPass.bindTexture/draw` | 三个诊断 pipeline，加 caster/resolve/mask/map 参考阴影 pipeline。由游戏编译缓存持有；关闭 mod 不销毁游戏缓存。 |
| read/write alias 避免 | `CommandEncoder.copyTextureToTexture` | color/shadow 模式先复制到同尺寸/格式 scratch，再输出 main color；depth 直接采样 main depth，pass 无 depth attachment。 |
| Backend/深度约定 | `GpuDevice.getDeviceInfo().backendName/isZZeroToOne` | 仅 backendName=Vulkan、RGBA8_UNORM scene color 执行；raw depth 诊断采用 vanilla 清零的 reversed-Z。 |
| GPU timestamp | `createTimestampQueryPool`、`writeTimestamp`、`getValues`、`DeviceInfo.timestampPeriod` | 4 个独立 query pair，至少延迟两帧且结果可用后才复用；满则跳过计时，继续 draw。API 未暴露 valid bits，负差值拒绝。 |
| query readback 行为 | 生成的 `VulkanQueryPool.getValues` 使用 64-bit + availability，未设置 WAIT；`VulkanCommandEncoder.writeTimestamp` 先 host reset | 源码检查，不直接调用这些内部 Vulkan 类。用户已观察 timestamp available 与样本增长；准确计时/性能对比仍待验证。 |
| 销毁/同步 | Vulkan texture/view/query pool 的 `close` 交由 backend 延迟回收 | mode 切换、`GameRenderer.resize/resetData/setLevel/close` 释放自有资源；正常帧不 submit、不 waitIdle、不等待 query。 |
| reload | Minecraft 清理 pipeline cache 后，下一次 enabled pass 再 precompile；每帧重新取主目标 | shader 来自 mod 内置资源，不支持材质包覆盖；0.2.0 normal/scene 的 reload 获用户确认；0.3.0 caster/阴影的 reload 待验证。没有 GBuffer/history。 |
| MRT | `RenderPipeline.Builder.withColorTargetState(int, ...)`、`RenderPassDescriptor` | API 存在，尚未扩展 terrain 输出或验证 MRT 性能/画质。 |
| 独立 terrain caster | `SectionCompiler.compile`、`RenderRegionCache.createRegion`、`drawMultipleIndexed`、BLOCK vertex format | 0.3.0 局部 solid/cutout 自有 mesh；不用 visibleSections，不修改 vanilla queue；新画面待实机确认。 |
| compute/storage、albedo/normal/velocity | 尚未完成完整能力实验 | 不宣称支持，不引入 native extension；P0b/P1 阻断项。 |

## 当前验证

`./gradlew build clientKit`：编译 main/client、生成客户端 JAR、运行相关测试并打包。测试先检查最终 JAR 的 mixin package 只含已声明的 mixin 类，再覆盖 bounded 样本在 delayed GPU 结果下的回收/清空、缺失与无效 timestamp 的 CSV 空值、26.2 hook 的字节码位置，以及使用 Minecraft 自带 Vulkan GLSL 编译器将八份 shader 编译为 SPIR-V。shader 测试无需创建 Vulkan device，不能代替 graphics pipeline 实测。

当前环境没有 `/dev/dri` GPU 设备和图形 display；未启动 Minecraft，也未生成帧率/显存/画质跑分。用户已确认 0.1.2 深度视图生效；0.2.0 normal、125/125 scene、F3+T 和下界切换也获用户确认；完整 resize/颜色/计时及新阴影验证仍待执行；安装验证步骤见 [INSTALL.md](INSTALL.md)。采样导出覆盖诊断/参考阴影 pass，不是原计划完整 benchmark 系统。

0.2.0 场景基础见 [SCENE.md](SCENE.md)。0.3.0 进入地形参考阴影实验，详见 [SHADOWS.md](SHADOWS.md)。真实 GBuffer、动态 caster、完整覆盖/benchmark 验收仍在缓存实现之前。

## 0.1.1 启动崩溃修复

用户实机确认 0.1.0 在 client entrypoint 阶段发生 `IllegalClassLoadError`：mixins JSON 将整个 `com.voxellight.adapter` 声明为保留包，普通 `RenderProbe` 因此无法加载。0.1.1 将 mixin 单独放在 `com.voxellight.mixin.client`，适配器保持原包；不修改其他 mod。新增最终 JAR 包布局回归测试，在 0.1.0 上重现失败后验证修复。benchmark 导出版本改为从 Fabric mod 元数据读取。此修复针对已报告的启动错误，尚未替代后续实机渲染验证。

## 0.1.2 诊断画面修复

用户实机确认 Vulkan 下 `diagnostic active`、GPU timestamp 可用且样本增长，但 depth 画面仍与 vanilla 相同。根因是误用了 `RenderPass.draw` 参数顺序：26.2 实际签名为 `(vertexCount, instanceCount, firstVertex, firstInstance)`，原调用 `(0, 3, 0, 1)` 提交零顶点。0.1.2 改为 `(3, 1, 0, 0)`，提交一个 fullscreen triangle。新增最终 JAR draw 参数检查，在 0.1.1 上重现失败，再验证修复。活动状态/采样只能证明命令路径执行，不能单独证明最终像素正确；用户随后确认修复后的实机画面已生效。

## 0.2.0 场景基础

通过 Fabric chunk load/unload、client level change、end tick 和 shutdown 事件管理局部场景。ClientLevel 的 block/section/range dirty 与 ClientChunkCache.onLightUpdate 补充变化；ShaderManager.apply 在 reload 后推进 resource generation 并重置诊断资源。最终 JAR 测试检查注入目标与 shadow 字段和实际 26.2 类字节码匹配。

normal pass 绑定游戏 Projection uniform，从 reversed-Z 深度重建视空间方向；CPU 编译为 SPIR-V 通过，用户已确认其画面有效。它不是模型 normal map。vanilla terrain 的颜色已经乘上 lightmap/顶点颜色并应用 fog，不能作为未经光照的 albedo；主相机 visibleSections 也不能充当完整 shadow caster 集。因此没有把这些诊断当作真正的 GBuffer 或阴影接入完成。

场景测试覆盖有界合并、旧任务/世界/资源版本拒绝、同坐标卸载重载、脏原因保留、不可变快照与负坐标索引。scene 尚无 GPU 上传；不会改变世界光照。

## 0.3.0 地形参考阴影

独立调用 vanilla SectionCompiler，使用模型 atlas 与 BLOCK 顶点格式输出 solid/cutout 自有 GPU mesh。每帧最多编译/上传一个局部 section，32 MiB GPU geometry/4 MiB section 上限；不使用主相机 visibleSections，也不占用 vanilla SectionRenderDispatcher 编译队列。snapshot 与 geometryVersion 只作失效/任务门槛；RenderRegionCache 在客户端线程复制当时的真实模型邻域，九个相邻 chunk 已加载后才创建，避免用空邻居编译边界。

shadow map 使用普通 [0,1] 正向深度、clear=1、LESS_THAN_OR_EQUAL、双面 raster；main depth 仍是 reversed-Z。26.2 RenderPipeline.Builder 在没有设置 color state 时仍自动添加默认 RGBA8 slot，不能直接配零 color attachment pass；当前配一个 R8_UNORM、WRITE_NONE attachment，保持 pass/pipeline 契约并明确计入 1 MiB 预算。

resolve 绑定游戏当前 Projection（包括 bob/portal 调整），使用 CameraRenderState.viewRotationMatrix 的 inverse 恢复相机相对世界位置，避免大坐标 float 精度损失；不改 RenderSystem 的全局 projection/target。shader stages 的实际 binding 与 IO 在最终 JAR 中通过游戏 GlslCompiler 的 CPU 编译/绑定逻辑验证。普通深度比较、caster/receiver 同 texel、scene reversed-Z 重建与大坐标有测试。

影子是近距固定太阳、每帧完整重绘的地形参考实验。场景准备不完整保留 vanilla；下界等无 skylight 维度不执行。真正太阳光分离、动态实体/方块实体、透明 receiver 和远处 caster 尚未支持，因此不宣称完整 P1a 或 shaderpack 等效。本构建没有在真实 GPU 运行；用户验证过的 0.2.0 结果不自动适用于新增 pipeline。

## 0.3.1 阴影回归修复

检查用户 logs/ 中三张城堡截图：caster map 有效，主画面/mask 出现平面 self-shadow 条纹；latest.log 空。新增 nearest texel receiver-plane PCF 修正、双精度 texel snapping、最多 125 个未知 caster 投影范围的局部回退。移除任意 section 缺失即禁用整帧的条件；shadow 视图之间切换保留模型资源。UBO 扩为 4,160 字节，SPIR-V 反射测试核对 vertex/fragment 实际 block 大小。新结果尚待实机确认。

## 0.3.2 移动/视角修复

新两张截图显示地面改善、侧面仍有三角条纹；用户确认移动和某些视角出现局部消失。latest.log 仍为空。移除未知 caster 投影范围的过度回退，改为仅绘制有效集合；法线由相邻深度重建，逆转置光矩阵计算平面梯度，替代跨模型边缘的屏幕导数。uniform 回到 160 字节，增加多相机视角的平面比较测试。实机画质确认仍待用户复测。

## 0.4.0 单层缓存与连续采样

用户要求下一阶段，并怀疑条纹来自 alias；这只是可能原因，现有截图不能证明所有条纹都属于边缘采样。加入 2048² depth map 和 bilinear comparison PCF（最多 16 taps），保留平面比较修正。新增固定世界 8 格 cell map 缓存；相机平移不改变缓存 depth，caster 增删/失效、cell 边界与生命周期立即失效。含 cutout 时每帧重绘；solid 不做 alpha discard，匹配本地 26.2 RenderPipelines 的 SOLID_TERRAIN/CUTOUT_TERRAIN 分类。无需新的 renderer mixin。shadow_cache on/off 提供同质量参考切换，status/export txt 可观察缓存计数/原因。map attachments 从 5 MiB 增为 20 MiB，无额外 history。CPU cache 测试和大坐标移动投影验证不能替代 GPU 画质/性能验收。

## 0.5.0 tile 缓存推进

用户认可 0.4.0，并确认编辑时看到刷新。几何改变确实必须刷新影子；0.5.0 不再因任意 mesh 改变刷新全 map，而以 actual vertex AABB 投影标记 64 个固定 atlas tile。删除标记旧足迹，新增标记新足迹；cutout 仅逐帧更新其足迹。合并 dirty tile 成 bounded 矩形，按本地 Vulkan renderArea 清理和重绘所有重叠的当前模型，viewport 保持全图。原 map 20 MiB 不增加，fixed anchor/过滤/caster 集与 cache off 全图参考一致。没有 mesh 完成前的 stale geometry/history，也没有时间预算延期页。测试包含软件 depth 全参考对照、真实 BLOCK position format、扩展模型 bounds、失败重试与 reset；无 GPU 实机验收。

## 0.5.1 编辑刷新修复

用户确认 0.5.0 行为基本正确，并询问如何修复 local refresh。本地源码发现两条延迟来源：shadow prepare 等待 occupancy snapshot 且每帧仅一份；ClientPacketListener.readSectionList 的 light packet rebuild 通过 ClientLevelMixin 被误分类成 GEOMETRY（27 邻居）。改为同帧小组替换（最多 8 个已有 section，staging 8 MiB，steady 32 MiB），模型直接使用 loaded world 和独立 world/resource/geometry token；发布前核对全部 token。窗口外/世代变化不保留旧 mesh，失败清理 staged 并退回安全暖机。新增 ClientPacketListenerMixin WrapOperation+try/finally ThreadLocal 分类 scope，保留 vanilla invocation 和真实几何更新。CPU 与 GPU geometry readiness 现在独立；模型编译仍主线程且可能增加编辑帧 CPU 时间。新增统计记录替换/回退与 peak build CPU，map/shader 质量与 20 MiB map 内存不变。无实际 GPU 运行，仍需用户验证编辑、boundary、light-packet、reload/unload 和帧时间。

## 0.6.0 celestial light

用户认可 0.5.1 并授权下一阶段。新增 native SkyRenderState sun/moon angle 的 world light；原生 SkyRenderer 使用 Y(-90°)×X(angle) 的 pose，模型方向与测试直接对应。读取原生 interpolated angles/rainBrightness/moonPhase，不使用猜测 dayTime 公式。不新增 mixin；缓存新增 source/angleStep projection key，取整 0.025° 与 reference 一致，方向变化先全失效再画。恒定 Z up 避免 noon lookAt 奇点，fixed 模式保留旧矩阵。world normal facing gate 避免对背光/掠射面重复调暗。新命令 sun world/fixed，默认 world；night/phase/rain/horizon strength 是明确的局部艺术策略，不是真正 direct sunlight 分离。map/geometry budgets unchanged，无 GPU device 实机证据。

## 0.7.0 接入与验证

沿用现有 world-pass hook，没有新 mixin。ArtificialLights 从已加载 client chunk section 读取 getBlockState/isSolidRender/getLightEmission，geometry token 决定缓存有效性，不受 vanilla light-only packet 失效。native GPU R8_UNORM 800×640 texture 使用 COPY_DST|TEXTURE_BINDING；CommandEncoder.writeToTexture(texture, nativeByteBuffer, mip=0, layer=0, x=0,y=0,width=800,height=640) 录制复制，之后释放 CPU native buffer。CPU upload 不能混用 NativeImage 的 RGBA 格式。LocalLightSettings 三 vec4 + 两个 vec4[16] = 560 字节，shadow 160 字节不扩张。SPIR-V reflection 与实际 COMPOSITE/MASK binding 验证新 sampler/uniform；caster 与 map stage 原接口保持。

continuous angle/projection、large-world movement、anchor 边界滞回、atlas 512k cell 无别名、unknown/unload/wall 编辑、dense source cap/fade/retention、removed sources 与 suspend cache 无假发布有 CPU 测试；最终 jar shader 编译/绑定测试覆盖 DDA GLSL。不把编译测试说成 GPU 渲染正确性或 performance 验证；没有 window/GPU 的实际运行结果。0.7.0 实机 smoke/夜间和洞穴检查见 INSTALL.md。

## 0.8.0 三层 map 接入

沿用 GameRenderer.renderLevel 在 LevelRenderer.render 后、HUD projection/depth clear 之前的 hook；源码确认当前 RenderSystem Projection 已包含 native bob/hurt/nausea 变换，viewRotationMatrix 是 terrain 的同一 model-view。resolve 使用实际 projection UBO inverse，继续复原 camera-relative world positions。

每层 native RenderPass attachment 尺寸与其 D32/R8 同为 2048²、1024²、1024²；256 tile renderArea 用 instance grid4/8，viewport 为全 attachment。caster ShadowSettings=160 bytes（三 buffer），fragment ShadowResolveSettings=304 bytes；新增 MiddleShadowMap/FarShadowMap sampler，LocalLightSettings=560 bytes 不变。最终 JAR 通过游戏 VulkanGlslCompiler 的每个实际 pipeline binding/stageIO rebind 及 std140/SPIR-V sizes 测试。MODE shadow_ranges 用同一 composite pipeline 的 uniform 诊断分支，不新增 mixin。

CPU tests 覆盖所有 range 的 sphere/clip/guard（含 maximal anchor offset）、view yaw/pitch/FOV 和大坐标反投影不改变 radius/projection、cache 独立尺寸/失效/裁剪、geometry pressure 的近处准入和稳定 tie-break/拒绝无收益部分淘汰。没有实际 GPU image/perf 结果；手持灯/材质 normal/实体遮挡边界不变。

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

## 0.11.0 B1 Material capture proof

本版新增 terrain-only GBuffer/MRT 诊断，不替换既有 shadow lighting。新命令为 `mode albedo`、`surface_normal`、`emission`、`material_flags`、`material_coverage`，使用 Vulkan 并自动开启 scene。`mode shadow` 继续作为 0.10 的 lighting reference；`mode normal` 仍是旧 depth-reconstruction diagnostic。

材料捕获使用原生 ModelBlockRenderer 的 quad/seed/offset/culling，以及 BlockColors 原始 tint source；丢弃 QuadInstance 的 baked lighting Color。geometry normal 从 quad 顶点求出，按 quad nominal outward direction 校准，仅支持 planar block faces，不提供 smooth entity normals/normal maps。private ENTITY 格式的 Normal 保留 geometry normal；UV1 存放 block emission/model emission/flags，UV2 保留原生 light coordinates，但捕获 shader 不采样 lightmap。

实际 MRT：RGBA8_UNORM 存储 **sRGB 编码的 unlit albedo** 与独立 flags，避免 dark linear color 的 8-bit 量化损失；后续 lighting 需要解码一次。纹理/tint 各自解码后在线性域相乘，再编码存储。RGBA16_FLOAT 存储 signed normal 与 supported coverage；另一 RGBA16_FLOAT 存储 block/model emission strength，而非 RGB radiance。private D32 存储 reversed-Z，使用 native projection（含 bob/hurt/nausea）和 camera rotation/section offsets。

新增 hook 位于 `ChunkSectionsToRender.renderGroup(OPAQUE)` TAIL，三色 capture 后直接显示诊断，然后继续 native entities/translucency/particles/weather/hand/UI。不在最终已合成图片上用 opaque depth 覆盖玻璃或水。主 depth 不写入；display 比较 private/native depth，最多允许 8 个 positive float ULP；未匹配像素保留 vanilla。主 hook 未执行（例如其他 renderer 改写该路径）时 status 显示 `opaque terrain hook not observed; vanilla retained`，不冒险在 after-world 补画。

局部 window camera±2 sections，最多125 entries；一 section/frame，同帧撤销 edited/unloaded/stale token，暖机缺口保留 native。geometry resident16 MiB、单 section/native scratch1 MiB；容量溢出记录 deferred，已有较近 geometry 优先，不反复编译同 token 的超限 section。material surface store 与 target/capture/display owners 分离；原生 visible mesh 复用尚未实现，当前 proof 仍有独立材质几何构建开销。

三个 color targets + private depth 为24 bytes/pixel：2560×1440为88,473,600 bytes（84.375 MiB），3840×2160约189.84 MiB；capture targets cap192 MiB。另有共享 scene-color scratch4 bytes/pixel（1440p约14.06 MiB），仍用于诊断/fallback，未宣称消除 color copy。target size超限保留 vanilla。material status 显示 sections/deferred/geometryBytes/targetBytes/draws/buildNs/uploadBytes，CSV timer包括color copy、MRT capture、display；geometry build/upload在query外单独记录。

当前不捕获 fluids、translucent models、entities/block entities/held items或特定资源包 emissive texture conventions；这些仍 native 渲染。quad alpha threshold=.5；诊断使用 nearest atlas filtering，不保证与 vanilla RGSS/anisotropic edges 完全一致，coverage 可暴露差异。emission 模式的黑色只表示已支持表面的 emission strength=0，不表示它未受 vanilla block light 照亮。尚未完成 B2 HDR sun/sky/local/emission separation 或完整 B1 实机验收。

实机检查：先 `material_coverage` 等待数秒，附近普通 terrain 应逐渐变绿；然后 albedo 看六面白色方块不再有 face lighting、火把开关不改变材质值；surface_normal 看台阶/半砖/斜面/栏杆与转动镜头时的世界空间 normal；emission 看 glowstone/torch 亮而受火把照明的墙 emission=0。保持这些模式测试移动、破坏/放置、F3+T、切维度、fullscreen/resize、bob/hurt/nausea；看 unsupported实体/水/玻璃/手/UI仍native。若广泛 magenta、全 vanilla 或 crash，请保留截图、status 与日志，不认为自动测试等于实机验证。

同时补齐独立 entity shadow pipeline 的 native shader precompile 注册，避免其首次 draw 依赖默认 shader 路径解析。

## 0.12.0 separated lighting boundary

B1 的 opaque `ChunkSectionsToRender.renderGroup` TAIL 同时用于 `FOUNDATION`。`MaterialCapture.capture` 只输出 material；`ShadowRenderer.updateLighting/bindLighting` 只生产/绑定 visibility，不执行 legacy composite；`LightingResolvePass` 拥有独立 RGBA16F lighting 与 main-target tone/fog output。输出 invalid pixels discard，不依赖 scene copy。使用 native `RenderSystem.getShaderFog()`（LevelRenderer 在 opaque 前设 terrainFog）。borrowed main depth 只采样，不作 attachment/write；后续 entities/transparency/particles 仍native。

新SurfaceToken包含LIGHT revision，不改 caster GeometryToken 规则；material store限额/loaded-only/一section-per-frame/失效撤销规则保留。实际颜色模型/显存cap/视觉验收见 VISUAL-FOUNDATION.md 与 INSTALL.md。Shader binding/SPIR-V checks覆盖新增pipeline与HDR格式，视觉验收仍须用户实机。

## 0.13.0 B3a dynamic block-entity casters

EntityShadows 的模型/GPU内存迁到 DynamicModelBuffer；DynamicCasterSystem 协调 mobs 与 BlockEntityShadows，使用同一三 cascade dynamic depth。新选择来自 loaded chunk.getBlockEntities，不依赖 visible section list；原生 tryExtractRenderState/submit 及 Model/ModelPart/SpriteGetter 默认路径捕获动画与 atlas UV。每类32对象/128模型/1 MiB frame/256 KiB scratch，block_entity_shadows 默认on，status独立；全64位 BlockPos tie-break。每cascade在 terrain 结束后重新借当前共享 indexbuffer，统一较大请求，pass内不触发 growth。动态失败rollback和删除/开关清空保持有效。

Native26.2床是普通模型，其他block-entity仅native model submit支持；item/text/transparent/custom geometry不支持。自身 material 仍vanilla，人工灯DDA不检测动态对象。B3a实机待验收，后续entity material/light-aware caster volume见PLAN。
