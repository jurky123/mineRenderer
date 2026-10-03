# VoxelLight 实施计划

最新优先级：用户确认0.24运行，并要求一次完成3phase后再实机测试。0.25 bundle完成1) bounded HDR water SSR，2) quarter-res bilateral volumetric spatial filtering，3) animated normal waves + fixed quality/comparison controls。默认density0.001不变。交付后按[combined checklist](TEST-0.25.md)统一实机验收；暂不扩展GI或加入未经验证的temporal volume history。

## 当前决策：Visual Foundation 优先（0.10.0 review 后）

当前实现、用户验收与下一任务统一见[CURRENT.md](CURRENT.md)，本文件保留路线和历史实施记录。0.16.1 material refresh/frame transforms已获用户确认，D1 scope保持有限，0.17 Basic AO已确认运行但收益较细微，0.18 lighting/color polish已获确认，0.19 local-light颜色/手持源已获确认，0.20 aerial perspective已获确认，默认density0.002；0.21实现Water Foundation与composition owner split并等待实机；不以temporal细微差异宣称完整验收。

| 顺序 | 下一里程碑 | 门槛 |
| --- | --- | --- |
| B1 | Material/GBuffer capture proof（0.11.2 用户确认） | 材质不含 lightmap/AO/fog；geometry normals 与 native depth/cutout 对齐；状态/预算和实机诊断。 |
| B2 | 分离 opaque lighting | 太阳阴影只作用于 direct；保留声明的 vanilla block-light baseline；HDR/tonemap/fog 一次应用；透明/UI 合成正确。 |
| B3 | Geometry/caster foundation（0.13–0.15 用户确认） | 有界实体/方块实体caster、opaque模型材质、light-aware窗口；unsupported模型仍native。 |
| D | Temporal + basic AO | camera reprojection、depth/normal rejection，动态无 motion pixels 拒绝 history。 |
| E | Local lighting polish | 数据驱动光源色、多 source 聚合、明确 falloff 和有界动态光源。 |
| F | 可选水/大气/SSR | 原生透明合成与独立 history/资源预算已验证。 |
| G | GPU Voxel DB / Probe GI | 真实 material/normal/emission 和基本 lighting/temporal 已通过，不使用 legacy 已照明色作为 GI 材质输入。 |

保留 legacy 效果与 profiler 作为可比较 reference。预算调度、dual-angle cache、cutout 动画分类、mesh reuse、clustered lights 等性能改造按实测推进；不声明当前 world-sun cache 已达到原 P1b 收益门槛。B1/B2 已获用户确认；B3a block-entity caster implemented，实机验收 pending；B3 material/caster-volume 后续实施。

具体依据：[0.10.0 评审决策](REVIEW-0.10.0.md)。下一阶段输入/格式/owner/验收：[Visual Foundation contract](VISUAL-FOUNDATION.md)。文后原计划和逐版记录保留为历史。


状态：用户已确认 0.1.2 Vulkan depth，以及 0.2.0 normal、125/125 局部 section、F3+T 和下界切换正常。0.3.0 进入 P1a 的地形参考阴影实验：独立模型 caster、固定太阳、单层非缓存 map、cutout、PCF/bias、近距合成。0.3.0 用户截图已证明 caster map 执行，同时暴露 acne 和全局准备闪灭；0.3.1 后新截图地面改善但侧面仍有条纹，移动时局部消失；0.3.2 改为深度邻域法线平面修正并保留有效 caster 的已确认遮挡，待实机复测；实体/方块实体动态 caster、远距完整覆盖、真正 GBuffer 和完整 benchmark 仍待接入，不宣称完整 P1a 验收通过。按用户要求基线为 Minecraft 26.2。详见 [评审](REVIEW.md)、[接入记录](INTEGRATION.md) 和 [阴影边界](SHADOWS.md)。

0.4.0 推进 P1b 的单层缓存基础：固定 8 格世界 anchor、caster 集版本失效、同质量每帧参考开关、map reuse/update 原因；同时将 map 提升到 2048² 并连续插值 comparison PCF。cutout 每帧重绘保证动画 alpha 当前。P1a 剩余画质/动态层与 P1b 三层 clipmap/分页/动态合成/太阳运动仍未完成；先实机比较当前缓存与参考。

用户认可 0.4.0，并观察编辑刷新。0.5.0 继续 tile 更新基础：64 个逻辑页、实际 caster bounds 的局部失效、邻接合并矩形、重叠 caster 全重绘与页更新计数。已有同质量参考开关；生命周期仍全重置。没有虚拟分页/三层 clipmap/延期预算，编辑 mesh 重建仍可能漏影；需实机比较 tile 边界与增删重叠 caster。

0.5.1 优先修复用户观察的 local refresh：独立 live geometry token、最多 8 section 同帧编辑替换、32 MiB steady/8 MiB staging；特定网络 light rebuild 分类 LIGHT，避免 27 邻居误失效。大组/预算/未加载邻居仍安全暖机，CPU 同步开销待测；新增替换/回退/peak CPU 状态。

用户认可 0.5.1。0.6.0 推进 P1b 变化太阳：native sky sun/moon angles、月相/雨天/近地平线强度策略、source/angle cache key、正午稳定 basis、sun world/fixed 比较开关。cache/reference 同样取整方向；方向变化立即全更新，strength-only 不失效。3 层 clipmap/延期页预算/动态 entity 与完整性能验收仍未完成。

## 原始阶段与验收（历史路线；当前优先级见文首）

| 阶段 | 交付 | 进入下一阶段的条件 |
| --- | --- | --- |
| P0a 版本/能力实验 | 单 Fabric 工程、版本锁定、颜色/深度 test pass、backend 检测、关闭恢复、能力表 | Vulkan 接入成功，OpenGL/缺能力安全恢复 vanilla；清楚记录公开 API 与内部 hook。 |
| P0b 场景与测量基础 | GPU timestamp、CPU 时间、资源统计、最小材质/normal 验证、WorldSceneBridge、有界队列 | 原生画面/透明/UI 次序正确；resize、reload、切世界无陈旧资源；generation 丢弃与背压可验证。 |
| P1a 阴影正确性基线 | 固定太阳、单层非缓存参考阴影、caster 提交、cutout、bias、动态层 | 非主相机可见 caster 正确投影；薄墙、远处遮挡与方块编辑正确；确立参考画质。 |
| P1b 缓存 MVP | 三层 clipmap、页调度、有效期、静态/动态合成、变化太阳、debug/benchmark | 达到下述正确性和性能门槛；否则修订投影/失效方案，暂停增加效果。 |
| P2 统一 GPU Voxel DB | 有界 section DB、分类/压缩、occupancy 层级、增量上传与回收 | 加载/卸载、快速编辑和长距离飞行无陈旧条目；显存稳定，记录每帧上传和更新时间。 |
| P3 AO / Local Lights | 半分辨率 GTAO、emissive 聚合、cluster cap/overflow、基础 temporal | 洞穴和火把压力测试质量合理，溢出按稳定重要性选择而非闪烁；分项预算实测。 |
| P4 Probe GI | 从单 cascade/完整方块开始，visibility moments、预算调度、clipmap | 薄墙漏光、运动响应和最长年龄达标后再扩展三 cascade；记录 quality/time Pareto 曲线。 |
| P5 可选画质 | volumetric、SSR、透明材质增强 | 分别用实测证明收益，明确 fallback 和 history。 |
| P6+ 后续研究 | DRS/空间 upscaling，然后可选 vendor temporal upscaler/RT | 能力、许可证、分发与质量有依据；保留无 RT 路径。 |

所有阶段优先保留 Mojang geometry、frame lifecycle 与 UI。版本 adapter 集中内部依赖，新增 native 能力逐项证明同步规则。当前不创建占位实现、无验证版本号的构建配置或泛化框架。

## P0 可直接执行的任务

1. 锁定一组实际可解析的 Minecraft 26.2、Java、Fabric Loader/API、Loom 和映射版本，记录坐标、开发 GPU/驱动和依赖来源；建立 `./gradlew build`，仅打包客户端入口。
2. 从生成源码列出所需 hook：world pass 边界、depth lifetime、颜色/材质来源、normal、terrain caster 提交、透明合成、UI、资源销毁。每项记录 exact symbol 和验证证据；不能把设计里的伪接口当实际 API。
3. 最小 pass 实验逐项验证 depth 约定、颜色空间、resize 和 disabled path，再扩展最小 GBuffer；完整 velocity 延后。缺能力时原生渲染继续运行，日志说明功能关闭原因。
4. Timestamp 采用延迟读取，不阻塞当前帧；记录 query 支持、有效位、单位和 unavailable 状态。CPU 计时不能伪称 GPU 时间。
5. 增加 section 快照、加载/卸载、世界和资源 generation、有界合并；只对有实际数据流的逻辑建立测试。
6. 形成 smoke checklist：启动/退出世界、维度切换、teleport、resize/fullscreen、resource reload、开关效果、backend/能力回退。需要图形环境的检查记录实际完成状态，不以构建通过替代。

## P1 比较方法与建议门槛

同一实现提供“每帧更新全部有效页”参考模式和缓存模式，保持投影、覆盖、分辨率、过滤、bias、caster 集一致，隔离缓存本身收益。vanilla 用于测集成开销；Iris/Vitrail 仅作额外外部比较，注明不同 backend/材质/效果造成的不可比因素。

建议初始门槛如下，首次基线后如需调整必须记录原因，不能为了通过而暗降画质：

- 固定太阳静止场景暖机后静态页 reuse ≥ 90%，缓存模式阴影 GPU p50 较全更新减少 ≥ 30%。
- 正常太阳运动、飞行和编辑场景总 GPU p95 较全更新不得恶化超过 10%；阴影画质不能靠使用无效页或减少覆盖换取。
- 近层可见脏页目标两帧内更新；超预算记录 deadline miss 并回退有效粗层，不伪报完成。远层设置有界最大年龄，数值由首个原型的误差测试确定。
- 低太阳角度、昼夜切换、破坏远处 caster、slab/cutout、动态实体均与全更新参考逐帧/截图检查；记录 shadow disagreement 和最大滞后，未定阈值前人工验收。
- 30 分钟 fly/edit/load/unload 压力运行不超过配置资源上限；资源 reload 后回到可解释的稳态，无旧 generation 写回。

## Benchmark 记录与预算

固定世界存档/seed、路线、时间/天气、视距、分辨率、render scale、质量配置、GPU/驱动、游戏/依赖版本和 Git commit。至少覆盖平原、森林、城市、洞穴、火把、快速编辑、飞行，并分别测试固定太阳和运行昼夜。每种模式预热 30 秒、采样 120 秒、重复三次；随机或交替运行顺序。

记录 CPU/GPU frame p50/p95/p99、平均 FPS、1% low（定义为最慢 1% 帧平均时间的倒数）、各 pass 时间、驻留资源/分配字节、上传字节、页 reuse/失效原因/截止超时、probe 更新/年龄和 temporal rejection。保存原始 CSV/JSON 到被忽略的 `benchmark-results/`；可分享的摘要和复现实验说明经人工检查后提交 docs。

6.0 ms 效果预算是原文目标总额，不能承诺 120 FPS。为每一阶段记录 total frame、vanilla baseline 和增量；DRS 不得掩盖算法回归，性能比较时锁定 render scale。先固定质量测量，再引入带滞回的自动控制。

P0 首次实测后锁定资源表：每个 target 的格式/分辨率/历史/frames-in-flight，shadow atlas 上限，驻留 section 数和每条目字节，probe 数与 radiance/visibility 字节，staging 和每帧 upload 上限。每项有 owner、淘汰条件和显存不足的降级动作；在该表完成前不进入大规模 voxel/GI。

## 验证与交付

文档阶段检查相对链接、源文档提取和 Git 忽略规则，无可构建代码。实现阶段运行 `./gradlew build` 和相关逻辑测试；render 正确性通过实机 smoke/对比截图验证。客户端交付文件名含游戏和 mod 版本，只包含本 mod 产物。

已配置 origin 为 `https://github.com/jurky123/mineRenderer.git`。汇总仓库索引后续需登记 mineRenderer；不修改其他仓库或子模块指针。

## 0.7.0 用户要求优先修复 flicker，并推进局部人工光源

用户未接受 0.6.0 的 flicker/月光表现。本次取消渲染方向取整与旋转全局 resnap，加入 anchor 滞回、近轴 normal 稳定、较宽 comparison PCF 和实际冷色 moon fill；实机是否消除报告问题待用户验证。按本次明确要求先交付有界 P2/P3 局部灯子集：最多 16 source、64 spatial representatives/section、80³ full-block opacity atlas、DDA 遮挡、编辑/generation token、选择/fade/overflow 与状态预算。不是完整 GPU Voxel DB、clustered light、细材质遮挡或 GI；3 层 clipmap/页调度/动态 entity 与性能验收仍待完成。下一阶段以用户视觉反馈和导出数据决定先补抗锯齿/细材质/局部灯预算，不默认宣称已经达到各阶段验收。

## 0.8.0 用户批准下一阶段；优先处理某些视角下阴影突变

用户认可 0.7，但怀疑影距导致局部突变。实施 P1b 三层 overlapping local cascades（2048/1024/1024，12..16/26..32 blends、默认 40..48 fade）、343-section scene marker/模型窗口、32 MiB 有界 proximity residency、独立 125-section 人工灯数据。补 two-neighbor depth plane selection 与连续 axis normal stabilization，提供 shadow_distance/ranges/map 三图诊断。缓存/参考保持同样的质量参数。CPU coverage/projection 与大小不同的 tile caches、预算准入/不会来回淘汰、shipped shader/UBO tests 通过；GPU 角度问题是否完全消除仍需用户实机回归。三层 map 不等于完成滚动 clipmap、页调度、动态 caster 或完整 P1b 性能验收。下一步依 export/截图先确认 coverage/normal/alias 根因，再决定细材质/temporal/预算调度；不直接宣称全阶段通过。

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

## 0.12.0 B2 separated terrain lighting

新增 `mode foundation`；`shadow` 保留 legacy comparison。独立 LightingResolvePass 负责 RGBA16F HDR、linear lighting、固定曝光/Reinhard/display encode/native fog。使用 B1 实际 geometry normal/material，不再给 SceneColor 二次打光。主 depth 不写入，unsupported pixels discard，native entities/transparency/UI 随后正常绘制；foundation 不复制 SceneColor。

SurfaceToken 单独跟踪 light-only packed light 失效，保留 shadow GeometryToken。局部灯使用 native block-light level baseline + smooth energy replacement，保留未入选 sources，避免 baseline 与 selected fill 整项相加。参考 emission 使用 material albedo 色与 emission strength；资源包 emissive radiance 和 exact native lightmap parity 尚未实现。

用户须验收火把墙/发光块太阳遮挡、昼夜/月相/雨/洞穴、玻璃水与 fog 顺序、灯更新与 F3+T/resize/维度切换。预算/格式/边界见 VISUAL-FOUNDATION.md 最新章节。B3 和 temporal/GI 仍未开始。

## 0.12.1 plant stability follow-up

0.12.0 overall foundation 获用户确认；植物 flicker 单独修复：capture native culling parity，取消 camera-driven foliage normal flip，双面 diffuse/sky 与 emitter-facing local ray offset。保留 8 ULP/采样/预算。实机复测后继续 B3；未借此宣称 temporal 已完成。

## 0.13.0 B3a block-entity caster

进入 geometry coverage 的第一步：DynamicCasterSystem 分离动/静 ownership，EntityShadows/BlockEntityShadows 负责原生对象选择/extract/submit，DynamicModelBuffer 统一限额顶点/sprite转换/rollback/上传。block-entity loaded-only selection 与 native camera visible list 无关，默认独立开关。已有 dynamic depth 共享，不因 animation 失效 terrain cache。完整 64-bit block position tie-break、原生 atlas/animated pose/预算/rollback/loaded-only/index refresh 合约加入测试。

实机验收 chest/shulker/banner/off-camera/removal/reload 等后，B3 下一步为 entity material/normal migration 与 light-aware caster volume；native26.2 BedBlock 是普通模型，已由 terrain 路径处理。当前不宣称 B3 完成。


## 0.14.0 B3b light-aware terrain caster selection

用户已确认0.13 block-entity阴影工作。本阶段先完成caster correctness子项：实际render camera48格receiver球，向当前sun/moon扫掠16..48格；native已加载chunk过滤后最多384sections，本地125优先，terrain32 MiB/staging8 MiB与material/local-light budgets保持不变。提供caster_volume light/cube比较与candidate/eligible/deferred/extrusion/selection计数，shadow准备读取keys前更新volume。自动测试覆盖光源方向/高低角度、真实camera offset/大坐标、local125优先、加载过滤先于cap、section距离独立数值验证及shipped loaded-only/调用顺序合约。

实机验收低角度上游远墙、第三人称/移动/编辑/卸载/F3+T/teleport/维度切换待用户执行；有限volume与budget不能宣称完整coverage。B3下一步仍为entity material/normal migration，然后才temporal与后续visual polish/GI。


## 0.15.0 B3c opaque native model material migration

用户已确认0.14 light-aware窗口。本阶段迁移支持的opaque模型材质：tee原生ModelFeatureRenderer一次render调用，ENTITY保留pose/world normal/UV/tint/overlay/native sky-block brightness；独立材质ownership，不使用shadow BLOCK输出。solid features完成后、translucency/depth copy前capture+HDR resolve，复用本帧shadow与既有MRT/HDR，不修改native主depth，foundation不复制SceneColor。ENTITY flag防止cutout动物被当作双面foliage，armor trim exclusion与超限全实体native回退防止擦除native装饰。

128模型尝试/1 MiB frame/256 KiB scratch，settings64 bytes；native vertices始终优先写入，private容量失败不截断vanilla。自动检查actual vertex/UV/normal/overlay/light、sprite parity、private budget/reset/trim fallback、native composition顺序、Mixin调用及实体GLSL→SPIR-V绑定；实机动物动画、armor trim/glint、hurt、block-entity开合、深度遮挡、reload/resize/维度等待用户确认。

本阶段仍不迁移blended玩家skin、held item/custom geometry、特殊emissive/dissolve shader。B3不宣称全实体覆盖；完成当前实机验收后，temporal基础可以开始设计，动态像素无motion时须拒绝history。GI仍后置。

## 0.16.0 D1 directional visibility history

用户确认0.15.0 opaque entity material lighting，进入temporal第一步。仅terrain direct sun/moon visibility过滤，MRT保留current HDR + visibility/direct term，额外pass重建position并reproject至上一帧packed visibility/oct-normal/radial-distance history，再修正current direct radiance。不会积累RGB；late entity material lighting仍current。dynamic caster遮挡足迹与animated/ENTITY材料拒绝history，强visibility变化立即响应。

GameRendererMixin捕获renderLevel实际ProjectionMatrixBuffer.getBuffer(Matrix4f)，包含bob/hurt/nausea；不能使用nominal camera projection替代。world/resource/scene changeRevision、caster geometryRevision、light source/jump、camera cut、frame gap、settings/resize使history失效。Bridge changeRevision仅增加可观测dirty-stream序号，不改变geometry/light-only契约。

四张RGBA16F、32 bytes/pixel、128 MiB额外cap和96-byte UBO；超过cap退回unfiltered foundation。两个MRT render descriptors显式renderArea，不写native depth。CPU admission counters不代表GPU pixel acceptance。native shader binding/阶段编译与CPU reprojection/invalidation/budget检查通过；移动阴影/编辑/plant/model/reload/resize实机验收待用户。下一步在D1验收后做basic AO或后续temporal覆盖，full TAA/motion vectors/GI未完成。

## 0.16.1 review follow-up

不扩展temporal或直接叠AO：先修LIGHT/material coverage闪回与移除fragment matrix inverses。retain-old/build/token-verify/swap使旧surface继续有效，steady16 MiB、每section1 MiB、temporary GPU replacement至多1 MiB；优先replacements，每帧仍最多一次build，oversized token不会反复编译。world/resource/unload/window退出立即退休。LIGHT仍重建packed light，独立dynamic light-data stream后置。

Directional history改用geometryChangeRevision，纯LIGHT dirty不全局reset；torch的实际geometry改变仍reset。其他D1算法/内存cap不扩张。用户目前无法辨认0.16改善，完整ghosting/稳定性验收仍pending。下一视觉功能选择半分辨率Basic AO + bilateral upsample，先不加AO temporal；tone/sky/local polish后续。

## 0.17.0 D2 basic horizon AO

先实现GTAO-inspired normalized horizon slice reference，非完整XeGTAO移植。half-res native-depth validated material normal/opaque inputs、5×5 spatial bilateral和full-res四guide upsample。ambient/sky与未shadowed的block-fill乘AO，sun/moon、selected direct lights和emission不乘AO。纹理与shadow history仍独立current，不引入AO temporal或geometry窗口扩张。

控制`ao on/off/view`、32 MiB target cap、neutral fallback、odd-size guide matching与lifecycle由AmbientOcclusionPass管理。native binding/UBO/descriptor和数学数值积分验证通过；墙角/楼梯/树根/洞穴、halo/FOV/reload/resize/维度实机检查pending。验收后进入tone/sky polish；性能和GI仍后置。


## 0.26 review follow-up: reusable world-sun shadows

Implemented dual-angle terrain epochs and static-cutout classification after0.25.1 CPU fixes/profiling. See[SHADOW-EPOCHS.md](SHADOW-EPOCHS.md) and CURRENT for acceptance. This is a bounded cache improvement, not a quality-reference freeze or a measured whole-renderer optimization. Future priority depends on off/on exports: additional resolve sampling trades against reduced map submissions. HZB SSR, froxel visibility, packed history, material single-raster MRT and clustered local lights remain pending; do not increase lights or march steps without evidence.

## 0.27 performance architecture entry

Implemented packed material MRT (16B/pixel) and temporal-shadow opt-in. Validate input precision/coverage and capture pass timings before selecting further raster changes. Directional visibility, HZB, froxel/temporal volume, single-raster material and RTX interop remain future work; full path tracing is not a performance mode.

## 0.28 surface shadow sampling budgets

Implemented4/16/36-tap continuous PCF and balanced default, preserving high reference. User-reported temporal-off gain15 FPS reinforces opt-in history. Compare filter appearance/pass timings before committing to screen-space visibility or HZB infrastructure.

## 0.29 HZB infrastructure and water consumer

Implemented optional conservative max-depth pyramid and hierarchical water SSR, with profiler stage and linear reference. Default promotion requires total-cost and image comparison. Remaining review work: compact directional visibility, volumetric/froxel sampling, single-raster material capture, clustered lights and a separate RTX capability/interop POC.
