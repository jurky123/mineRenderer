## alpha.45：Material-Aware Cache 2.1 验收

默认 ROUGH_DIFFUSE 已接入 diffuse cache，精确镜面继续追踪。默认仍 FULL/MONOLITHIC，不自动开启实验缓存。先执行 `/voxellight rt_benchmark material`，确认 query/training/hit 和 B1/B2 实际工作量，再做画质对照与三类场景 production。新增 56 列覆盖诊断、source-depth 隔离、实际请求间接训练和动态槽位 generation。[实现、数值边界与验收](performance/MATERIAL-AWARE-CACHE-21.md)。

## alpha.44：Primary / Cache 2.0 验收

`rt_primary monolithic|split`、`rt_cache tail|primary`、`rt_sampling owen|shift` 为临时执行控制，默认 monolithic/tail/owen。`primary` 缓存需配合 `rt_realtime cache|cache_sparse`；Reference 强制完整路径。先运行 `/voxellight rt_benchmark material`（24 段专项 ABBA），再用 `/voxellight rt_benchmark production`（4 段保留日常 AUTO/实时/OMM/SER 配置的固定视角门禁）。结束恢复配置；production JSON 追加到 `benchmark-results/voxellight/production-history.jsonl`。性能、方差和画质分别验收，详见 [Primary / Cache 2.0](performance/PRIMARY-CACHE-2.md)。

## 0.39.0-alpha.43：输运与场景提交热路径

减少非发光 MIS 查询、RIS 完整材质解码、动态更新驻留表与重复排序。保留 EXACT / Wavefront 默认，FAST 按场景验收。[实测及验收](performance/ALPHA-42-FRAME-ANALYSIS.md)。

## alpha.42 验收修正

默认 EXACT + Wavefront。显式选 `rt_reconstruction dlss`，stats 检查实际 RR 后端、输入/输出尺寸与 rtWorldReason；alpha.41 的上传结果未运行 RR 或世界接管，不能直接作为两项验收。[分析与修正](performance/ALPHA-41-FRAME-ANALYSIS.md)。

## alpha.41 RT / DLSS

实时优先 DLSS RR Performance，实际初始化与输入尺寸请看 stats。新增 `rt_reconstruction dlss`、`rt_world composite|exclusive`、`rt_shadow exact|fast`、`rt_integrator wavefront|iterative`；当前默认 exclusive / exact / wavefront；fast 手动按场景选择。Reference 强制 exact / wavefront。运行 `/voxellight rt_benchmark frame` 导出世界接管、透明阴影和间接执行 ABBA。

RR 活跃时 SDK 决定输入尺寸，`rt_scale` 仅用于 OptiX/Vulkan 原重建。性能/画质仍需客户端验收。[完整说明](performance/CAUSTICA-FRAME-PIPELINE.md)。

# VoxelLight 0.39.0-alpha.45 安装

Minecraft Java 26.2 / Java 25 / Fabric Loader 0.19.5 / Fabric API 0.160.0+26.2。安装包只含本 mod，不重复打包已有前置。

1. 删除旧 VoxelLight jar，将 `mods/voxellight-client-26.2-0.39.0-alpha.45.jar` 放进客户端 `mods/`。
2. 视频设置选择原生 Vulkan 图形 API，然后重启。
3. 进入世界，执行以下命令。

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight held_lights on
/voxellight status
```

实时优先 Vulkan 原生 DLSS Ray Reconstruction；不可用时使用 OptiX / Vulkan 重建回退，实际后端和输入/输出尺寸以 status 为准。安装包包含独立去噪 DLL/SO，无旧追踪器或 PTX runtime compiler；运行不需要安装 SDK。

截图/参考模式单独开启：`rt_mode reference`、`rt_accumulate on`、`rt_accumulate spp 256`。镜头移动、地形或光照变化会清空参考历史；`rt_accumulate freeze on` 显式冻结，`freeze off` 恢复动态更新。`rt_mode realtime` 返回实时重建。`rt_spp 1..8` 现为一帧批处理，不重复上传相机和复制输出。

`rt_scale 0` 保留自动低分辨率；`rt_scale 1..8` 请求指定缩放，实际分辨率受设备 buffer 范围和 continuation 预算限制，请查看 `internalResolution`。性能比较必须保持相同内部像素数与 spp。

`rt_backend vulkan_poc` 查看法线，`vulkan_transport_test` 查看灰色材质输运，`raster` 返回光栅。`preset vulkan_quality` 开启 Vulkan 材质与累积，performance/balanced/quality 是光栅预设。设置界面：暂停/Options → VoxelLight 或 `/voxellight settings`。

实机检查：移动镜头确认没有明显拖影；切换实时/参考模式、窗口大小和 F3+T；暗室测试手持/放置灯；观察实体、箱子、第一人称手持物与 cutout 粒子是否进入 PT。检查高速飞行与反射中的分页请求及区块恢复。原版手持覆盖只在 PT 成功显示且捕获到手持模型时关闭。动态模型目前使用原生颜色/贴图的漫反射材质，透明/additive 粒子、附魔光效和完整动态 LabPBR 尚未接入。

构建主机无 NVIDIA GPU：本版已通过构建、数值和接口检查，OptiX 外部显存/信号量同步、视觉和帧时间仍需 RTX 实机验收。DLSS RR、OMM/SER 暂未启用，待重建稳定并取得 profiling 后评估。

alpha.21：PT 模式旁路光栅效果和材质捕获；动态纹理按稳定分组、同拓扑 BLAS refit、局部几何复制；稳定中心射线 guide 和单次 guide MRT；只执行选中的去噪后端，OptiX 只处理实际使用的 beauty 层。动态物体暂时拒绝历史，真实物体运动向量和 NRD/DLSS 尚未接入。

性能验收请先用 `rt_spp 1`、`rt_scale 0`，保持窗口大小一致，执行 `/voxellight profile on`，等待地形稳定后静止和移动各测试约 20 秒，再 `/voxellight export`。数据位于 `benchmark-results/voxellight/`，关注 `.passes.csv` 和同名 `.txt` 中的实际内部分辨率、动态分组/refit 与复制字节数。切换 `rt_reconstruction vulkan` 可比较纯 Vulkan 后端。要评估清晰度，可另测 `rt_scale 2`，其像素数增加，不能与 scale 0 的性能直接比较。

alpha.22：手持物品按自身 UV 裁切图集，补偿 HUD/世界 FOV 差异；动态几何放到静态地形之后以减少搬移。动态图集回收未使用槽位，避免反复切换物品耗尽槽位。实机外观仍待验收。

alpha.23：纹理裁切槽位与 BLAS 分组分开；按原始纹理和手持/世界类别组合几何。stats 的 `rtDynamicTextureTiles` 表示裁切纹理数量，`rtDynamicGroups` 表示几何分组数，二者无需相等。继续用相同分辨率、1 spp 和同一场景导出 profile 对比；GPU 提速仍待实机确认。

alpha.24：统一 scene commit、稳定几何范围、持久 BLAS/TLAS refit、对象局部平移与上帧位置、纹理版本追踪、hot/cold path 和可选 GPU active queue 已接入。实时降噪后执行独立输出分辨率时域放大；Reference 不执行降噪/放大。profile 导出 `.passes.csv` 包含真实 frame/scope、尺寸、spp 和 scene generation，另有抽样 `.rays.csv`。stats 的 scheduling 表示 fixed batch 或 GPU compact continuation；初始 compaction 阈值为 65,536 paths，尚需实测。输出双 history 额外约 32 bytes/output pixel；`rtBufferAllocatedBytes`/`rtBufferRetiringBytes` 只覆盖本项目 Vulkan buffer。NRD/DLSS RR、ReSTIR、焦散和完整分信号重建仍未实现。

自动验收：在正常 Vulkan PT 世界站定执行 `/voxellight rt_benchmark start`，结束导出 ZIP 并恢复控制。详见 [自动测试](performance/RT-AUTOMATIC-BENCHMARK.md)。

alpha.34 默认直接光 RIS。第二轮专用验收执行 `/voxellight rt_benchmark direct`，自动对照 legacy/RIS 并恢复设置；见 [第二轮设计](performance/RT-DIRECT-LIGHTING-ROUND-2.md)。

alpha.35：BLAS 优化专用测试执行 `/voxellight rt_benchmark blas`，约 2–4 分钟，上传自动导出的 zip。参见 [BLAS 实施与验收](performance/RT-BLAS-OPTIMIZATION.md)。

第三轮算法显式启用：`/voxellight rt_realtime cache_sparse`。回退为 `rt_realtime full`；reference 强制完整路径。自动测试 `rt_benchmark realtime`（1 spp、realtime，24 段），完成后上传 zip；详见 REALTIME-PT-ROUND3.md。

alpha.40 首先执行 `/voxellight rt_benchmark shader`（8 段）；完整调度/算法扫描用 `rt_benchmark hot`。保持窗口前台、镜头不动，上传自动导出的 zip。详见 HOT-SHADER-CLEANUP.md。
