# VoxelLight release history

历史说明保留当时参数与验收状态；当前状态以[CURRENT.md](CURRENT.md)为准，操作与最新安装见[INSTALL.md](INSTALL.md)。

## 0.18.0 lighting/color polish

- Manual EV and rational filmic tone curve, continuous hemisphere sky colors and weather/night policy.
- Emissive terrain bloom: quarter-resolution extraction + separable blur,16MiB target cap, no extra geometry or SceneColor copy.
- Supported foundation output fades24–32 blocks to native inside the guaranteed material window; main alpha preserved.
- `look polished|reference`, `exposure -2..2`, `bloom on|off`, `coverage_blend on|off`; reference restores0.17 color policy.
- AO confirmed operational by user but visually modest.0.18 visual acceptance remains pending; lava/transparent/sky bloom and atmosphere/water remain outside this phase.

## 0.17.0 basic terrain AO

- Half-resolution horizon AO, depth/normal bilateral spatial filter and full-resolution upsample.
- Ambient/sky and unshadowed block fill receive AO; direct sun/moon/local lamps and emission remain current.
- `/voxellight ao on|off|view`, neutral fallback,32 MiB extra target cap; no AO history.
- 0.16.1 stability accepted by user; AO awaits in-game acceptance.

## 0.16.1 stability follow-up

- Retain stale material meshes until a verified replacement; bounded1 MiB staging over16 MiB resident cap. No cross-world/resource/unload retention.
- Directional history ignores LIGHT-only dirty events, retaining geometry/lifetime invalidation.
- Actual projection inverse and three light normal matrices uploaded once per world frame; shared resolve UBO560 bytes.
- Review prioritizes coverage stability before AO; no temporal expansion or quality/performance claim without in-game evidence.

## Earlier release notes

0.5.0 的 tile 缓存继续推进 P1b：一张现有 map 划分为 8×8 个 256² tile，mesh 增删/失效按实际顶点 AABB 的光空间投影失效旧/新足迹，合并脏 tile 的矩形并重绘所有当前重叠 caster。不新增 map 内存。`updatedPages=N/64`、`pageUpdates/pageReuses/updateRegions` 在 status/export txt 中可见；cell 跨界与 reset 仍全更新，编辑 mesh 重建仍可能暂时漏影。未实现三层 clipmap、虚拟分页/预算调度或动态实体层，实际性能待测。

0.5.1 的编辑修复：shadow geometry 使用客户端当前 world/resource/version token，不再等待 CPU occupancy 编码。最多 8 个已驻留 section 的编辑在一次 prepare 内构建/检查/替换，再绘制脏 tile；新增驻留仍每帧一个。32 MiB steady geometry 外允许至多 8 MiB replacement staging，每 section 仍 4 MiB。大组编辑、预算不足或未加载邻居安全退回局部暖机，可能漏影；不跨帧保留陈旧 caster。单个编辑帧 CPU 耗时可能增加，status 的 peakBuildNs/replacementBatches/replacementFallbacks 可观察。network readSectionList 的 light-only rebuild 不再推进几何版本。

0.6.0 历史实现（已由下述 0.7.0 替代）：默认 `/voxellight sun world`：按 native SkyRenderState 选择当前主导太阳/月亮，方向按 0.025° 取整，变化立即使 64 个 tile 全更新。雨天减弱对比、月亮较弱且依月相、近地平线淡出；world 模式只额外调暗朝向光源的表面。`/voxellight sun fixed` 保留 0.5.1 固定光向/对比与比较路径。`shadow_cache off/on` 在两个 sun 模式中仍使用相同取整方向/强度/材质/投影。保持 24 格接收与 125 section caster 窗口、20 MiB map，不承诺真实太阳光分离或实机性能。

## 0.7.0 连续光源、月光与人工光源（历史）

修复路径：取消世界光方向取整和旋转时按全局坐标 texel resnap；shadow anchor 带 8 格滞回，避免小幅往返换 map。重建法线接近轴向时吸附为真实方块面，掠射面更宽淡出，连续 5×5 PCF（36 次 comparison）降低 raster shimmer。没有 temporal history，不承诺消除薄模型/深度边缘所有 aliasing。

月光不再只是调暗已有画面：满月最高 0.22 的遮影强度加冷色 fill，仍按月相、雨天和高度衰减；新月没有月光。`/voxellight local_lights on|off` 独立切换人工光源（默认 on，随 shadow 模式运行）。torch/lantern/soul 等由实际 emission 选取颜色，覆盖最多 16 个光源、每 section 64 个 4×4×4 cell 代表；GPU 80³ R8 atlas 对 full-block 做 DDA 遮挡，未知空间挡光。不处理手持/实体灯或透明/半砖精细遮挡，不是 GI；vanilla lightmap 保留，因此是额外的局部 fill。

资源与实机检查见 [安装说明](INSTALL.md) 与 [阴影/局部灯预算](docs/SHADOWS.md)。GPU 成本尚无实机数据；缓存关闭仍使用相同连续方向、PCF 与局部灯。

## 0.8.0 重叠阴影范围与视角稳定性

三张 map 为 near 2048²/±32 格、middle 1024²/±64 格、far 1024²/±96 格，depth span=255 格；共享当前 sun/moon、同一组独立 caster 和带滞回 anchor，各自拥有 tile cache。距离按重建世界位置的球面半径选取，12–16、26–32 格平滑 blend，40–48 格平滑退出；默认更远、更软的阴影不代表整个视距或所有远处 caster 完整覆盖。map+write-disabled attachments 共 30 MiB。

`/voxellight shadow_distance 24` 和 `48` 用于隔离距离范围影响（允许 12..48 格）；只改 receiver fade，不换 caster/projection/filter。`/voxellight mode shadow_ranges` 显示 near 绿/middle 橙/far 蓝和渐变，转头时同一个位置应保持范围颜色；`mode shadow` 恢复照明。`shadow_map` 改为 near/middle/far 三个横向 panel。

两步 depth extrapolation 选择属于当前 plane 的邻居，避免单纯选深度最近的一侧在某些视角切到别的方块；near-axis normal 用连续混合代替 >0.98 硬吸附。预算不足的 section 记录 token/请求字节，远处 casters 可为更近 geometry 腾出空间，不互相来回替换、不反复编译已知无法容纳的同版本数据；保留有效遮挡并报告 partial coverage。其视觉效果、薄模型边缘和实际 GPU 成本仍需实机验证。

0.9.0 默认使用半砖/楼梯/栅栏的原生遮挡形状。`/voxellight light_occlusion shapes|full` 可与此前 full-block 行为比较；详细预算与实机检查见安装说明。

0.10.0 增加独立太阳/月亮动态实体模型阴影：`/voxellight entity_shadows on|off`。最多 32 个附近实体、1 MiB/frame 模型顶点，使用原生动画/纹理 cutout；具体支持范围与 24 MiB 增量深度层预算见安装说明。

## 0.11.0 Material diagnostics（B1）

新增 `/voxellight mode albedo`、`surface_normal`、`emission`、`material_flags`、`material_coverage`。捕获原生 quad geometry normal、未照明 texture/tint 与 emission strength，使用三 target MRT 和独立 reversed-Z；仅局部 terrain，支持 coverage 可观测。现有 `shadow` 与 depth `normal` 保留；0.12.0 新增分离 lighting/HDR，见下文。详细范围、显存和实机门槛见 [安装说明](INSTALL.md)。

## 0.12.0 separated terrain lighting（B2）

`/voxellight mode foundation` 从 unlit material/真实 normal 计算 sun/moon、hemisphere sky、block/local light 和参考 emission，输出 linear HDR，再 tone map 与 native fog。太阳阴影只影响 direct term；unsupported geometry 保留 native，entities/transparency/UI 随后合成。`mode shadow` 是旧版比较，`mode off` 恢复 vanilla。无 SceneColor copy，现有 material/caster 窗口与预算仍有限，实机验收待完成。具体光照模型、显存与测试步骤见 [安装说明](INSTALL.md) 和 [Foundation contract](docs/VISUAL-FOUNDATION.md)。

## 0.13.0 B3a block-entity shadows

新增 chest/shulker/banner 等 native model submit 的动态投影，默认开启；`/voxellight block_entity_shadows off` 可比较。来自已加载 chunk 的独立最近32个选择，不依赖 camera visible list；与 mob 共用已有 dynamic depth，单独1 MiB/128 model budget。`foundation`/`shadow`/`shadow_map` 均复用此层。Native26.2 beds 已为普通模型，仍走 terrain。实机检查与边界见 [安装说明](INSTALL.md)。B3 实体材质迁移将随后推进；light-aware caster volume 见0.14.0。


## 0.14.0 B3b light-aware caster selection

默认从48格 receiver 球向 sun/moon 扩展16..48格，优先本地125 sections，只选择已加载地形，scene cap384与terrain32 MiB预算不变。`/voxellight caster_volume cube` / `light` 比较旧窗口；低太阳角度、较远建筑的附近阴影最容易看到差异。scene status显示搜索/已加载候选/限额排除与扩展距离；未加载或预算排除的caster仍可能缺失。完整实机检查见安装说明。实体材质第一步见0.15.0，未进入GI。


## 0.15.0 B3c opaque entity material lighting

实际native ModelFeatureRenderer顶点在原render调用中同步捕获ENTITY格式，保留pose、sprite UV、unlit tint、overlay、packed light与normal，不重新提取/运行动画。支持原生solid/cutout模型与普通armor，部分opaque block-entity模型也走同一路径；在solid feature结束后、translucency前使用同帧shadow/local-light数据完成分离HDR lighting。`/voxellight entity_materials off` / `on` 比较仅新增的实体材质光照，terrain与shadow仍启用。

最多128 model attempts/1 MiB frame/256 KiB model scratch，复用现有MRT/HDR，不新增全屏targets。玩家blended skin、eyes/glow、held item/custom submits仍native；armor trim以coverage exclusion保留native像素，glint/transparency随后native绘制。该阶段支持范围与实机验收见安装说明；用户已确认。

## 0.16.0 D1 temporal shadow stability

`foundation` 默认对静态terrain太阳/月亮阴影visibility做camera reprojection与depth/normal rejection，保留当前texture/emission/local lighting；动态阴影区域不进入history。`/voxellight temporal_shadows off` / `on`比较小幅shadow crawl。新增32 bytes/pixel、128 MiB cap，1440p支持，4K回退当前阴影。编辑/reload/camera cut重置history；不是full TAA。详细验收见[安装说明](INSTALL.md)。
