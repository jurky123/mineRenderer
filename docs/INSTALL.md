# VoxelLight 0.39.0-alpha.34 安装

Minecraft Java 26.2 / Java 25 / Fabric Loader 0.19.5 / Fabric API 0.160.0+26.2。安装包只含本 mod，不重复打包已有前置。

1. 删除旧 VoxelLight jar，将 `mods/voxellight-client-26.2-0.39.0-alpha.34.jar` 放进客户端 `mods/`。
2. 视频设置选择原生 Vulkan 图形 API，然后重启。
3. 进入世界，执行以下命令。

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction optix
/voxellight rt_spp 1
/voxellight held_lights on
/voxellight status
```

实时模式使用运动重投影、遮挡/材质校验和独立 OptiX Temporal AOV 去噪；OptiX 初始化失败时自动回退 Vulkan 时域滤波，状态会注明原因。安装包包含独立去噪 DLL/SO，无旧追踪器或 PTX runtime compiler；运行不需要安装 SDK。

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
