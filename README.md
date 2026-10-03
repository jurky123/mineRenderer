# mineRenderer / VoxelLight

VoxelLight 是纯客户端 Fabric 光照引擎 mod：复用 Minecraft 原生渲染器，以缓存阴影、统一体素场景和渐进更新 GI 改善方块世界光照。

当前是 **Minecraft 26.2 的局部光照 renderer prototype**：真实材质/法线、分离HDR lighting、三层太阳/月亮阴影、形状人工灯、有界动态caster、半分辨率terrain AO、色彩/天空光与emissive bloom。仅支持原生Vulkan，默认关闭；`foundation`是主效果，`shadow`为旧LDR比较路径。当前版本、已确认阶段、预算与下一步统一记录于[CURRENT.md](docs/CURRENT.md)。目录名为mineRenderer，功能名为VoxelLight，mod ID为`voxellight`。

## 文档

- [Atmosphere prototype](docs/ATMOSPHERE.md)：局部高度/距离haze、analytic forward glow、比较与边界。
- [局部灯/手持光](docs/LOCAL-LIGHTS.md)：resource-pack颜色和16源预算内的动态手持灯。
- [色彩/光照polish](docs/POLISH.md)：filmic、手动曝光、天空半球色、emission bloom与reference比较。
- [AO说明](docs/AO.md)：terrain horizon AO、ambient composition与验收。
- [当前状态](docs/CURRENT.md)：唯一的当前阶段/验收/下一步记录。
- [版本历史](docs/CHANGELOG.md)：历史实现与评审决定。
- [原始 v0.2 设计文档](VoxelLight_Design_v0.2_Native_Vulkan.docx)：保留原件。
- [v0.2 可搜索文本](docs/DESIGN-v0.2.md)：按段落提取，表格布局请看 DOCX。
- [设计评审](docs/REVIEW.md)：已核验依据、工程缺口和建议决策。
- [0.10.0 评审决策](docs/REVIEW-0.10.0.md)：已核验限制与 Visual Foundation 优先级。
- [Visual Foundation contract](docs/VISUAL-FOUNDATION.md)：material capture、分离 lighting 与 geometry/caster 的实施门槛。
- [实施计划](docs/PLAN.md)：修订后的依赖顺序、实验和验收标准；后续实施按此计划推进。
- [26.2 接入能力与验证记录](docs/INTEGRATION.md)：实际 API、资源生命周期和尚未通过的实机门槛。
- [参考阴影与资源预算](docs/SHADOWS.md)：固定光源、独立 caster、回退与当前画质边界。
- [客户端安装与诊断](docs/INSTALL.md)：安装前置、命令和 smoke checklist。

## 边界与开发约定

按用户要求，当前开发基线改为 Minecraft **26.2** / Java **25** / Fabric Loader **0.19.5** / Fabric API **0.160.0+26.2** / Loom **1.17.21** / Gradle **9.5.1**。使用 26.2 官方未混淆类名，无额外 mappings。26.3/26.4 不属于当前支持范围；原始设计文档作为历史保留。

本项目独立实现客户端世界光照，不属于 MineUI 界面 API、MineAudio 音频 API 或 MineDisplay 展示业务，无须服务端插件。需要其他项目新增能力时先提出需求。

采用单个 Fabric 构建工程和功能包，版本相关 hook 集中在 `com.voxellight.adapter`，可独立测试的采样数据在 `com.voxellight.debug`。出现真实复用需求后再拆模块。

## 构建与使用

```sh
./gradlew build clientKit
```

mod：`build/libs/voxellight-client-26.2-<version>.jar`；安装包：`build/distributions/voxellight-client-kit-26.2-<version>.zip`。`<version>`取自`gradle.properties`的`mod_version`，见[当前状态](docs/CURRENT.md)。安装包只含本 mod 和安装说明；Fabric Loader/API 按安装文档配置。

进入世界后使用 `/voxellight mode foundation` 开启材质分离光照；使用 `/voxellight mode color` 检查原画面复制，`/voxellight mode depth` 查看世界深度，`/voxellight mode off` 恢复原画面。`/voxellight status` 查看状态，`/voxellight export` 导出最近最多 14,400 个 pass 样本。仅 Vulkan 执行诊断，OpenGL 保留 vanilla。所有命令均在本地执行，无服务端要求。

新增 `/voxellight mode normal`：深度重建的视空间表面方向着色，不是模型/PBR 法线。`/voxellight scene on` 启用 CPU 场景跟踪，`/voxellight scene` 查看队列与版本统计，`/voxellight scene inspect` 查看准星方块的快照材质，`/voxellight scene off` 关闭并清空快照。场景默认关闭，与视觉 mode 独立。

场景仅跟踪light-aware volume 内最多384个已加载section（cube比较为7×7×7=343）；人工灯另限定为近处 125 section/80³。客户端线程每 tick 最多复制两份 palette、以 2 ms 作为软预算；单 worker 编码，最多两个提交任务。变化合并成有界 marker，拒绝旧 generation/version 的结果；离开局部窗口的 section 立即撤销。详见 [场景设计与预算](docs/SCENE.md)。

新增 `/voxellight mode shadow`：自动启用 scene，在主世界附近生成随原生 sun/moon angle 变化的地形阴影，并在已加载世界添加发光方块的局部灯。暖机后生效；32 MiB geometry 压力下优先保留近处 caster，status 会显示 budgetDeferred，而非要求永远 N/N；`mode shadow_mask` 查看白=无遮挡/黑=遮挡，`mode shadow_map` 查看光相机深度。默认复用有效 map；`/voxellight shadow_cache off` 用同一投影/过滤每帧重绘作为参考，`on` 恢复缓存。cutout 所覆盖的 tile 每帧重绘，其他有效 tile 可复用。模型沿用 vanilla，包含 slab/fence/cutout；不依赖主相机可见集合。方向阴影/月光默认接收 48 格内、40–48 格淡出，12–16 与 26–32 格重叠混合；人工灯仍为 24 格内、16–24 淡出；重建期间保留当前有效 caster 已确认的阴影，缺失 caster 仍可能造成局部漏影；无 skylight 维度只运行局部灯；资源超预算时保留原画面。详见 [参考阴影](docs/SHADOWS.md)。

`build` 包含阴影投影/偏置、实际 pipeline shader 绑定与 stage IO、队列/快照正确性、最终 JAR mixin 包与目标契约、非零 draw、采样数据和 Vulkan GLSL→SPIR-V 测试。本机没有 GPU/显示环境；新增功能的构建通过不替代实机验证。

性能预算是实验目标，尚无跑分。先验证 renderer 接入和缓存阴影，达到阶段门槛后再进入 GI。提交源码时同步记录构建、相关测试和游戏内验证结果。

独立 Git 仓库使用 `main` 分支；不改汇总仓库子模块指针。运行世界、日志、构建产物和本地代理配置不进入版本控制。已配置 origin：`https://github.com/jurky123/mineRenderer.git`。构建与验证记录见接入文档。
