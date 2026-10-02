# mineRenderer / VoxelLight

VoxelLight 是纯客户端 Fabric 光照引擎 mod：复用 Minecraft 原生渲染器，以缓存阴影、统一体素场景和渐进更新 GI 改善方块世界光照。

当前是 **Minecraft 26.2 的局部光照 renderer prototype**。0.12.0 新增 `foundation`：以未照明材质、真实 geometry normal 和 emission strength 分离 sun/moon、sky、block/local illumination，输出 HDR 后 tone map/native fog；0.11.2 的 material diagnostics 已获用户确认。既有三层局部 directional tile cache、形状人工灯遮挡与有界动态实体 caster 继续复用，`shadow` 保留旧 LDR 比较路径。具备统一世界脏区、局部快照、backend 检测、非阻塞 GPU timestamp、显存/上传状态与 CSV 导出。Foundation 尚待实机验收；material 仅有限 terrain coverage，未支持 geometry 继续 vanilla，尚无实体材质迁移、方块实体阴影、虚拟分页/滚动 clipmap、temporal 或 GI。目录/仓库名称为 `mineRenderer`，功能名称为 `VoxelLight`，mod ID 为 `voxellight`，Java 包为 `com.voxellight`。

## 文档

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

mod：`build/libs/voxellight-client-26.2-0.12.0.jar`；安装包：`build/distributions/voxellight-client-kit-26.2-0.12.0.zip`。安装包只含本 mod 和安装说明；Fabric Loader/API 按安装文档配置。

进入世界后使用 `/voxellight mode color` 检查原画面复制，`/voxellight mode depth` 查看世界深度，`/voxellight mode off` 恢复原画面。`/voxellight status` 查看状态，`/voxellight export` 导出最近最多 14,400 个 pass 样本。仅 Vulkan 执行诊断，OpenGL 保留 vanilla。所有命令均在本地执行，无服务端要求。

新增 `/voxellight mode normal`：深度重建的视空间表面方向着色，不是模型/PBR 法线。`/voxellight scene on` 启用 CPU 场景跟踪，`/voxellight scene` 查看队列与版本统计，`/voxellight scene inspect` 查看准星方块的快照材质，`/voxellight scene off` 关闭并清空快照。场景默认关闭，与视觉 mode 独立。

场景仅跟踪相机实体附近最多 343 个已加载 section（7×7×7）；人工灯另限定为近处 125 section/80³。客户端线程每 tick 最多复制两份 palette、以 2 ms 作为软预算；单 worker 编码，最多两个提交任务。变化合并成有界 marker，拒绝旧 generation/version 的结果；离开局部窗口的 section 立即撤销。详见 [场景设计与预算](docs/SCENE.md)。

新增 `/voxellight mode shadow`：自动启用 scene，在主世界附近生成随原生 sun/moon angle 变化的地形阴影，并在已加载世界添加发光方块的局部灯。暖机后生效；32 MiB geometry 压力下优先保留近处 caster，status 会显示 budgetDeferred，而非要求永远 N/N；`mode shadow_mask` 查看白=无遮挡/黑=遮挡，`mode shadow_map` 查看光相机深度。默认复用有效 map；`/voxellight shadow_cache off` 用同一投影/过滤每帧重绘作为参考，`on` 恢复缓存。cutout 所覆盖的 tile 每帧重绘，其他有效 tile 可复用。模型沿用 vanilla，包含 slab/fence/cutout；不依赖主相机可见集合。方向阴影/月光默认接收 48 格内、40–48 格淡出，12–16 与 26–32 格重叠混合；人工灯仍为 24 格内、16–24 淡出；重建期间保留当前有效 caster 已确认的阴影，缺失 caster 仍可能造成局部漏影；无 skylight 维度只运行局部灯；资源超预算时保留原画面。详见 [参考阴影](docs/SHADOWS.md)。

`build` 包含阴影投影/偏置、实际 pipeline shader 绑定与 stage IO、队列/快照正确性、最终 JAR mixin 包与目标契约、非零 draw、采样数据和 Vulkan GLSL→SPIR-V 测试。本机没有 GPU/显示环境；新增功能的构建通过不替代实机验证。

性能预算是实验目标，尚无跑分。先验证 renderer 接入和缓存阴影，达到阶段门槛后再进入 GI。提交源码时同步记录构建、相关测试和游戏内验证结果。

独立 Git 仓库使用 `main` 分支；不改汇总仓库子模块指针。运行世界、日志、构建产物和本地代理配置不进入版本控制。已配置 origin：`https://github.com/jurky123/mineRenderer.git`。构建与验证记录见接入文档。

0.5.0 的 tile 缓存继续推进 P1b：一张现有 map 划分为 8×8 个 256² tile，mesh 增删/失效按实际顶点 AABB 的光空间投影失效旧/新足迹，合并脏 tile 的矩形并重绘所有当前重叠 caster。不新增 map 内存。`updatedPages=N/64`、`pageUpdates/pageReuses/updateRegions` 在 status/export txt 中可见；cell 跨界与 reset 仍全更新，编辑 mesh 重建仍可能暂时漏影。未实现三层 clipmap、虚拟分页/预算调度或动态实体层，实际性能待测。

0.5.1 的编辑修复：shadow geometry 使用客户端当前 world/resource/version token，不再等待 CPU occupancy 编码。最多 8 个已驻留 section 的编辑在一次 prepare 内构建/检查/替换，再绘制脏 tile；新增驻留仍每帧一个。32 MiB steady geometry 外允许至多 8 MiB replacement staging，每 section 仍 4 MiB。大组编辑、预算不足或未加载邻居安全退回局部暖机，可能漏影；不跨帧保留陈旧 caster。单个编辑帧 CPU 耗时可能增加，status 的 peakBuildNs/replacementBatches/replacementFallbacks 可观察。network readSectionList 的 light-only rebuild 不再推进几何版本。

0.6.0 历史实现（已由下述 0.7.0 替代）：默认 `/voxellight sun world`：按 native SkyRenderState 选择当前主导太阳/月亮，方向按 0.025° 取整，变化立即使 64 个 tile 全更新。雨天减弱对比、月亮较弱且依月相、近地平线淡出；world 模式只额外调暗朝向光源的表面。`/voxellight sun fixed` 保留 0.5.1 固定光向/对比与比较路径。`shadow_cache off/on` 在两个 sun 模式中仍使用相同取整方向/强度/材质/投影。保持 24 格接收与 125 section caster 窗口、20 MiB map，不承诺真实太阳光分离或实机性能。

## 0.7.0 连续光源、月光与人工光源（历史）

修复路径：取消世界光方向取整和旋转时按全局坐标 texel resnap；shadow anchor 带 8 格滞回，避免小幅往返换 map。重建法线接近轴向时吸附为真实方块面，掠射面更宽淡出，连续 5×5 PCF（36 次 comparison）降低 raster shimmer。没有 temporal history，不承诺消除薄模型/深度边缘所有 aliasing。

月光不再只是调暗已有画面：满月最高 0.22 的遮影强度加冷色 fill，仍按月相、雨天和高度衰减；新月没有月光。`/voxellight local_lights on|off` 独立切换人工光源（默认 on，随 shadow 模式运行）。torch/lantern/soul 等由实际 emission 选取颜色，覆盖最多 16 个光源、每 section 64 个 4×4×4 cell 代表；GPU 80³ R8 atlas 对 full-block 做 DDA 遮挡，未知空间挡光。不处理手持/实体灯或透明/半砖精细遮挡，不是 GI；vanilla lightmap 保留，因此是额外的局部 fill。

资源与实机检查见 [安装说明](docs/INSTALL.md) 与 [阴影/局部灯预算](docs/SHADOWS.md)。GPU 成本尚无实机数据；缓存关闭仍使用相同连续方向、PCF 与局部灯。

## 0.8.0 重叠阴影范围与视角稳定性

三张 map 为 near 2048²/±32 格、middle 1024²/±64 格、far 1024²/±96 格，depth span=255 格；共享当前 sun/moon、同一组独立 caster 和带滞回 anchor，各自拥有 tile cache。距离按重建世界位置的球面半径选取，12–16、26–32 格平滑 blend，40–48 格平滑退出；默认更远、更软的阴影不代表整个视距或所有远处 caster 完整覆盖。map+write-disabled attachments 共 30 MiB。

`/voxellight shadow_distance 24` 和 `48` 用于隔离距离范围影响（允许 12..48 格）；只改 receiver fade，不换 caster/projection/filter。`/voxellight mode shadow_ranges` 显示 near 绿/middle 橙/far 蓝和渐变，转头时同一个位置应保持范围颜色；`mode shadow` 恢复照明。`shadow_map` 改为 near/middle/far 三个横向 panel。

两步 depth extrapolation 选择属于当前 plane 的邻居，避免单纯选深度最近的一侧在某些视角切到别的方块；near-axis normal 用连续混合代替 >0.98 硬吸附。预算不足的 section 记录 token/请求字节，远处 casters 可为更近 geometry 腾出空间，不互相来回替换、不反复编译已知无法容纳的同版本数据；保留有效遮挡并报告 partial coverage。其视觉效果、薄模型边缘和实际 GPU 成本仍需实机验证。

0.9.0 默认使用半砖/楼梯/栅栏的原生遮挡形状。`/voxellight light_occlusion shapes|full` 可与此前 full-block 行为比较；详细预算与实机检查见安装说明。

0.10.0 增加独立太阳/月亮动态实体模型阴影：`/voxellight entity_shadows on|off`。最多 32 个附近实体、1 MiB/frame 模型顶点，使用原生动画/纹理 cutout；具体支持范围与 24 MiB 增量深度层预算见安装说明。

## 0.11.0 Material diagnostics（B1）

新增 `/voxellight mode albedo`、`surface_normal`、`emission`、`material_flags`、`material_coverage`。捕获原生 quad geometry normal、未照明 texture/tint 与 emission strength，使用三 target MRT 和独立 reversed-Z；仅局部 terrain，支持 coverage 可观测。现有 `shadow` 与 depth `normal` 保留；0.12.0 新增分离 lighting/HDR，见下文。详细范围、显存和实机门槛见 [安装说明](docs/INSTALL.md)。

## 0.12.0 separated terrain lighting（B2）

`/voxellight mode foundation` 从 unlit material/真实 normal 计算 sun/moon、hemisphere sky、block/local light 和参考 emission，输出 linear HDR，再 tone map 与 native fog。太阳阴影只影响 direct term；unsupported geometry 保留 native，entities/transparency/UI 随后合成。`mode shadow` 是旧版比较，`mode off` 恢复 vanilla。无 SceneColor copy，现有 material/caster 窗口与预算仍有限，实机验收待完成。具体光照模型、显存与测试步骤见 [安装说明](docs/INSTALL.md) 和 [Foundation contract](docs/VISUAL-FOUNDATION.md)。
