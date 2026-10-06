# VoxelLight 0.39.0-alpha.20 安装

Minecraft Java 26.2 / Java 25 / Fabric Loader 0.19.5 / Fabric API 0.160.0+26.2。安装包只含本 mod，不重复打包已有前置。

1. 删除旧 VoxelLight jar，将 `mods/voxellight-client-26.2-0.39.0-alpha.20.jar` 放进客户端 `mods/`。
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
