# VoxelLight 26.2 接入原型

版本：0.21.1。仅客户端，不安装到 Paper 服务端。此版本提供局部太阳/月亮地形阴影、三层局部 tile 缓存、连续 comparison PCF 和 emissive-block 人工灯，新增 material/GBuffer diagnostics 与 `foundation` 分离 HDR terrain lighting，加入有界 native block-entity 模型阴影，尚无分页 clipmap 或 GI；默认关闭，功能开关不跨游戏启动保存。

## 安装

1. 创建 Minecraft Java **26.2** 的 Fabric 客户端，使用 Java **25**、Fabric Loader **0.19.5** 或兼容的新版本。
2. 安装 [Fabric API 0.160.0+26.2](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.160.0+26.2/fabric-api-0.160.0+26.2.jar)。已有兼容的 Fabric API 时无需重复安装。
3. 将安装包 `mods/voxellight-client-26.2-0.21.1.jar` 放进该客户端的 `mods/`，替换旧版 VoxelLight，保留其他前置。
4. 视频设置中选择 Vulkan，然后进入测试世界。首次验证使用 vanilla 材质和不含其他 renderer mod 的独立测试配置。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/voxellight` 或 `/voxellight status` | backend、设备/驱动、pass 状态、timestamp、scratch 字节和样本数。 |
| `/voxellight mode color` | 世界颜色复制并重新绘制；预期视觉上与 off 一致，用于发现翻转、采样或色彩差异。 |
| `/voxellight mode depth` | 世界 reversed-Z 的对数灰度诊断，非线性距离；天空预期为黑色。透明物体可能不写深度。 |
| `/voxellight mode albedo` | 0.11：未照明 terrain texture × biome/material tint；不含 face shading/AO/lightmap/fog。 |
| `/voxellight mode surface_normal` | 0.11：真实 quad geometry 的世界空间 normal，不读取相邻屏幕 depth。 |
| `/voxellight mode emission` | 0.11：block/model emission strength 的灰度图，非 emissive RGB radiance。 |
| `/voxellight mode material_flags` | 0.11：cutout 红、tint 绿、animated 蓝；unshaded 添加灰色标记。普通 opaque flags=0 为黑。 |
| `/voxellight mode material_coverage` | 0.11：green=支持且 depth 匹配，magenta=已捕获但 depth 不匹配，gray=未捕获。 |
| `/voxellight mode normal` | 用深度和当前 projection 重建表面方向并着色；天空黑色，无新增世界光照。 |
| `/voxellight mode shadow` | 世界太阳/月亮阴影、月光 fill 与人工灯；自动启用 scene，caster 准备完成后生效。 |
| `/voxellight caster_volume light` / `cube` | 默认 light；沿当前光源方向选择已加载地形 caster，cube 恢复旧 7³ 窗口供比较。 |
| `/voxellight look polished` / `reference` | foundation 默认polished；reference恢复0.17光照/Reinhard输出，关闭新增bloom/exposure/距离blend；AO/history保持独立。 |
| `/voxellight water on` / `off` | polished foundation默认on；近处有HDR背景的Fast/Fancy水面：Fresnel/absorption/sky reflection/refraction；水下/Fabulous/缺背景native。 |
| `/voxellight atmosphere on` / `off` | polished foundation默认on；局部高度/距离haze和朝向光源的analytic glow，非shadowed光柱；水下/非主世界native。 |
| `/voxellight atmosphere_density 0.002` | 默认0.002，允许0..0.08；可用0.03放大比较，0无haze。 |
| `/voxellight exposure 0.75` | polished默认+0.75EV，允许-2..2，手动曝光，无自动变化。 |
| `/voxellight bloom on` / `off` | polished默认on；已捕获torch/glowstone等terrain emission的quarter-res glow，非lava/透明/天空bloom或GI。 |
| `/voxellight coverage_blend on` / `off` | polished默认on；24–32格连续淡回native，避免5³材质窗硬边；off比较原receiver范围。 |
| `/voxellight ao on` / `off` / `view` | foundation terrain AO，默认on；view自动进入foundation并显示AO灰度，on恢复正常光照。 |
| `/voxellight temporal_shadows on` / `off` | foundation directional visibility history比较，默认on；静止画面可能无差别，非full TAA。 |
| `/voxellight entity_materials on` / `off` | 默认on；支持的opaque实体模型使用材质/normal分离lighting，off保留native实体颜色；不关闭terrain或实体阴影。 |
| `/voxellight entity_shadows on` / `off` | 默认 on；开关新增太阳/月亮实体模型阴影，保留地形阴影与原生 blob shadow。 |
| `/voxellight light_occlusion shapes` / `full` | 默认 shapes；比较人工灯形状遮挡与 full-block 参考。 |
| `/voxellight held_lights on` / `off` | 默认on；玩家任一手持emissive BlockItem产生一盏动态灯，优先较亮手，包含于16源预算；需要local_lights on。 |
| `/voxellight local_lights on` / `off` | 默认 on，独立开关新增人工灯；保留 vanilla lightmap 和 sun/moon。 |
| `/voxellight sun world` / `fixed` | 默认 world，跟随当前原生天空角度；fixed 保留旧版固定光源用于比较。切换清空计时样本/缓存，保留 caster。 |
| `/voxellight shadow_cache on` / `off` | 默认 on，复用有效局部 map；off 为相同画质/投影的每帧重绘参考。切换清空计时样本与缓存计数。 |
| `/voxellight shadow_distance 48` | 默认 48 格；允许 12..48，只改方向阴影/月光 receiver fade，不改变人工灯范围。 |
| `/voxellight mode shadow_ranges` | near 绿/middle 橙/far 蓝，显示世界球面距离 blend 与外圈 fade；用 mode shadow 恢复。 |
| `/voxellight mode shadow_mask` | 白=无遮挡/范围外，黑=遮挡；手/HUD 保持原样。 |
| `/voxellight mode shadow_map` | 横向三个 panel 依次为 near/middle/far 普通深度，空区域白色。 |
| `/voxellight scene on` / `off` | 开关局部 CPU 场景跟踪；与视觉 mode 独立，默认关闭。 |
| `/voxellight scene` | world/resource generation、resident/dirty/inFlight、内存与 CPU 时间。 |
| `/voxellight scene inspect` | 准星方块快照中的 state ID、材质 flags、emission 与 section 统计。 |
| `/voxellight mode off` | 关闭 pass 并释放自有纹理、caster 顶点 buffer、CPU builder/query pool；scene 开关独立。 |
| `/voxellight export` | 在游戏目录 `benchmark-results/voxellight/` 写 pass CSV、scene 汇总 CSV 和设备状态说明。 |

CSV 的 CPU 字段只表示该 pass 的命令准备时间，GPU 字段表示 color copy + draw 或 depth/normal draw，shadow 模式则表示颜色复制 + shadow map + resolve，不包含 caster 编译/上传，不是整帧耗时。status 中 lastBuildNs 单独记录最近 caster 编译/上传 CPU 时间。GPU 结果延迟读取，未完成/不支持时留空；导出前让场景继续渲染几帧。模式切换、窗口尺寸变化、世界切换清空样本；先导出再切换。最多保留 14,400 条，导出发生在命令执行时，不在每帧写文件。

OpenGL/未知 backend 或不支持的 scene format 保留原生画面，status 会显示原因。shader/pass 出错时自动关闭并写日志，下一帧恢复原生渲染；可用 mode 命令重试。已有 vanilla spectator/post effects 会继续处理诊断结果。

当前scope和预算汇总见[CURRENT.md](https://github.com/jurky123/mineRenderer/blob/main/docs/CURRENT.md)。下方按版本列出的检查保留历史参数；最新差异见文末0.21.0。

## 实机 smoke checklist

- Vulkan 启动/退出世界无 crash，color 与 off 的同场景截图无翻转/亮度差异。
- depth 在主手渲染前读取真实世界深度；手和 2D HUD 不被改成深度灰度。
- 改变窗口尺寸、fullscreen、F3+T resource reload 后画面正确，无陈旧纹理。
- 切换维度、teleport、退出并重进世界，状态/样本与资源数量可解释。
- 连续切换 off/color/depth；off 的 scratchBytes 为 0，画面立即回到 vanilla。
- OpenGL 下请求 color/depth 无诊断 draw、无 Vulkan 对象访问，原画面可用。
- export 中 CPU/GPU 数值与 profiler 对比，空值不伪报为 0；记录 GPU、驱动、视距、分辨率和测试路线。

用户已确认 0.1.2 depth、0.2.0 normal/scene、F3+T 和下界切换。0.3.0 阴影与完整 resize/颜色/计时检查仍待实机验证；构建环境没有 GPU/显示设备。

## 0.2.0 新功能验证

1. Vulkan 世界内运行 `/voxellight mode normal`：应看到随表面朝向变化的彩色平面；原手和 HUD 保持原样。此视图基于深度近似，不能验证资源包 normal map。
2. `/voxellight scene on`，等待约数秒，再 `/voxellight scene`：`resident` 应增长到局部窗口内的 `tracked`，最多 125。空 section 也有快照。
3. 放置/破坏方块、放置/移除火把后再次查看 scene，`accepted` 应增长；准星对准新方块运行 `scene inspect` 检查 state ID/emission 更新。flags 位：1=full occluder，2=non-air，4=fluid，8=non-model。
4. F3+T 后 `resourceGen` 增长并重建快照；切换维度后 `worldGen` 增长；飞行只跟踪新的局部窗口，`tracked <= 125`、`queuedJobs <= 2`。
5. `/voxellight export` 导出 scene 汇总。完成后分别关闭 `scene off` 和 `mode off`，前者清空 CPU 快照，后者释放诊断 GPU 资源。

scene 使用 CPU，可在 OpenGL 下单独验证；视觉 normal/depth 仍要求 Vulkan。镜头 teleports/切维度后不复用旧 section；0.2.0 normal、初始 scene、F3+T 和维度切换已获用户实机确认；编辑/长距离飞行与 0.3.0 的新行为仍需单独检查。

## 0.3.0 阴影验证

1. 主世界白天、平地上搭一根 3–5 格高的柱子，站在附近运行 `/voxellight mode shadow`。scene 自动开启；保持位置，等待数秒，`/voxellight status` 应显示 `terrain lighting active`、`casters=N/N`，柱子附近出现斜向阴影。0.6.0 默认随原生 sun/moon angle；需要旧参考时先运行 `/voxellight sun fixed`，光源为 `(0.6, 1, 0.35)`。
2. 接收距离仅相机附近 24 格，16–24 格淡出。转动镜头使柱子离开画面，但地面仍在画面内：其阴影应保留。放置/破坏柱子与 section 边界方块：重建期间保留当前有效 caster 的阴影；实际缺失的 caster 仍可能暂时漏影。
3. 测试半砖、栅栏和树叶。cutout 以 atlas alpha 0.5 裁剪；水、玻璃等 translucent layer 不投影。实体/箱子等单独渲染的模型暂不投影。
4. `/voxellight mode shadow_mask` 应显示遮挡黑白图；`shadow_map` 应显示光相机深度而非原世界画面。shadow/shadow_mask/shadow_map 之间切换复用 caster；不会重新暖机。
5. F3+T、切维度、飞行与 resize 后不能出现上一世界/资源的影子。无 skylight 维度（如下界）保留原来的天空/环境并只加局部灯，status 显示 local lights only；回主世界后重新准备。scene off 暂停阴影；mode off 恢复原画面，需停止 CPU scene 时另运行 scene off。
6. 记录 status 与 export。shader/预算失败会自动退到 off，查看客户端日志；无 GPU 性能承诺。

这是颜色乘性合成的地形参考实验：会一并调暗原画面的 lightmap/fog/发光色，尚未分离真正太阳光。主世界阴影结果还未在本环境实机运行；不要把本构建视为完整 P1a 的画质/性能验收。

## 0.5.0 tile 缓存与采样验证

1. 替换旧 jar 后运行 `mode shadow`。2048² map 的 texel 约 0.046875 格，连续 3×3 comparison PCF 使用最多 16 个实际 texel 比较，避免整数 filter 步进；不是 raw depth 插值或 temporal AA。仍有薄几何、斜面重建误差，不保证所有条纹消失。
2. 在附近没有 cutout（树叶、草等）的石头场景暖机，查看 status：mapRenders 应稳定，mapReuses 应增长，mapReason=valid tiles reused、updatedPages=0/64。含 cutout 时仅其投影 tile 每帧重绘（mapReason=cutout animation safety），其余页继续复用；一个大模型或密集 cutout 仍可覆盖全部 map。
3. 在同一 8 格世界 cell 内走动和转头，缓存投影保持固定；跨 cell 会立即重绘。接收距离仍为 24 格、16–24 格淡出，caster 窗口仍是 125 sections。编辑/移除柱子后 mapReason 可显示 casters changed，updatedPages 应反映实际脏 tile，通常少于 64（大范围更新可能全部）；不得复用失效几何。
4. 使用 `shadow_cache off`、`on` 比较同一路线：两个路径保持同样的 map 大小、世界 anchor、caster 集、bias 和过滤；每次切换先导出原样本。静止截图/mask 应一致。动画 cutout、F3+T、resize、切世界和 teleport 都要检查，不能出现旧世界影子。
5. map attachments 合计 20 MiB（D32 16 MiB + R8 4 MiB），另有既有 geometry、scene scratch 和 uniform。缓存没有额外 history target。export txt 的 mapRenders/mapReuses 是本次计数，CSV 仍测量整个 shadow pass（copy + 可选 map + resolve）；尚未实现完整 frame benchmark。

本版本是单层固定 atlas 的 tile 更新，三层 clipmap/虚拟分页/时间预算、动态 caster 和变化太阳仍待开发。构建通过不等于 GPU 画质或性能验收。

编辑后刷新是正确行为：几何改变必须使旧深度失效。0.5.0 只清空/重建受影响的 tile，其他页保持有效。0.5.1 对最多 8 个已有 mesh 的修改同帧替换，不等待 occupancy 编码。新驻留仍每帧一个；大组修改、复杂模型超出 staging 或缺邻居仍可能短暂漏影；不跨帧保留旧 caster 来掩盖延迟。验证时反复增删柱子，让两个 caster 的影子重叠，移除较近者后应显露较远者的影子。边界编辑、草/树叶、快速移动、F3+T 后分别与 cache off 的 mask 比较，检查 tile 边缘无缝、无残留影子。

## 0.5.1 局部编辑刷新验证

1. 保持站位，等待 casters=N/N，在 section 内部和边界反复放置/破坏方块。成功路径 status 显示 replacement=same-frame edit replacement、replacementBatches 增长、lastReplacementSections=1..8；修改对象的影子应更新，未修改的 section 几何不应先整块消失。移除后不能留下旧 block 的影子。
2. 放置/移除火把，等待服务器 light packet；独立 relighting 不应导致随后一大片 caster 再次失效。真正方块 state/邻居模型变化仍更新几何；树叶/草的 cutout tile 仍逐帧更新，别用 pageUpdates 判断 light 是否触发几何重建。
3. 大范围编辑/大量邻居改变可能显示 fallback: more than 8 edited sections、unloaded model neighbors、replacement geometry budget 或 superseded edit；这些路径明确允许局部暖机缺影。已经不等 CPU scene resident=N/N 才生成 caster，二者短暂不同是正常。
4. 比较编辑前后帧时间，status/export txt 的 lastBuildNs 是最近一个 geometry 构建/替换组 CPU 时间，peakBuildNs 是本次最大值，不在 pass CSV/GPU timestamp 中。replacementBatches/replacementFallbacks 为累计，lastReplacementSections/replacement 为最近事件。最多八个同步编译并非 CPU 硬时间预算；可能用一次较长的编辑帧换掉多帧缺影。
5. F3+T、切世界、unload/reload 同坐标和飞行不复用旧世代的 caster。32 MiB resident + 最多 8 MiB replacement staging 为 live owned geometry 上限，map 仍 20 MiB；游戏 backend 延迟销毁/staging 不计入此 payload 上限。

无需新命令；保持 mode shadow。新增机制须实机复测，本环境无法证明所有局部闪烁已经消失。

## 0.6.0 世界太阳/月亮验证（历史参数；当前见 0.7.0）

1. 运行 mode shadow，默认 sun world。在早晨/下午比较柱子投影方向，应随 native 天空方向变化，正午影子可能很短。status 的 sun=world、lightSource=SUN/MOON/NONE、lightAngleDeg、lightStrength 说明当前选中的 light。
2. 夜间使用较弱的月亮 shadow（full moon 最强，new moon 没有额外月影）。晴天太阳最大调暗 0.45，满月 0.12；雨天最低为各自的 25%。这是乘性合成的艺术策略，尚未从原生颜色分離物理 direct sunlight。
3. 近地平线将 light 强度在 elevation y=0.10..0.25（约 5.7°..14.5°）淡入，避免有限 caster window 产生极长但缺失的阴影。没有高于该阈值的 celestial 时保留 vanilla，status 给出原因；mask/map 此时也不执行。无 skylight 或非 OVERWORLD skybox 在 world 模式回退。
4. world 模式用深度重建的、朝向相机的表面 normal，对背光/掠射面不做额外 shadow 调暗；normal 是近似，透明 receiver 等限制照旧。fixed 模式保留上一版比较行为。不要把 world/fixed 图像差异当 cache 错误。
5. 阳光方向按 0.025° steps 取整，最大角度偏差 0.0125°，raw strength 仍连续变化。shadow_cache off/on 两者用同一方向/投影，不靠降低 cache 画质获取复用。mapReason=celestial light moved 时全图更新是正确的；只改变天气/强度而方向相同则不会仅因此全更新。
6. 日出/日落、太阳移动、time jump、雨天、满月/新月、block edit、F3+T/维度切换分别比较 cache on/off 和 mask。`sun fixed` 可恢复前版固定 light 以检查 edit/alias，`sun world` 恢复新的行为。先 export 再切换 sun/cache，计时样本会清空；world Sun 的重绘频率与 fixed benchmark 不同。

map/geometry 上限和编辑替换上限不变，没有新增 texture/history/GPU wait。需要实机确认阴影朝向/接缝/移动变化与 GPU 开销，尚未实现远层 clipmap、动态 entity caster 或完整 benchmark。

## 0.7.0：闪烁修复、可见月光与局部人工灯（历史；当前见 0.8.0）

上述 0.5/0.6 参数是历史记录。当前 world sun 不取整 render angle，也不对旋转投影按全局坐标重新吸附；angleStep 仅用于旧测试/诊断精度，cache key 是连续真实 angle。方向改变每帧全 map 更新，fixed light 仍可复用；8 格 anchor 滞回避免在 cell 边缘来回切 map。法线近轴向时稳定为 block face；掠射光源使用更宽 smoothstep，PCF 为连续 5×5/最多 36 texel comparison，bias=0.00025。没有 temporal AA/history。

```text
/voxellight mode shadow
/voxellight sun world
/voxellight local_lights on
/voxellight status
```

1. 白天暖机至 casters=N/N，缓慢移动/转头，也在 x/z 为 8 的整数倍附近往返；静止等待太阳移动。比较 `/voxellight sun fixed` 与 world。检查 shadow_cache off/on，二者应保持同样画质。导出 status/CSV 后可定位 geometry、map 更新和 GPU 时间。
2. 满月午夜在无遮挡的平地/柱子附近，应出现冷色 fill 和月亮方向的阴影。可在有权限的测试世界运行 `/time set 18000`、`/weather clear`；日零是满月，其他日期/月相会改变强度。status 应显示 lightSource=MOON、满月高空 lightStrength 接近 0.22。新月/地平线以下没有月光；下界没有太阳/月亮，不补一个假月亮。moon fill 在 16–24 格接收范围淡出。
3. 在洞穴放置普通火把、灵魂火把和 glowstone，等待 lightSections 暖机；运行 local_lights off/on 比较新增的暖色/冷色 fill。off 不关闭 vanilla lightmap，也不关闭 sun/moon。灯半径不超过 12 格，接收范围仍为 24 格。手持/掉落物/实体发光由原来的游戏/mod 行为决定，本版本不读取这些光源。
4. 在灯和地面之间放一面完整不透明方块墙，把墙转出画面再检查；新增光不得因为墙离开画面而穿墙。移动/破坏/放置灯与墙，普通编辑最多 8 个驻留 section 同帧更新 occupancy；大组编辑先变为 unknown，可能短暂缺灯，不允许陈旧灯继续照射。强光分组每 4×4×4 block cell 选一个代表，不是每个 emissive block 单独一个灯。
5. 密集灯阵最多 16 个 active slot，按 emission/距离和保留优势选择，新增/淘汰 0.25 秒淡入淡出；单帧 dt 上限 0.1 秒。移除/卸载灯则立即移除，不保留假余光。首次采集最多一个 section/帧；edited group 最多 8 个；只读已加载 chunk，不请求新 chunk。
6. 运动、传送、F3+T、切下界再返回后检查颜色/遮挡/灯位置。mode off 释放自有资源；scene off 停止跟踪。status 新增 activeLights、lightCandidates、lightSections、voxelBytes/voxelUploadBytes、lightExtractNs/lastVoxelPrepareNs；固定位置且 geometry 不变时 voxelUploads 应停止增加，纯 LIGHT packet 不重建 occupancy。

人工光源只对完整 solid-render block 遮挡；slab/fence/cutout/glass、水和实体未做精细体素遮挡。占用 atlas 为 800×640 R8（512,000 字节），覆盖 80³ cells；unknown/outside 挡光，DDA 最多 48 cell 步。不替换 vanilla block light，新增照明使用已照亮 LDR 色估计接收颜色，不能消除 vanilla 本身的漏光/正确重照材质/恢复 HDR。满月冷色 fill 也不是物理天空或 GI。

本机仅完成 build、CPU 逻辑、最终 jar GLSL→SPIR-V/真实 binding 和 160/560 字节 uniform reflection 验证。没有显卡实机 flicker 修复或帧率验收；请保留截图与导出结果，特别是 thin/cutout 与快速编辑场景。

## 0.8.0：三层阴影与视角突变检查（历史）

0.7.0 已获用户认可，但仍有某些视角的阴影突变。本次 world-distance 范围扩大至 48 格；三层 map 独立 cache，near 2048²/±32、middle 1024²/±64、far 1024²/±96；12–16、26–32 格重叠 blend，默认 40–48 格淡出。人工灯仍是 80³/近处 125 section、最多 16 灯、receiver 24 格，避免扩大灯 atlas/trace 成本。

```text
/voxellight mode shadow
/voxellight sun world
/voxellight shadow_distance 48
```

1. 保持位置慢慢转头，复测之前出现突变的物体。用 `/voxellight mode shadow_ranges` 查看范围颜色：近绿、中橙、远蓝；同一表面的球面距离不应因为转头/FOV 而跳到另一层。重叠区颜色连续过渡，超出最终 fade 的地方变暗以标记没有额外阴影；天空为白色。恢复 `/voxellight mode shadow`。
2. 比较 `/voxellight shadow_distance 24` 与 `48`（允许整数 12..48）。距离开关只改变 receiver 最后 8 格 fade，保存 caster/projection/PCF，清空 pass samples。24 格版在 16–24 淡出；48 格版在 40–48 淡出。如果同一位置在两个距离下仍随视角突变，则不能归因于单纯的距离开关，请保存截图/status/export。
3. 沿柱子/树的阴影缓慢穿过 12–16、26–32、40–48 格区域，检查只逐渐变软/淡出，没有突然丢失整个区域；正午、斜阳、满月与 fixed sun 均测试。map 范围对支持的 receiver 留足 filter guard；map 最边缘另用 3–8 texel fade。低太阳角仍可能缺窗口外 caster。
4. `/voxellight mode shadow_map` 显示横向 near/middle/far 三张 map。shadow_cache off/on 应采用同样的三层投影、caster、距离、filter 和灯；每帧参考会重画所有 96 个 tile（64+16+16）。实际太阳移动也会全部失效；没有用旧太阳方向延迟外层。
5. scene status 现在为 7×7×7、最多 343 个已加载 section（上下世界高度会减少数量）；CPU palette worker 仍是两个 job、每 tick 两份 copy 和 2ms 软预算。不要再以固定 125/125 判断方向阴影暖机。模型 warm 为一份/帧，小编辑至多 8 section 同帧；未加载邻居仍暂缺。
6. geometry steady 仍为 32 MiB；overbudget 单 section/窗口保留当前有效 casters，报告 budgetDeferred，优先为近处 geometry 淘汰更远 casters。没有空间时不来回替换同等优先级数据，也不每帧重新编译同一个无法容纳的版本。partial coverage 可能长期存在；未发布 geometry 不声称全覆盖。
7. 检查飞行、section/anchor 边缘往返、方块编辑、F3+T、下界来回、resize、mode/scene off/on 与灯开关。status 增加 cascades=3、shadowDistance、mapSizes=2048/1024/1024、updatedPages=N/96、budgetDeferred/budgetEvictions。导出的 CPU/GPU 样本含三张 map 更新、复制、voxel upload/resolve。

视角修复另外使用两个邻居深度的 extrapolation error 判断同一 plane，避免单纯选最近 depth 在斜视角吸到旁边方块；axis normal 稳定改为 smoothstep(0.94,0.995) 连续混合，不再在 0.98 切换。没有真实模型 GBuffer normal/velocity/history，因此薄模型、subpixel 边缘、缺失 caster 或低帧率下的 aliasing 仍不承诺全部消失。

map+attachment 从 20 增为 30 MiB；caster GPU uniform 3×160 字节，resolve 304 字节，人工灯 560 字节/R8 512,000 字节不变；caster geometry 32 MiB steady、temporary replacement peak 40 MiB 不变。三层动态光更新会增加 draw/filter 开销，没有实机帧率结果。本机 build/CPU projection+cache+residency、最终 jar mixin/GLSL binding/reflection 测试通过，不替代实机验收。

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

## 0.11.1 material capture hotfix

修复 MRT capture 缺少 `RenderPassDescriptor.renderArea` 导致所有 material 模式失败并自动回到 OFF。替换旧 jar 后重新选择 `MATERIAL_COVERAGE` 或 `SURFACE_NORMAL`；此错误与 Iris、认证失败无关。新增已打包 descriptor 的全尺寸/窗口尺寸变更回归检查；实机画面仍需验收。

## 0.11.2 material input and cutout fixes

修复未使用 UV2 导致 native Vulkan 压缩 shader input location、Normal 读取错误 attribute。UV2 现在保留 skylight strength 到 emission B，回归检查实际 SPIR-V input location 与完整 vertex format 一致。capture 使用原生 opaque terrain sampler，并匹配 vanilla nearest/RGSS 采样与 cutout alpha；不放宽 depth rejection。ALBEDO 无阴影是预期诊断行为；SURFACE_NORMAL 应显示面方向颜色。植物紫色表示捕获深度不匹配，实机仍需复测。

## 0.12.0 B2 separated terrain lighting

0.11.2 的 material diagnostics 已获用户确认。新版默认仍 OFF，进入 Vulkan 世界后运行：

```text
/voxellight mode foundation
/voxellight sun world
/voxellight status
```

等待数秒 materialSections/casters 暖机。此模式以 unlit albedo/实际 geometry normal 分离太阳/月亮、hemisphere sky、block/local light、emission，RGBA16F HDR → 固定 exposure 1/Reinhard → sRGB display encode → native fog。太阳遮挡只调制 direct illumination，不压暗 emission 或火把 baseline。`mode shadow` 可比较旧版，`mode off` 看原版；材质诊断命令保持可用。

验收顺序：

1. 白天在建筑阴影中的墙边放火把，比较 foundation/shadow/off；遮住阳光后墙应保留火把照明。glowstone/sea lantern 等已支持材质 emission 不应跟太阳 visibility 一起压暗。
2. 正午/低太阳/月夜/新月与雨，洞穴和 Nether；月亮为较弱冷色 direct，夜 sky ambient 独立。`sun fixed` 可固定方向隔离时间变化。
3. 放/破坏火把与遮挡块，移动跨 section、F3+T、resize/fullscreen、切维度。material light-only 更新会撤销旧 surface，限一 section/frame 重建，较大更新暂时回退 native。
4. 检查玻璃/水/实体/particles/weather/手/UI 未被 opaque lighting 覆盖，fog 没重复变浓；测试超过16个 nearby emitters。status 在 foundation 应有 `scratchBytes=0`、`hdrBytes`、materialSections 与 caster/local counters；可 export CSV。

这是有限局部 terrain reference：125 material sections，未支持/深度不匹配 pixels 留 native；entities/block entities/fluids/translucency 自身仍 vanilla lighting。local_lights off 仅关闭选中光源的 colored visibility term；保留 native per-vertex block-light **level** 兼容 baseline，以免16灯限制丢光。baseline squared response 与 stronger selected light 平滑替换而非相加，不是 exact native lightmap/night vision/gamma parity。Emission radiance 以 authored albedo × emission strength ×2.4 定义，尚不支持资源包独立 emissive texture 或 bloom。

MRT emission properties R/G=block/quad strength，B=sky level，A=block level；normal alpha=coverage。HDR8 bytes/pixel，material+HDR32 bytes/pixel；1440p112.5 MiB（不含独立 shadows/local/geometry），combined cap256 MiB、material cap192 MiB，超限保留 native。foundation 不复制 SceneColor；diagnostics/legacy 仍用 scratch。当前同步双套 terrain 构建与现有 PCF/DDA 是视觉 reference，未宣称性能验收。B3 entity materials/block-entity shadows/light-aware caster volume 和 temporal/GI 后续实施。

## 0.12.1 plant stability fix

用户确认 0.12.0 foundation 效果正常，但反馈植物 flicker。Material capture 改为与 native CUTOUT_TERRAIN 相同的 backface culling，避免 opposing plant quads 同时覆盖 coplanar depth。Foundation foliage 改为与视角无关的双面 diffuse/sky response；local visibility offset 朝光源侧，不再按相机翻转 normal。8 ULP depth rejection 与 native nearest/RGSS sampling 保持不变，没有扩大容差或新增 history。

替换旧 jar 后 `mode foundation`，在草/花/树叶附近慢转、移动，尤其绕过 plant plane 的侧面；比较 `sun fixed` 与 `sun world`，附近放火把观察彩光。`mode material_coverage` 检查植物是否出现 green/magenta 来回切换。请同时比较 `mode off`：native cutout 边缘仍可能有 subpixel aliasing，本版未做 TAA/temporal，不能宣称消除全部闪烁。固定相机的 flicker、更新触发的 native fallback 需另行定位。

## 0.13.0 B3a animated block-entity shadows

用户已确认 0.12.1 foundation/plant fix。新版默认 block_entity_shadows on。在 Vulkan 世界执行：

```text
/voxellight mode foundation
/voxellight sun fixed
/voxellight block_entity_shadows off
/voxellight block_entity_shadows on
/voxellight status
```

摆放单/双 chest、ender chest、shulker box 和 banner，观察开箱盖动画、shulker 打开、旗帜动画对应阴影；移到相机外但让投影落在镜头内。也可 `mode shadow` 比较 legacy receiver，`shadow_map` 查看合并 dynamic depth。Native 26.2 BedBlock 已非 BlockEntity，床走已有 terrain 模型 caster，不需要此开关。Sign/其他 block-entity 的 model 部件可进入；文字、item/custom geometry、beam/透明 submit 不支持。

DynamicCasterSystem 统一 entity/block-entity depth，DynamicModelBuffer 统一 bounded 模型顶点/sprite UV/原生动画/错误回滚。扫描相机±4 chunk（最多81个），仅 `getChunk(FULL,false)` 的已加载 chunk，球半径64格；非渲染 block entities 不占32个选择槽。按距离/完整64位 packed BlockPos 稳定排序，每类分别最多32对象、128 model attempts、1 MiB frame/GPU vertex buffer、256 KiB model scratch；同用24 MiB dynamic depth，不新增整套 shadow maps。现有 terrain32 MiB/HDR/material/local预算不变。

独立 `entity_shadows` 和 `block_entity_shadows` 控制；关闭/删除/卸载后下一 frame 重建 dynamic layer，不会保留旧图像，也不使 static terrain tiles 因 lid/flag 动画失效。GPU sequential index buffer 在 terrain cascade draw 后、dynamic pass 前统一取当前较大需求，避免两个 stream 互相增长后持有旧 buffer。模型 renderer 失败撤销该对象的 partial draw，记录失败，不关掉地形效果。

status 新增 blockEntityCasters/Models/Skipped/Failures/Overflow、Chunks/CaptureNs/UploadBytes/BufferBytes。没有 GPU 时自动测试不代替 chest/sprite/动画实机验收。依次测开关、破坏/放置、离开相机、F3+T、切维度、resize、第三人称 mob 并存，检查无残影且 entity baseline 不回归。动态模型仍只为 sun/moon shadow caster，本身继续 vanilla shading；人工灯 DDA 仍不遮挡动态对象。超预算可能缺 caster，未实现选入/淘汰 fade。

B3a 是 geometry coverage 的第一步；entity material/normal migration、block-entity material 和 receiver-driven light-aware caster search 仍未完成，不宣称完整 B3 验收。


## 0.14.0 B3b light-aware terrain caster volume

0.13 block-entity shadows 已获用户实机确认。本阶段改进低太阳/月亮角度的 terrain caster coverage：从实际 render camera 的 48 格 receiver 球沿 receiver→light 方向扩展 16..48 格，光源越低扩展越长。使用 section AABB 与 swept sphere 的相交距离，不扩大整个立方体；无 celestial light 时退回旧 7³ 窗口。投影、receiver fade 和 shadow_distance 保持原有语义。

只从已加载 chunk 选择，先过滤世界高度/缺失 chunk，再准入最多384 sections。附近 ±2 section 的125条优先，随后 receiver 范围，再是向光源的额外 caster；material125/16 MiB、local-light125/80³、terrain32 MiB+8 MiB staging、单 worker/two jobs 保持既有预算。不能保证所有远处建筑投影：section cap、未加载 chunk、mesh budget、暖机和有限 extrusion 仍会缺失 coverage。动态实体/方块实体仍使用64格选择，不受本次扩展影响。

Vulkan 中 `/voxellight mode foundation`、`/voxellight sun world`；在晨昏观察向光源约60–80格的已加载高墙向附近投影，比较 `/voxellight caster_volume cube` 与 `light`，每次等待 caster 暖机。正午/近处小场景可能没有明显差异。测试转动视角、跨 section、编辑远处墙、chunk 卸载、F3+T、切维度、第三人称与 teleport；用 `/voxellight scene` 查看 casterCandidates、casterEligible、casterWindowDeferred、casterExtrusion、casterSelectionNs，以及 shadow 的 geometry budgetDeferred。自动测试通过不能替代这些 GPU 检查。

B3 entity material/normal migration 仍未实施；本版不新增 temporal、GI 或无限距离阴影。


## 0.15.0 B3c actual opaque model materials

0.14 light-aware caster selection 已获用户确认。本版继续B3 geometry/material覆盖：world feature准备期间，在native ModelFeatureRenderer的原model.renderToBuffer调用中tee实际顶点，不重新extract实体、不再次setupAnim/render模型。自有ENTITY36字节格式保存真实posed world-space normal、camera-relative vertex、UV、native未照明tint、overlay与packed sky/block brightness；sprite映射同时应用于native/private流。vertex tint不包含native shader的cardinal shading/lightmap/fog，亮度不是emission。

仅准入已知原生ENTITY_SOLID、ENTITY_CUTOUT、ENTITY_CUTOUT_CULL、ARMOR_CUTOUT_NO_CULL的MAIN_TARGET/QUADS模型。通常可看到牛/猪/僵尸及普通armor；经同一原生模型路径的chest/shulker等opaque模型也可支持。玩家的blended skin、透明显隐、glowing eyes/emissive或dissolve shader、手持item/custom geometry、particles、labels/outline、hand/UI不迁移，仍native。支持的实体材质emission strength=0；fullbright packed light不会伪造emission。hurt/white overlay与native ColorModulator属于材质tint，保留；cutout使用原生texture采样和纹理alpha .1阈值，不复用terrain .5/RGSS。

opaque terrain先完成已有foundation。native solid features绘制后，复用同一MRT清零并捕获opaque models；使用native private/main depth最多8 ULP匹配，再用同帧shadow/light environment resolve到复用HDR，tone/fog一次。保持native主depth不写入，不重做shadow prepare/update，不复制SceneColor（foundation）。随后translucent、glint、blob shadow、water、weather、hand/UI按原路径继续；material diagnostics会额外复制当前color以保留未支持实体。opaque armor trim已先native绘制，因此以私有coverage exclusion保留其native像素；若trim mask预算/捕获失败，整帧实体relighting跳过。任意custom coplanar shader不能宣称完全兼容。

只处理native实际提交的可见模型，最多128尝试、1 MiB CPU frame + GPU vertex、256 KiB单模型scratch；并非新32实体选择器。四个settings buffer共64字节；textures/DynamicTransforms是同帧借用，不持有跨frame列表、不接管其生命周期。复用existing24 bytes/pixel MRT+8 bytes/pixel HDR，terrain/material/local-light budgets不变。off/reload/world/resize释放自有buffer；新frame清空旧pose，无motion vector/history。entity_materials off保持terrain/shadow并撤销新实体流，资源可保留至normal reset。

测试Vulkan `/voxellight mode foundation`，观察行走的牛/猪/僵尸进入/离开建筑太阳阴影，再用`entity_materials off/on`比较实体颜色（terrain应保持同一效果）。看夜间/月光、火把照明与hurt tint；第三人称玩家目前仍native。检查普通/染色armor与trim/glint不消失、胸箱开合、cutout缺口、实体重叠/behind terrain、移除/大量模型、F3+T、resize/fullscreen、teleport/维度切换。`mode albedo`/`surface_normal`/`material_coverage`可直接检查支持模型；late coverage在未捕获区域保留之前的terrain诊断和native实体颜色，不整屏改灰。entity emission图黑色是预期。status看entityMaterialPass/models/skipped/failures/uploadBytes/captureNs/overlayFallback；CSV新增 *_ENTITIES pass行，terrain与实体GPU耗时分列。自动CPU/原生GLSL→SPIR-V/绑定/调用合约已验证，GPU实机尚待确认。

这是一版有界opaque model迁移，不表示所有entity material都已完成；blended玩家/模型与custom submit仍是已知缺口。B3本版实机验证之后再考虑temporal稳定性，不进入GI。

## 0.16.0 D1 temporal directional shadows

0.15.0 opaque entity material lighting 已获用户确认。`foundation` 默认开启 terrain sun/moon shadow visibility history；这不是全画面 TAA。纹理、emission、local lights 与晚绘制实体材质每帧保持当前值。动态 caster 阴影区域、animated/ENTITY material 不保存 history；真实 projection（包括 bob/hurt/nausea）参与 camera reprojection，使用 depth/normal rejection、3×3 clamp 和强变化即时响应。

使用 `/voxellight temporal_shadows off` / `on` 比较。先 `/voxellight mode foundation`，可用 `/voxellight sun fixed` 固定光源；等待 caster 准备完成后沿阴影墙缓慢走动/转视角。目标是降低小幅 PCF/shadow crawl，静止画面可能没有明显差异，不处理植物纹理本身的亚像素 aliasing。检查移动实体、开箱、banner 没有拖影；编辑、teleport、快速转向、F3+T、resize、维度切换应重置 history。

额外四张 RGBA16F texture 共32 bytes/pixel，独立128 MiB cap：1440p约112.5 MiB，4K超限自动保留当前 foundation shadows。另有96-byte settings UBO；不新增 SceneColor copy，不修改 native depth。status 的 temporalSeeds/Reuses/Resets 是 CPU frame admission 数量，不是 GPU 实际接受像素数量。scene/caster/light变化、长帧间隔与 camera cut 会重新seed。实机稳定性仍待验收；full RGB TAA、motion vectors、AO、GI 尚未实现。

## 0.16.1 material refresh stability

这一版不新增AO，画面总体应保持0.16光照。在已有foundation覆盖的洞穴中多次放置/移除火把，检查周围section在light propagation期间不会整片退回vanilla后逐块恢复。`/voxellight mode material_coverage`也可观察保留的coverage；新放/破坏geometry的像素仍遵守native depth匹配，可能暂时unsupported。

status新增materialStale（等待replacement的resident数）、materialReplacements与materialDiscarded。每帧仍只编译一个surface；stale的packed light/material短暂保留，超大/超预算replacement可延后。16 MiB steady geometry外最多1 MiB temporary replacement，world/F3+T/卸载/window退出立即清理旧surface。检查resource reload、移动跨section、快速编辑和维度切换没有旧块残留。

纯LIGHT dirty不再reset directional history，实际torch geometry编辑仍reset；D1支持范围和128 MiB cap不变。CPU上传actual inverse projection与light normal matrices；输出应与此前一致。完整ghosting/cascade边界验证仍需实机，用户对0.16改善目前无明显观察；下一视觉阶段为Basic AO。

## 0.17.0 basic terrain AO

用户已确认0.16.1稳定性修复。本版新增半分辨率horizon AO，默认随foundation开启。第一版仅opaque terrain；cutout植物、unshaded、实体receivers以及不支持的coverage保持neutral。作用于sky/ambient与未被selected lamp替换的block-light fill；太阳/月亮direct、selected local lamp direct和emission保持当前光照。

1. 替换旧jar，进入Vulkan测试世界，`/voxellight mode foundation`。等待material caster暖机。
2. 看墙角、楼梯、树根、台阶和建筑内部，比较`/voxellight ao off`与`/voxellight ao on`。应出现柔和contact shading，空旷平面基本不变。
3. `/voxellight ao view`查看应用后的AO factor：支持的空旷面白，角落/接缝灰。天空和unsupported pixels保留native画面，cutout/entity receivers为neutral白。`ao on`恢复正常HDR/tone/fog。
4. 走动、改变FOV和窗口尺寸（含odd尺寸）、F3+T、编辑方块/放火把/切维度。观察墙角没有整片闪回，轮廓没有大范围黑色halo；植物光照/透明/UI顺序应保持。AO是screen-space参考，会遗漏屏幕外/半分辨率薄细节，无AO temporal，不承诺完全无crawl。
5. 检查发光块与太阳照射面：AO不应把emission或sun/direct lamps一同压暗。

status显示`ao=half-res spatial terrain`、`aoSize`、`aoBytes`。1.5格world radius、最大80 full-res pixel search，4 slices×4 steps×2 sides；5×5 bilateral spatial filter、四guide bilateral upsample，两个RGBA16F half targets总32 MiB cap（1440p14.1 MiB，4K31.6 MiB）。另16-byte neutral target +32-byte settings。超预算只关闭AO并保留foundation。无额外SceneColor copy/native depth写入，无新增history；GPU耗时未实机测量。算法范围/参考见[AO.md](https://github.com/jurky123/mineRenderer/blob/main/docs/AO.md)。

## 0.18.0 lighting/color polish 验收

进入`mode foundation`，同一位置轮流`look reference`和`look polished`，比较白墙明暗面、晨昏墙面、夜间torch/glowstone。新增film曲线、手动曝光、天空半球色和emission-only bloom；无需另开scene。`exposure 0.75`为默认，可试`0`或`1`；`bloom off/on`单独比较光晕。`coverage_blend off`可隔离色彩变化，on为24–32格淡回native，旧shadow receiver参数不变。AO view仍灰度。

检查植物/动物、F3+T、resize、下界/回主世界、off后恢复。透明/水/lava、sky halo和未捕获表面仍native；bloom不是GI。status新增look/exposureEV/coverageBlend/bloomBytes。预算和边界见[POLISH.md](https://github.com/jurky123/mineRenderer/blob/main/docs/POLISH.md)。

## 0.19.0 local-light polish 验收

`mode foundation`后在暗处手持torch/soul_torch/lantern/glowstone/end_rod，比较`held_lights off/on`，在主/副手切换、移除物品、第三人称移动、薄墙附近和下界检查。光随玩家而非第三人称相机移动；最多15静态+1手持源，没有额外DDA循环。体素暖机阶段未知区域仍挡光。

颜色由`assets/voxellight/light_materials.json`精确block ID决定，resource pack可替换；F3+T重读并更新局部源。未知ID暖白，非法JSON回退bundled并记日志。检测reload、resize、teleport、local_lights off、退出世界后没有残留。详见[LOCAL-LIGHTS.md](https://github.com/jurky123/mineRenderer/blob/main/docs/LOCAL-LIGHTS.md)。燃烧/掉落物/其他玩家手持灯和多emitter聚合尚未实现。

## 0.20.0 atmosphere 验收

`mode foundation`后在户外10–24格建筑/树林前比较`atmosphere off/on`，低太阳视角和雨天更明显；默认density0.018，可试0.03。检查封闭洞穴、夜晚/月相、植物/动物、手持灯、underwater/lava、Nether/End、F3+T和resize。`look reference`关闭atmosphere，AO view不变。不是shadowed光柱或water rewrite，局部材质窗之外仍native。见[ATMOSPHERE.md](https://github.com/jurky123/mineRenderer/blob/main/docs/ATMOSPHERE.md)。

## 0.20.1 atmosphere 默认密度

用户已确认0.20 atmosphere运行，偏好density0.002；本版采用该默认值。旧版可用`/voxellight atmosphere_density 0.002`获得同一设置。命令范围和其它效果保持原样。

## 0.21.0 HDR Water Foundation 验收

使用Fast/Fancy图形，在24格内浅水、能看到石头/沙子底部的位置运行`mode foundation`、`water off/on`。观察normal ripples、掠射天空反射、随深度增强的吸收和小幅折射。水下、Fabulous独立透明target、未捕获背景/天空和非主世界保留native。不是SSR，不修改fluid geometry。

测试流水/侧面、植物/动物、glass、hand/HUD、underwater、resize、F3+T、切世界和off恢复。`water` active表示shader已接入，不保证全湖每pixel都有HDR背景。保留atmosphere默认density0.002。预算和真实composition边界见[WATER.md](https://github.com/jurky123/mineRenderer/blob/main/docs/WATER.md)。

## 0.21.1 water seams 复测

修复截图中blue grid/dashes：fluid normal derivatives在native alpha discard和每pixel fallback之前计算，取消依赖屏幕尺度的normal cutoff，允许UV边界rounding。请在同一水岸位置比较water off/on、缓慢移动和远近视角，检查blue seams是否消失。HDR背景缺失仍native，不是扩大coverage。
